package com.example.layoutprovider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 路径⑤ 的「提供方」：把桌面布局以 {@code <workspace>} XML 的形式交给 launcher。
 *
 * <p>本类现在<b>只负责 ContentProvider 契约</b>（P10 重构后）：
 * 规划逻辑在 {@link LayoutPlanner}，配置在 {@link AppConfig}。
 *
 * <p>对外接口：
 * <ul>
 *   <li>{@code openFile()} —— launcher 从这里读布局 XML（URI 带 gridWidth/gridHeight/hotseatSize）</li>
 *   <li>{@code call()} —— 供 UI 与调试用：{@code PLAN_JSON} / {@code SET_OVERRIDE} /
 *       {@code SET_CUSTOM_FOLDER} / {@code APPLY} / {@code APPLY_FULL} / {@code SELFTEST_*} …</li>
 * </ul>
 */
public class LayoutProvider extends ContentProvider {

    private static final String TAG = LayoutPlanner.TAG;
    private static final String AUTH = "com.example.layoutprovider";
    private static final String SECURE_KEY = "launcher3.layout.provider";

    @Override
    public boolean onCreate() {
        return true;
    }

    // ------------------------------------------------------------------ launcher 读取布局

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int gw = intParam(uri, "gridWidth", 4);
        int gh = intParam(uri, "gridHeight", 5);
        int hs = intParam(uri, "hotseatSize", 4);

        AppConfig cfg = AppConfig.load(getContext());
        cfg.saveGrid(getContext(), gw, gh, hs);      // UI 预览要用

        LayoutPlanner.Plan plan = LayoutPlanner.buildPlan(getContext(), cfg, gw, gh, hs);
        String xml = LayoutPlanner.render(plan, gw, gh);

        Log.i(TAG, "收到布局请求: " + uri);
        Log.i(TAG, "网格 " + gw + "x" + gh + ", hotseat=" + hs + ", 配置[" + cfg + "]");
        Log.i(TAG, plan.summary());
        for (Map.Entry<String, Integer> e : plan.bucketSizes.entrySet()) {
            Log.i(TAG, "  分类 [" + e.getKey() + "] = " + e.getValue() + " 个");
        }
        Log.i(TAG, "生成内容:\n" + xml);

        try {
            File out = new File(getContext().getCacheDir(), "workspace-generated.xml");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(out), StandardCharsets.UTF_8)) {
                w.write(xml);
            }
            // 自我记录式基线：把本次输出留一份，供下次「免 root 增量」使用（见 notes/10 §4.3）
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(new File(getContext().getFilesDir(), "last-generated.xml")),
                    StandardCharsets.UTF_8)) {
                w.write(xml);
            } catch (IOException e) {
                Log.w(TAG, "写 last-generated.xml 失败（不影响本次导入）: " + e);
            }
            Snapshots.capture(getContext(), xml);      // 留一份快照，支持回滚
            // 记下"已归位"的应用集合（这份布局已含全部应用）
            List<String> allPkgs = new java.util.ArrayList<>();
            for (LayoutPlanner.Entry e : LayoutPlanner.loadApps(getContext())) allPkgs.add(e.pkg());
            PendingApps.markKnown(getContext(), allPkgs);
            return ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) {
            throw new FileNotFoundException("生成布局失败: " + e);
        }
    }

    @Override
    public String getType(Uri uri) {
        return "text/xml";
    }

    // ------------------------------------------------------------------ call() 接口

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        String authority = launcherAuthority();
        out.putString("LAUNCHER_AUTHORITY", authority);

        try {
            switch (method == null ? "" : method) {

                case "PLAN_JSON":
                    out.putString("JSON", planJson(extras != null && extras.getBoolean("forceFull", false)));
                    out.putString("RESULT", "OK");
                    break;

                // ---- 配置写入 ----

                case "SET_OVERRIDE": {
                    String pkg = extras == null ? null : extras.getString("pkg");
                    if (pkg == null || pkg.isEmpty()) {
                        out.putString("RESULT", "ERR: 缺少 pkg");
                        break;
                    }
                    String bucket = extras.getString("bucket", "");
                    boolean excluded = extras.getBoolean("excluded", false);
                    AppConfig.load(getContext()).setOverride(getContext(), pkg, bucket, excluded);
                    out.putString("RESULT", "OK");
                    Log.i(TAG, "[SET_OVERRIDE] " + pkg + " -> bucket=" + bucket + ", excluded=" + excluded);
                    break;
                }

                case "SET_CUSTOM_FOLDER": {
                    String id = extras == null ? null : extras.getString("id");
                    String name = extras == null ? null : extras.getString("name");
                    String csv = extras == null ? "" : extras.getString("pkgs", "");
                    String used = AppConfig.load(getContext()).setCustomFolder(getContext(), id, name, csv);
                    out.putString("RESULT", "OK");
                    out.putString("ID", used);
                    Log.i(TAG, "[SET_CUSTOM_FOLDER] " + name + " <- " + csv);
                    break;
                }

                case "DELETE_CUSTOM_FOLDER": {
                    String id = extras == null ? null : extras.getString("id");
                    AppConfig.load(getContext()).deleteCustomFolder(getContext(), id);
                    out.putString("RESULT", "OK");
                    break;
                }

                case "CLEAR_OVERRIDES":
                    AppConfig.load(getContext()).clearOverrides(getContext());
                    out.putString("RESULT", "OK");
                    break;

                case "SET_LAYOUT_OPTION": {
                    String key = extras == null ? null : extras.getString("key");
                    String value = extras == null ? "" : extras.getString("value", "");
                    AppConfig.load(getContext()).setLayoutOption(getContext(), key, value);
                    out.putString("RESULT", "OK");
                    Log.i(TAG, "[SET_LAYOUT_OPTION] " + key + " = " + value);
                    break;
                }

                case "SET_MANUAL_DOCK": {
                    String csv = extras == null ? "" : extras.getString("pkgs", "");
                    AppConfig.load(getContext()).setManualDock(getContext(), csv);
                    out.putString("RESULT", "OK");
                    Log.i(TAG, "[SET_MANUAL_DOCK] " + csv);
                    break;
                }

                case "SET_GRID": {
                    int w = extras == null ? 0 : extras.getInt("w", 0);
                    int h = extras == null ? 0 : extras.getInt("h", 0);
                    int hs = extras == null ? 0 : extras.getInt("hs", 0);
                    if (w >= 2 && h >= 3 && hs >= 1) {
                        AppConfig.load(getContext()).saveGrid(getContext(), w, h, hs);
                        out.putString("RESULT", "OK");
                        Log.i(TAG, "[SET_GRID] " + w + "x" + h + " hotseat=" + hs);
                    } else {
                        out.putString("RESULT", "ERR: 网格参数不合法");
                    }
                    break;
                }

                case "SET_BUCKET_NAME": {
                    String bucket = extras == null ? null : extras.getString("bucket");
                    String name = extras == null ? "" : extras.getString("name", "");
                    AppConfig.load(getContext()).setBucketName(getContext(), bucket, name);
                    out.putString("RESULT", "OK");
                    Log.i(TAG, "[SET_BUCKET_NAME] " + bucket + " -> " + name);
                    break;
                }

                case "EXPORT_CONFIG":
                    out.putString("JSON", AppConfig.exportCurrent(getContext()));
                    out.putString("RESULT", "OK");
                    break;

                case "IMPORT_CONFIG": {
                    if (arg == null || arg.trim().isEmpty()) {
                        out.putString("RESULT", "ERR: 内容为空");
                    } else {
                        AppConfig.importInto(getContext(), arg);
                        out.putString("RESULT", "OK");
                        Log.i(TAG, "[IMPORT_CONFIG] " + arg.length() + " 字节");
                    }
                    break;
                }

                case "PROFILE_LIST": {
                    List<String> names = AppConfig.profileNames(getContext());
                    String active = AppConfig.load(getContext()).activeProfileName;
                    StringBuilder b = new StringBuilder("[");
                    boolean f = true;
                    for (String n : names) {
                        if (!f) b.append(',');
                        f = false;
                        b.append("{\"name\":").append(jsonStr(n))
                         .append(",\"active\":").append(n.equals(active)).append('}');
                    }
                    out.putString("JSON", b.append(']').toString());
                    out.putString("RESULT", "OK");
                    break;
                }

                case "PROFILE_SAVE": {
                    String name = extras == null ? null : extras.getString("name");
                    AppConfig.load(getContext()).saveProfile(getContext(), name);
                    out.putString("RESULT", "OK");
                    Log.i(TAG, "[PROFILE_SAVE] " + name);
                    break;
                }

                case "PROFILE_LOAD": {
                    String name = extras == null ? null : extras.getString("name");
                    boolean ok = name != null
                            && AppConfig.load(getContext()).loadProfile(getContext(), name);
                    out.putString("RESULT", ok ? "OK" : "ERR: 方案不存在");
                    Log.i(TAG, "[PROFILE_LOAD] " + name + " -> " + ok);
                    break;
                }

                case "PROFILE_DELETE": {
                    String name = extras == null ? null : extras.getString("name");
                    if (name != null) AppConfig.load(getContext()).deleteProfile(getContext(), name);
                    out.putString("RESULT", "OK");
                    break;
                }

                case "LIST_SNAPSHOTS": {
                    StringBuilder b = new StringBuilder("[");
                    boolean f = true;
                    for (Snapshots.Item it : Snapshots.list(getContext())) {
                        if (!f) b.append(',');
                        f = false;
                        b.append("{\"id\":").append(jsonStr(it.id))
                         .append(",\"time\":").append(it.time)
                         .append(",\"folders\":").append(it.folders)
                         .append(",\"apps\":").append(it.apps)
                         .append(",\"current\":").append(it.current)
                         .append('}');
                    }
                    out.putString("JSON", b.append(']').toString());
                    out.putString("RESULT", "OK");
                    break;
                }

                case "RESTORE_SNAPSHOT": {
                    String id = extras == null ? null : extras.getString("id");
                    boolean ok = id != null && Snapshots.restore(getContext(), id);
                    out.putString("RESULT", ok ? "OK" : "ERR: 快照不存在");
                    Log.i(TAG, "[RESTORE_SNAPSHOT] " + id + " -> " + ok);
                    break;
                }

                // ---- 应用 ----

                case "APPLY":
                    out.putString("RESULT", applySecureSetting());
                    Log.i(TAG, "[APPLY] " + out.getString("RESULT"));
                    break;

                case "APPLY_FULL": {
                    String w = applySecureSetting();
                    if (!w.startsWith("OK")) {
                        out.putString("RESULT", w);
                        break;
                    }
                    if (authority == null) {
                        out.putString("RESULT", "ERR: 找不到默认 HOME 应用");
                        break;
                    }
                    String launcherPkg = authority.substring(0, authority.length() - ".settings".length());
                    String r2 = execSu("pm clear " + launcherPkg);
                    String r3 = execSu("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
                    if (!"OK".equals(r2)) {
                        out.putString("RESULT", w + "；但重置桌面失败：" + r2);
                    } else {
                        out.putString("RESULT", "OK");
                        Log.i(TAG, "[APPLY_FULL] pm clear " + launcherPkg + " -> " + r2 + "; start -> " + r3);
                    }
                    break;
                }

                // ---- 自检：验证普通 app 能否调用 launcher 的 provider（见 notes/10） ----

                case "SELFTEST_EXPORT": {
                    if (authority == null) {
                        out.putString("RESULT", "ERR: 找不到默认 HOME 应用");
                        break;
                    }
                    Bundle res = getContext().getContentResolver().call(
                            Uri.parse("content://" + authority), "EXPORT_LAYOUT_XML", null, null);
                    out.putString("RESULT", "OK");
                    out.putString("KEY_RESULT", res == null ? "(null bundle)" : res.getString("KEY_RESULT"));
                    String xml = res == null ? null : res.getString("KEY_LAYOUT");
                    out.putString("KEY_LAYOUT", xml);
                    Log.i(TAG, "[SELFTEST_EXPORT] 成功，拿到 "
                            + (xml == null ? "null" : xml.length() + " 字节"));
                    break;
                }

                case "SELFTEST_IMPORT": {
                    if (authority == null) {
                        out.putString("RESULT", "ERR: 找不到默认 HOME 应用");
                        break;
                    }
                    Bundle res = getContext().getContentResolver().call(
                            Uri.parse("content://" + authority), "IMPORT_LAYOUT_XML", arg, null);
                    out.putString("RESULT", "OK");
                    out.putString("KEY_RESULT", res == null ? "(null bundle)" : res.getString("KEY_RESULT"));
                    Log.i(TAG, "[SELFTEST_IMPORT] 成功");
                    break;
                }

                default:
                    out.putString("RESULT", "ERR: 未知 method " + method);
            }
        } catch (Throwable t) {
            out.putString("RESULT", "ERR: " + t);
            Log.e(TAG, "[" + method + "] 失败", t);
        }
        return out;
    }

    // ------------------------------------------------------------------ 应用（写 Secure Settings）

    /**
     * 写 Secure Settings 的**三级策略**：
     * ① **直接写** —— 需要 {@code WRITE_SECURE_SETTINGS}（其 protectionLevel 含 {@code development}，
     *    在 userdebug/eng 构建上可用 {@code adb shell pm grant} 一次性授予）
     * ② **借 root** —— {@code su -c settings put …}
     * ③ 都失败 → 返回提示，用户改用界面上的 adb 命令
     */
    private String applySecureSetting() {
        try {
            android.provider.Settings.Secure.putString(
                    getContext().getContentResolver(), SECURE_KEY, AUTH);
            return "OK（直接写入）";
        } catch (Throwable t) {
            Log.i(TAG, "直接写 Secure Settings 失败：" + t);
        }
        return execSu("settings put secure " + SECURE_KEY + " " + AUTH);
    }

    private static String execSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int code = p.waitFor();
            if (code == 0) return "OK";
            return "su 退出码 " + code + "（设备可能未 root）";
        } catch (Exception e) {
            return "无法执行 su：" + e + "（设备未 root，请改用下面的 adb 命令）";
        }
    }

    // ------------------------------------------------------------------ UI 用 JSON

    /** 给 UI 的完整快照：应用清单 + 规划结果 + XML */
    private String planJson(boolean forceFull) {
        Context ctx = getContext();
        AppConfig cfg = AppConfig.load(ctx);
        LayoutPlanner.Plan plan = LayoutPlanner.buildPlan(
                ctx, cfg, cfg.gridW, cfg.gridH, cfg.gridHotseat, forceFull);
        String xml = LayoutPlanner.render(plan, cfg.gridW, cfg.gridH);

        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        String launcherPkg = (ri != null && ri.activityInfo != null) ? ri.activityInfo.packageName : "";

        // 每个应用去了哪
        Map<String, String> dest = new HashMap<>();
        for (LayoutPlanner.Entry e : plan.hotseat) dest.put(e.pkg(), "dock");
        for (LayoutPlanner.Placement p : plan.placements) {
            if ("folder".equals(p.kind) && p.apps != null) {
                for (LayoutPlanner.Entry e : p.apps) dest.put(e.pkg(), "folder:" + p.folderName);
            } else if ("icon".equals(p.kind) && p.apps != null) {
                dest.put(p.apps.get(0).pkg(), "desktop");
            }
        }

        Set<String> customSet = new HashSet<>();
        for (AppConfig.CustomFolder cf : cfg.customFolders()) customSet.addAll(cf.pkgs);

        // 使用频率（供 UI 显示排序依据与授权状态）
        UsageRank usage = UsageRank.load(ctx);

        // 变更预览（与上次生成的布局对比）
        LayoutDiff diff = LayoutDiff.compute(ctx, plan);

        // 应用清单只取一次，下面多处复用（避免重复扫描 PackageManager）
        List<LayoutPlanner.Entry> allApps = LayoutPlanner.loadApps(ctx);

        StringBuilder sb = new StringBuilder(32768);
        sb.append('{');
        sb.append("\"launcher\":").append(jsonStr(launcherPkg)).append(',');
        sb.append("\"gridKnown\":").append(cfg.gridKnown).append(',');
        sb.append("\"grid\":{\"w\":").append(cfg.gridW).append(",\"h\":").append(cfg.gridH)
          .append(",\"hotseat\":").append(cfg.gridHotseat).append("},");
        sb.append("\"topReserved\":").append(LayoutPlanner.TOP_RESERVED).append(',');
        sb.append("\"maxScreens\":").append(LayoutPlanner.MAX_SCREENS).append(',');
        sb.append("\"mergeMode\":").append(plan.mergeMode).append(',');
        sb.append("\"mergeSource\":").append(jsonStr(plan.mergeSource)).append(',');
        sb.append("\"totalApps\":").append(plan.totalApps).append(',');
        sb.append("\"keptFolders\":").append(plan.keptFolders).append(',');
        sb.append("\"added\":").append(plan.added).append(',');
        sb.append("\"removed\":").append(plan.removed).append(',');
        sb.append("\"dropped\":").append(plan.dropped).append(',');
        sb.append("\"excluded\":").append(plan.excluded).append(',');
        sb.append("\"customFolders\":").append(plan.customFolders).append(',');
        sb.append("\"customSkipped\":").append(plan.customSkipped).append(',');
        sb.append("\"config\":").append(jsonStr(cfg.toString())).append(',');
        sb.append("\"dockMode\":").append(jsonStr(cfg.dockMode)).append(',');
        sb.append("\"folderSort\":").append(jsonStr(cfg.folderSort)).append(',');
        sb.append("\"screenStrategy\":").append(jsonStr(cfg.screenStrategy)).append(',');
        sb.append("\"usageStatus\":").append(jsonStr(usage.status())).append(',');
        sb.append("\"activeProfile\":").append(jsonStr(cfg.activeProfileName)).append(',');
        sb.append("\"profiles\":").append(strList(AppConfig.profileNames(ctx))).append(',');
        sb.append("\"usageAvailable\":").append(usage.available()).append(',');
        sb.append("\"usageCount\":").append(usage.size()).append(',');
        sb.append("\"usageTop\":[");
        // 只列**启动器应用** —— 否则会把开发期间长时间在前台的自家 app / launcher 也列出来，
        // 对用户是噪音（实测模拟器上 top1 是 com.example.layoutprovider 64623s）
        Set<String> launcherPkgs = new HashSet<>();
        for (LayoutPlanner.Entry e : allApps) launcherPkgs.add(e.pkg());
        boolean f3 = true;
        int shown = 0;
        for (String p : usage.top(40)) {
            if (shown >= 6) break;
            if (!launcherPkgs.contains(p)) continue;
            if (!f3) sb.append(',');
            f3 = false;
            shown++;
            sb.append("{\"pkg\":").append(jsonStr(p))
              .append(",\"score\":").append(usage.score(p)).append('}');
        }
        sb.append("],");

        sb.append("\"manualDock\":[");
        boolean f4 = true;
        for (String p : cfg.manualDock) {
            if (!f4) sb.append(',');
            f4 = false;
            sb.append(jsonStr(p));
        }
        sb.append("],");

        sb.append("\"diff\":{")
          .append("\"hasBaseline\":").append(diff.hasBaseline).append(',')
          .append("\"kept\":").append(diff.kept).append(',')
          .append("\"added\":").append(strList(diff.added)).append(',')
          .append("\"removed\":").append(strList(diff.removed)).append(',')
          .append("\"moved\":").append(strList(diff.moved))
          .append("},");

        // 待归位（新装 / 卸载）
        PendingApps.Result pend = PendingApps.pending(ctx, allApps);
        sb.append("\"pending\":{")
          .append("\"added\":").append(strList(pend.added)).append(',')
          .append("\"removed\":").append(strList(pend.removed))
          .append("},");

        // 分类显示名映射
        sb.append("\"bucketNames\":{");
        boolean f5 = true;
        for (Map.Entry<String, String> e : cfg.bucketNames().entrySet()) {
            if (!f5) sb.append(',');
            f5 = false;
            sb.append(jsonStr(e.getKey())).append(':').append(jsonStr(e.getValue()));
        }
        sb.append("},");

        sb.append("\"buckets\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : plan.bucketSizes.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(jsonStr(e.getKey())).append(':').append(e.getValue());
        }
        sb.append("},");

        sb.append("\"apps\":[");
        first = true;
        for (LayoutPlanner.Entry e : allApps) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"label\":").append(jsonStr(e.label()))
              .append(",\"pkg\":").append(jsonStr(e.pkg()))
              .append(",\"cls\":").append(jsonStr(e.cls()))
              .append(",\"bucket\":").append(jsonStr(LayoutPlanner.bucketOf(cfg, e)))
              .append(",\"auto\":").append(jsonStr(LayoutPlanner.bucketOfAuto(e)))
              .append(",\"overridden\":").append(cfg.hasOverride(e.pkg()))
              .append(",\"excluded\":").append(cfg.isExcluded(e.pkg()))
              .append(",\"inCustom\":").append(customSet.contains(e.pkg()))
              .append(",\"usage\":").append(usage.score(e.pkg()))
              .append(",\"icon\":").append(jsonStr(iconDataUri(e.pkg())))
              .append(",\"dest\":").append(jsonStr(cfg.isExcluded(e.pkg()) ? "excluded"
                      : (dest.containsKey(e.pkg()) ? dest.get(e.pkg()) : "dropped")))
              .append('}');
        }
        sb.append("],");

        sb.append("\"customFolderList\":[");
        first = true;
        for (AppConfig.CustomFolder cf : cfg.customFolders()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(jsonStr(cf.id))
              .append(",\"name\":").append(jsonStr(cf.name))
              .append(",\"count\":").append(cf.pkgs.size())
              .append(",\"pkgs\":[");
            boolean f2 = true;
            for (String p : cf.pkgs) {
                if (!f2) sb.append(',');
                f2 = false;
                sb.append(jsonStr(p));
            }
            sb.append("]}");
        }
        sb.append("],");

        sb.append("\"placements\":[");
        first = true;
        for (LayoutPlanner.Placement p : plan.placements) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"kind\":").append(jsonStr(p.kind))
              .append(",\"screen\":").append(p.screen)
              .append(",\"x\":").append(p.x).append(",\"y\":").append(p.y)
              .append(",\"spanX\":").append(p.spanX).append(",\"spanY\":").append(p.spanY)
              .append(",\"title\":").append(jsonStr(p.folderName == null ? "" : p.folderName))
              .append(",\"labels\":[");
            if (p.apps != null) {
                boolean f2 = true;
                for (LayoutPlanner.Entry e : p.apps) {
                    if (!f2) sb.append(',');
                    f2 = false;
                    sb.append(jsonStr(e.label()));
                }
            }
            sb.append("]}");
        }
        sb.append("],");

        sb.append("\"hotseat\":[");
        first = true;
        for (LayoutPlanner.Entry e : plan.hotseat) {
            if (!first) sb.append(',');
            first = false;
            sb.append(jsonStr(e.label()));
        }
        sb.append("],");

        sb.append("\"xml\":").append(jsonStr(xml));
        sb.append('}');
        return sb.toString();
    }

    /** 应用图标 → data:image/png;base64,…（给 UI 显示；取不到返回空串） */
    private String iconDataUri(String pkg) {
        try {
            PackageManager pm = getContext().getPackageManager();
            android.graphics.drawable.Drawable d = pm.getApplicationIcon(pkg);
            int size = 72;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos);
            bmp.recycle();
            return "data:image/png;base64," + android.util.Base64.encodeToString(
                    bos.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String strList(List<String> xs) {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (String s : xs) {
            if (!first) b.append(',');
            first = false;
            b.append(jsonStr(s));
        }
        return b.append(']').toString();
    }

    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder(s.length() + 16);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }

    /** 当前默认 HOME 应用对应的 launcher provider authority（约定：<包名>.settings） */
    private String launcherAuthority() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getContext().getPackageManager()
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null || ri.activityInfo.packageName == null) return null;
        return ri.activityInfo.packageName + ".settings";
    }

    private static int intParam(Uri uri, String key, int def) {
        try {
            String v = uri.getQueryParameter(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ 其余契约（不用）

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
