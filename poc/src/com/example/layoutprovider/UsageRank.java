package com.example.layoutprovider;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用使用频率（"谁对你更重要"）。
 *
 * <p>数据源 {@link UsageStatsManager}，需要 {@code PACKAGE_USAGE_STATS} 权限
 * （appop {@code GET_USAGE_STATS}）。用户侧在「设置 → 特殊应用权限 → 使用情况访问」里授权，
 * **免 root 可得**；userdebug/eng 构建也可用 {@code adb shell appops set <pkg> GET_USAGE_STATS allow}。
 *
 * <p><b>三层降级</b>（任何一层不满足都安全退回，绝不让排布出错）：
 * <ol>
 *   <li>未授权 → {@code status="未授权"}，{@code available=false}</li>
 *   <li>已授权但无数据（新机 / 模拟器）→ {@code status="无数据"}，{@code available=false}</li>
 *   <li>有数据 → {@code available=true}，按 score 排序</li>
 * </ol>
 *
 * <p>打分：近 7 天时长 × 1.0 + 近 8–30 天时长 × 0.4（近期行为权重更高）。
 * 查询耗时可能 200–800ms，所以带缓存（内存 + 文件，TTL 30 分钟）。
 */
public final class UsageRank {

    private static final String TAG = LayoutPlanner.TAG;
    private static final String CACHE_FILE = "usage-cache.txt";
    private static final long TTL_MS = 30 * 60 * 1000L;
    private static final long DAY = 24 * 60 * 60 * 1000L;

    private final Map<String, Integer> scores = new HashMap<>();
    private boolean available;
    private String status = "未授权";

    private UsageRank() { }

    public boolean available() { return available; }
    public String status() { return status; }
    public int score(String pkg) { return scores.getOrDefault(pkg, 0); }
    public int size() { return scores.size(); }

    /** 按分数降序的前 n 个包名（只含分数 > 0 的） */
    public List<String> top(int n) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(scores.entrySet());
        es.removeIf(e -> e.getValue() == null || e.getValue() <= 0);
        es.sort((a, b) -> b.getValue() - a.getValue());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, es.size()); i++) out.add(es.get(i).getKey());
        return out;
    }

    // ------------------------------------------------------------------ 读取

    public static UsageRank load(Context ctx) {
        UsageRank r = new UsageRank();

        if (!hasPermission(ctx)) {
            r.status = "未授权";
            Log.i(TAG, "[usage] 未授予「使用情况访问」");
            return r;
        }

        // 内存/文件缓存
        Map<String, Integer> cached = readCache(ctx);
        if (cached != null) {
            r.scores.putAll(cached);
            r.finish();
            Log.i(TAG, "[usage] 命中缓存，" + r.scores.size() + " 个应用，status=" + r.status);
            return r;
        }

        try {
            UsageStatsManager usm =
                    (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            long weekAgo = now - 7 * DAY;
            long monthAgo = now - 30 * DAY;

            Map<String, Long> recent = sum(usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, weekAgo, now));
            Map<String, Long> older = sum(usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, monthAgo, weekAgo));

            for (Map.Entry<String, Long> e : recent.entrySet()) {
                r.scores.merge(e.getKey(), (int) (e.getValue() / 1000), Integer::sum);
            }
            for (Map.Entry<String, Long> e : older.entrySet()) {
                // 8–30 天权重 0.4
                r.scores.merge(e.getKey(), (int) (e.getValue() / 1000 * 0.4), Integer::sum);
            }
            writeCache(ctx, r.scores);
        } catch (Throwable t) {
            Log.w(TAG, "[usage] 查询失败: " + t);
        }

        r.finish();
        Log.i(TAG, "[usage] 查询完成，" + r.scores.size() + " 个应用，status=" + r.status);
        return r;
    }

    private void finish() {
        boolean any = false;
        for (Integer v : scores.values()) {
            if (v != null && v > 0) { any = true; break; }
        }
        available = any;
        status = any ? "可用" : "无数据";
    }

    private static Map<String, Long> sum(List<UsageStats> list) {
        Map<String, Long> m = new HashMap<>();
        if (list == null) return m;
        for (UsageStats s : list) {
            if (s == null || s.getPackageName() == null) continue;
            m.merge(s.getPackageName(), s.getTotalTimeInForeground(), Long::sum);
        }
        return m;
    }

    /** appop 检查（比 checkPermission 可靠：该权限是 appop 类型） */
    private static boolean hasPermission(Context ctx) {
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 缓存（简单文本格式，避免引 JSON 解析器）

    private static File cacheFile(Context ctx) {
        return new File(ctx.getCacheDir(), CACHE_FILE);
    }

    /** 命中且未过期才返回；否则 null */
    private static Map<String, Integer> readCache(Context ctx) {
        File f = cacheFile(ctx);
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String head = r.readLine();
            if (head == null) return null;
            long ts = Long.parseLong(head.trim());
            if (System.currentTimeMillis() - ts > TTL_MS) return null;
            Map<String, Integer> m = new HashMap<>();
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.lastIndexOf('=');
                if (i <= 0) continue;
                try {
                    m.put(line.substring(0, i), Integer.parseInt(line.substring(i + 1).trim()));
                } catch (NumberFormatException ignored) { }
            }
            return m.isEmpty() ? null : m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeCache(Context ctx, Map<String, Integer> m) {
        try (FileWriter w = new FileWriter(cacheFile(ctx))) {
            w.write(String.valueOf(System.currentTimeMillis()));
            w.write('\n');
            List<String> keys = new ArrayList<>(m.keySet());
            Collections.sort(keys);
            for (String k : keys) {
                w.write(k);
                w.write('=');
                w.write(String.valueOf(m.get(k)));
                w.write('\n');
            }
        } catch (IOException e) {
            Log.w(TAG, "[usage] 写缓存失败: " + e);
        }
    }

    /** 调试/测试用：从外部注入分数（不落盘） */
    static UsageRank of(Map<String, Integer> m) {
        UsageRank r = new UsageRank();
        if (m != null) r.scores.putAll(m);
        r.finish();
        return r;
    }
}
