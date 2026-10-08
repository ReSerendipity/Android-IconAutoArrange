package com.example.layoutprovider;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用的全部配置（收口）。
 *
 * <p>这是 P10 重构的产物：原本配置散在 provider 里（调试开关读文件、覆盖与手动分组读
 * SharedPreferences、网格也读 SharedPreferences），现在统一由本类读写，
 * 后续的 Profile / Dock / 首屏策略 / 导入导出都只需在这里扩展。
 *
 * <p>存储格式（**暂与重构前完全一致**，保证行为不变）：
 * <ul>
 *   <li>调试开关：{@code <filesDir>/poc-config.txt}（key=value）</li>
 *   <li>用户覆盖：prefs {@code poc} 的 {@code ov:<包名>} = {@code "<分类>|0或1"}（末位 1=排除）</li>
 *   <li>手动分组：prefs {@code poc} 的 {@code cf:<id>} = {@code "<名称>\t<pkg1,pkg2,...>"}</li>
 *   <li>网格：prefs {@code poc} 的 {@code gw}/{@code gh}/{@code hs}</li>
 * </ul>
 */
public final class AppConfig {

    static final String TAG = "LayoutProviderPoC";

    private static final String CONFIG_FILE = "poc-config.txt";
    private static final String PREFS = "poc";
    private static final String OV_PREFIX = "ov:";
    private static final String CF_PREFIX = "cf:";
    private static final String BN_PREFIX = "bn:";

    /** 分类显示名重映射（原名 → 显示名） */
    private final Map<String, String> bucketNames = new HashMap<>();

    // ------------------------------------------------------------------ 调试开关

    public boolean widgets = false;
    /** ⚠️ searchwidget 在 Lawnchair 上必然抛 NPE（见 notes/09 §3），默认关闭 */
    public boolean searchWidget = false;
    public boolean spread = false;
    public int repeat = 1;

    // ------------------------------------------------------------------ 网格

    public int gridW = 4;
    public int gridH = 6;
    public int gridHotseat = 4;
    /** launcher 是否至少调用过一次（否则网格是默认值，UI 要提示） */
    public boolean gridKnown = false;

    // ------------------------------------------------------------------ 排布策略

    /** Dock 来源：{@code auto}=硬编码优先级（默认，保持原行为）/ {@code usage}=按使用频率 / {@code manual}=用户指定 */
    public String dockMode = "auto";
    /** dockMode=manual 时的用户选择（按顺序） */
    public final List<String> manualDock = new ArrayList<>();
    /** 文件夹内排序：{@code name}（默认）/ {@code usage} */
    public String folderSort = "name";
    /** 首屏策略：{@code balanced}（默认）/ {@code usage}=常用优先 */
    public String screenStrategy = "balanced";

    // ------------------------------------------------------------------ 用户覆盖 / 手动分组

    /** 包名 → {分类, "1"=排除 / "0"=纳入} */
    private final Map<String, String[]> overrides = new HashMap<>();
    private final List<CustomFolder> customFolders = new ArrayList<>();

    /** 用户手动创建的分组 → 桌面上会变成一个文件夹 */
    public static final class CustomFolder {
        public final String id;
        public final String name;
        public final List<String> pkgs;

        CustomFolder(String id, String name, List<String> pkgs) {
            this.id = id;
            this.name = name;
            this.pkgs = pkgs;
        }
    }

    private AppConfig() { }

    // ------------------------------------------------------------------ 读

    public static AppConfig load(Context ctx) {
        AppConfig c = new AppConfig();
        c.loadDebugFlags(ctx);
        c.loadGrid(ctx);
        c.loadOverridesAndFolders(ctx);
        return c;
    }

    private void loadDebugFlags(Context ctx) {
        File f = new File(ctx.getFilesDir(), CONFIG_FILE);
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                String[] kv = line.split("=", 2);
                String k = kv[0].trim();
                String v = kv[1].trim();
                boolean on = "1".equals(v) || "true".equalsIgnoreCase(v);
                if ("widgets".equals(k)) widgets = on;
                else if ("searchwidget".equals(k)) searchWidget = on;
                else if ("spread".equals(k)) spread = on;
                else if ("repeat".equals(k)) {
                    try { repeat = Math.max(1, Math.min(9, Integer.parseInt(v))); }
                    catch (NumberFormatException ignored) { }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "读取 " + CONFIG_FILE + " 失败: " + e);
        }
    }

    private void loadGrid(Context ctx) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        gridKnown = sp.contains("gw");
        gridW = sp.getInt("gw", 4);
        gridH = sp.getInt("gh", 6);
        gridHotseat = sp.getInt("hs", 4);
        dockMode = sp.getString("dockMode", "auto");
        folderSort = sp.getString("folderSort", "name");
        screenStrategy = sp.getString("screenStrategy", "balanced");
        for (String p : sp.getString("manualDock", "").split(",")) {
            p = p.trim();
            if (!p.isEmpty()) manualDock.add(p);
        }
        activeProfileName = sp.getString("activeProfile", "默认");
    }

    /** 设置 Dock 成员（有序） */
    public void setManualDock(Context ctx, String pkgsCsv) {
        prefs(ctx).edit().putString("manualDock", pkgsCsv == null ? "" : pkgsCsv).apply();
        manualDock.clear();
        for (String p : (pkgsCsv == null ? "" : pkgsCsv).split(",")) {
            p = p.trim();
            if (!p.isEmpty()) manualDock.add(p);
        }
    }

    /** 通用策略开关（UI 用）：key ∈ {dockMode, folderSort, screenStrategy} */
    public void setLayoutOption(Context ctx, String key, String value) {
        if (value == null) value = "";
        switch (key == null ? "" : key) {
            case "dockMode":
                if (!"usage".equals(value) && !"manual".equals(value)) value = "auto";
                dockMode = value;
                break;
            case "folderSort":
                if (!"usage".equals(value)) value = "name";
                folderSort = value;
                break;
            case "screenStrategy":
                if (!"usage".equals(value)) value = "balanced";
                screenStrategy = value;
                break;
            default:
                return;
        }
        prefs(ctx).edit().putString(key, value).apply();
    }

    private void loadOverridesAndFolders(Context ctx) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String k = e.getKey();
            String v = String.valueOf(e.getValue());

            if (k.startsWith(BN_PREFIX)) {
                bucketNames.put(k.substring(BN_PREFIX.length()), v);

            } else if (k.startsWith(OV_PREFIX)) {
                int i = v.indexOf('|');
                if (i < 0) continue;
                overrides.put(k.substring(OV_PREFIX.length()),
                        new String[]{v.substring(0, i), v.substring(i + 1)});

            } else if (k.startsWith(CF_PREFIX)) {
                int t = v.indexOf('\t');
                String name = t < 0 ? "我的分组" : v.substring(0, t);
                String rest = t < 0 ? v : v.substring(t + 1);
                List<String> pkgs = new ArrayList<>();
                for (String p : rest.split(",")) {
                    p = p.trim();
                    if (!p.isEmpty()) pkgs.add(p);
                }
                customFolders.add(new CustomFolder(k.substring(CF_PREFIX.length()), name, pkgs));
            }
        }
    }

    // ------------------------------------------------------------------ 查询

    /** 用户覆盖的分类；没有则返回 null */
    public String overrideBucket(String pkg) {
        String[] ov = overrides.get(pkg);
        return (ov != null && ov[0] != null && !ov[0].isEmpty()) ? ov[0] : null;
    }

    public boolean hasOverride(String pkg) {
        return overrideBucket(pkg) != null;
    }

    public boolean isExcluded(String pkg) {
        String[] ov = overrides.get(pkg);
        return ov != null && "1".equals(ov[1]);
    }

    public List<CustomFolder> customFolders() {
        return customFolders;
    }

    /** 分类的显示名（没改过就返回原名） */
    public String displayBucket(String bucket) {
        String v = bucketNames.get(bucket);
        return (v == null || v.isEmpty()) ? bucket : v;
    }

    public Map<String, String> bucketNames() {
        return bucketNames;
    }

    /** 重命名分类（名字留空 = 恢复原名） */
    public void setBucketName(Context ctx, String bucket, String name) {
        if (bucket == null || bucket.isEmpty()) return;
        if (name == null) name = "";
        name = name.trim();
        if (name.isEmpty()) {
            prefs(ctx).edit().remove(BN_PREFIX + bucket).apply();
            bucketNames.remove(bucket);
        } else {
            prefs(ctx).edit().putString(BN_PREFIX + bucket, name).apply();
            bucketNames.put(bucket, name);
        }
    }

    // ------------------------------------------------------------------ 写

    public void setOverride(Context ctx, String pkg, String bucket, boolean excluded) {
        String val = (bucket == null ? "" : bucket) + "|" + (excluded ? "1" : "0");
        prefs(ctx).edit().putString(OV_PREFIX + pkg, val).apply();
        overrides.put(pkg, new String[]{bucket == null ? "" : bucket, excluded ? "1" : "0"});
    }

    public void clearOverrides(Context ctx) {
        android.content.SharedPreferences sp = prefs(ctx);
        android.content.SharedPreferences.Editor ed = sp.edit();
        for (String k : new ArrayList<>(sp.getAll().keySet())) {
            if (k.startsWith(OV_PREFIX)) ed.remove(k);
        }
        ed.apply();
        overrides.clear();
    }

    /** @return 实际使用的 id（新建时自动生成） */
    public String setCustomFolder(Context ctx, String id, String name, String pkgsCsv) {
        if (name == null || name.trim().isEmpty()) name = "我的分组";
        if (id == null || id.isEmpty()) id = "g" + System.currentTimeMillis();
        prefs(ctx).edit().putString(CF_PREFIX + id, name.trim() + "\t" + pkgsCsv).apply();
        return id;
    }

    public void deleteCustomFolder(Context ctx, String id) {
        if (id == null || id.isEmpty()) return;
        prefs(ctx).edit().remove(CF_PREFIX + id).apply();
    }

    public void saveGrid(Context ctx, int w, int h, int hs) {
        prefs(ctx).edit().putInt("gw", w).putInt("gh", h).putInt("hs", hs).apply();
        gridW = w; gridH = h; gridHotseat = hs; gridKnown = true;
    }

    // ------------------------------------------------------------------ 多套方案（Profile）

    private static final String PROFILE_PREFIX = "profile:";
    private static final String PROFILE_LIST = "profileNames";

    /** 当前生效的方案名（仅用于 UI 显示） */
    public String activeProfileName = "默认";

    /**
     * 把当前配置序列化成 JSON。
     *
     * <p>设计取舍：**方案 = 一份配置快照，切换 = 把快照写回平铺 key**。
     * 这样完全不需要给 key 加命名空间、也不需要迁移历史数据 ——
     * 相比"重写整个配置存储层"，这条路的回归风险低得多。
     */
    public String toJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("dockMode", dockMode);
            o.put("folderSort", folderSort);
            o.put("screenStrategy", screenStrategy);
            o.put("manualDock", new org.json.JSONArray(manualDock));

            org.json.JSONObject ov = new org.json.JSONObject();
            for (Map.Entry<String, String[]> e : overrides.entrySet()) {
                ov.put(e.getKey(), e.getValue()[0] + "|" + e.getValue()[1]);
            }
            o.put("overrides", ov);

            org.json.JSONArray cfs = new org.json.JSONArray();
            for (CustomFolder cf : customFolders) {
                org.json.JSONObject c = new org.json.JSONObject();
                c.put("id", cf.id);
                c.put("name", cf.name);
                c.put("pkgs", new org.json.JSONArray(cf.pkgs));
                cfs.put(c);
            }
            o.put("customFolders", cfs);
            return o.toString();
        } catch (Throwable t) {
            Log.w(TAG, "toJson 失败: " + t);
            return "{}";
        }
    }

    /** 把一份配置快照写回平铺 key（会先清掉现有的覆盖与手动分组） */
    public void applyJson(Context ctx, String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            android.content.SharedPreferences sp = prefs(ctx);
            android.content.SharedPreferences.Editor ed = sp.edit();

            for (String k : new ArrayList<>(sp.getAll().keySet())) {
                if (k.startsWith(OV_PREFIX) || k.startsWith(CF_PREFIX)) ed.remove(k);
            }

            ed.putString("dockMode", o.optString("dockMode", "auto"));
            ed.putString("folderSort", o.optString("folderSort", "name"));
            ed.putString("screenStrategy", o.optString("screenStrategy", "balanced"));

            StringBuilder csv = new StringBuilder();
            org.json.JSONArray md = o.optJSONArray("manualDock");
            if (md != null) {
                for (int i = 0; i < md.length(); i++) {
                    if (csv.length() > 0) csv.append(',');
                    csv.append(md.optString(i));
                }
            }
            ed.putString("manualDock", csv.toString());

            org.json.JSONObject ov = o.optJSONObject("overrides");
            if (ov != null) {
                for (java.util.Iterator<String> it = ov.keys(); it.hasNext(); ) {
                    String k = it.next();
                    ed.putString(OV_PREFIX + k, ov.optString(k));
                }
            }

            org.json.JSONArray cfs = o.optJSONArray("customFolders");
            if (cfs != null) {
                for (int i = 0; i < cfs.length(); i++) {
                    org.json.JSONObject c = cfs.optJSONObject(i);
                    if (c == null) continue;
                    StringBuilder pk = new StringBuilder();
                    org.json.JSONArray arr = c.optJSONArray("pkgs");
                    if (arr != null) {
                        for (int j = 0; j < arr.length(); j++) {
                            if (pk.length() > 0) pk.append(',');
                            pk.append(arr.optString(j));
                        }
                    }
                    ed.putString(CF_PREFIX + c.optString("id", "g" + i),
                            c.optString("name", "我的分组") + "\t" + pk);
                }
            }
            ed.apply();
        } catch (Throwable t) {
            Log.w(TAG, "applyJson 失败: " + t);
        }
    }

    public static List<String> profileNames(Context ctx) {
        List<String> out = new ArrayList<>();
        for (String s : prefs(ctx).getString(PROFILE_LIST, "").split(",")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 把当前配置存成一个（或覆盖同名）方案，并标记为当前方案 */
    public void saveProfile(Context ctx, String name) {
        if (name == null || name.trim().isEmpty()) name = "方案";
        name = name.trim();
        List<String> names = profileNames(ctx);
        if (!names.contains(name)) names.add(name);
        prefs(ctx).edit()
                .putString(PROFILE_PREFIX + name, toJson())
                .putString(PROFILE_LIST, String.join(",", names))
                .putString("activeProfile", name)
                .apply();
        activeProfileName = name;
    }

    public boolean loadProfile(Context ctx, String name) {
        String json = prefs(ctx).getString(PROFILE_PREFIX + name, null);
        if (json == null) return false;
        applyJson(ctx, json);
        prefs(ctx).edit().putString("activeProfile", name).apply();
        activeProfileName = name;
        return true;
    }

    public void deleteProfile(Context ctx, String name) {
        List<String> names = profileNames(ctx);
        names.remove(name);
        prefs(ctx).edit()
                .remove(PROFILE_PREFIX + name)
                .putString(PROFILE_LIST, String.join(",", names))
                .apply();
    }

    /** 导出当前配置（给「配置导入导出」用） */
    public static String exportCurrent(Context ctx) {
        return load(ctx).toJson();
    }

    /** 导入配置（覆盖当前） */
    public static void importInto(Context ctx, String json) {
        load(ctx).applyJson(ctx, json);
    }

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public String toString() {
        return "widgets=" + widgets + ", searchwidget=" + searchWidget
                + ", spread=" + spread + ", repeat=" + repeat;
    }
}
