package com.example.layoutprovider;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 布局规划器（纯逻辑，不碰 ContentProvider 契约）。
 *
 * <p>流水线：<b>① 枚举应用 → ② 分类 → ③ 成组 → ④ 占格排布 → ⑤ 渲染 XML</b>。
 * 其中"用户手动分组"优先于自动分类；存在合并基线时走**增量合并**分支
 * （见 {@code notes/10}、{@code notes/14}）。
 *
 * <p>本类是 P10 重构从 {@code LayoutProvider} 里抽出来的，逻辑与抽取前**逐字节一致**
 * （用 {@code tools/capture-golden.sh} 抓取的基线 XML 比对验证）。
 */
public final class LayoutPlanner {

    static final String TAG = "LayoutProviderPoC";

    /** 屏幕 0 的第 0 行被 launcher 自己的 smartspace 小组件占用 → 从 y=1 起排（notes/07 §5.3） */
    static final int TOP_RESERVED = 1;
    /** 最多铺几屏 */
    static final int MAX_SCREENS = 3;

    /** dock 优先候选：按序取，命中即用 */
    static final String[] HOTSEAT_PRIORITY = {
            "com.google.android.dialer", "com.android.dialer", "com.android.contacts",
            "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
            "com.android.chrome", "org.mozilla.firefox", "com.android.browser",
            "com.android.camera2", "com.google.android.GoogleCamera", "com.android.camera",
    };

    /** 候选 appwidget：{包名, 类名, spanX, spanY}，取第一个已安装的 */
    static final String[][] WIDGET_CANDIDATES = {
            {"com.google.android.deskclock", "com.android.alarmclock.DigitalAppWidgetProvider", "2", "2"},
            {"com.android.deskclock", "com.android.alarmclock.DigitalAppWidgetProvider", "2", "2"},
            {"com.google.android.calendar", "com.android.calendar.widget.CalendarAppWidgetProvider", "2", "3"},
            {"com.android.calendar", "com.android.calendar.widget.CalendarAppWidgetProvider", "2", "3"},
    };

    private LayoutPlanner() { }

    // ------------------------------------------------------------------ 数据模型

    public static final class Entry {
        final String pkg, cls, label;
        final int category;

        Entry(String pkg, String cls, String label, int category) {
            this.pkg = pkg;
            this.cls = cls;
            this.label = label;
            this.category = category;
        }

        public String pkg() { return pkg; }
        public String cls() { return cls; }
        public String label() { return label; }
    }

    /** 最终落到网格上的一个槽位 */
    public static final class Placement {
        public final String kind;            // folder | icon | appwidget | searchwidget
        public final int screen, x, y, spanX, spanY;
        public final String folderName;      // 仅 folder
        public final List<Entry> apps;       // folder 的子项 / icon 的那一个
        public final String pkg, cls;        // 仅 appwidget

        Placement(String kind, int screen, int x, int y, int spanX, int spanY,
                  String folderName, List<Entry> apps, String pkg, String cls) {
            this.kind = kind;
            this.screen = screen;
            this.x = x;
            this.y = y;
            this.spanX = spanX;
            this.spanY = spanY;
            this.folderName = folderName;
            this.apps = apps;
            this.pkg = pkg;
            this.cls = cls;
        }
    }

    public static final class Plan {
        public final List<Placement> placements = new ArrayList<>();
        public final List<Entry> hotseat = new ArrayList<>();
        public int totalApps;
        public int dropped;
        public String widgetNote = "";
        // 合并（增量）模式统计
        public boolean mergeMode;
        public String mergeSource = "";
        public int keptFolders;
        public int added;
        public int removed;
        public int excluded;
        public int customFolders;
        public int customSkipped;
        public final Map<String, Integer> bucketSizes = new LinkedHashMap<>();

        public String summary() {
            int folders = 0, icons = 0, widgets = 0;
            for (Placement p : placements) {
                if ("folder".equals(p.kind)) folders++;
                else if ("icon".equals(p.kind)) icons++;
                else widgets++;
            }
            int screens = 0;
            for (Placement p : placements) screens = Math.max(screens, p.screen + 1);
            return "规划结果: 共 " + totalApps + " 个应用"
                    + (mergeMode
                        ? "; 【合并模式 · 基线 " + mergeSource + "】保留文件夹 " + keptFolders
                          + " 个, 新增 " + added + " 个应用, 清理失效 " + removed + " 项"
                        : "; 分类桶=" + bucketSizes)
                    + "; 桌面 文件夹" + folders + " + 单图标" + icons + " + 部件" + widgets
                    + " = " + placements.size() + " 个槽位, 占 " + screens + " 屏"
                    + (dropped > 0 ? ", 丢弃 " + dropped : "")
                    + (customFolders > 0 ? ", 手动分组 " + customFolders + " 个" : "")
                    + (customSkipped > 0 ? ", 手动分组不足 2 项跳过 " + customSkipped : "")
                    + (excluded > 0 ? ", 用户排除 " + excluded : "")
                    + "; dock=" + hotseat.size() + "; " + widgetNote;
        }
    }

    /** 占用网格：支持跨格（spanX/spanY），可动态开新屏 */
    static final class Grid {
        final int gw, gh;
        final List<boolean[][]> screens = new ArrayList<>();

        Grid(int gw, int gh) {
            this.gw = gw;
            this.gh = gh;
            screens.add(new boolean[gw][gh]);
        }

        private boolean fits(int s, int x, int y, int sx, int sy) {
            if (x < 0 || y < 0 || x + sx > gw || y + sy > gh) return false;
            boolean[][] g = screens.get(s);
            for (int i = 0; i < sx; i++) {
                for (int j = 0; j < sy; j++) {
                    if (g[x + i][y + j]) return false;
                }
            }
            return true;
        }

        private void mark(int s, int x, int y, int sx, int sy) {
            boolean[][] g = screens.get(s);
            for (int i = 0; i < sx; i++) {
                for (int j = 0; j < sy; j++) {
                    g[x + i][y + j] = true;
                }
            }
        }

        /** 行优先找第一个放得下的位置并占用；放不下返回 null */
        int[] place(int sx, int sy) {
            for (int s = 0; s < MAX_SCREENS; s++) {
                while (screens.size() <= s) screens.add(new boolean[gw][gh]);
                int yStart = (s == 0) ? TOP_RESERVED : 0;
                for (int y = yStart; y + sy <= gh; y++) {
                    for (int x = 0; x + sx <= gw; x++) {
                        if (fits(s, x, y, sx, sy)) {
                            mark(s, x, y, sx, sy);
                            return new int[]{s, x, y};
                        }
                    }
                }
            }
            return null;
        }

        /** 在指定屏的指定行整行占用（用于把搜索框钉在底部） */
        int[] placeAtRow(int s, int row, int sx, int sy) {
            while (screens.size() <= s) screens.add(new boolean[gw][gh]);
            if (fits(s, 0, row, sx, sy)) {
                mark(s, 0, row, sx, sy);
                return new int[]{s, 0, row};
            }
            return null;
        }
    }

    /**
     * 当前（已有的）布局 —— 由 launcher 的 {@code EXPORT_LAYOUT_XML} 导出后放到
     * {@code <filesDir>/current-layout.xml}。存在时启用 <b>合并（增量）模式</b>。
     *
     * <p>为什么需要外部喂入：launcher 的 provider 用的是 {@code signatureOrSystem} 权限，
     * 普通第三方 app **读不到**当前布局（实测见 notes/10）。
     */
    public static final class CurrentLayout {
        public final List<FolderSpec> folders = new ArrayList<>();
        public final List<String[]> dock = new ArrayList<>();      // {pkg, cls}，按 rank 顺序
        public final List<String[]> icons = new ArrayList<>();     // {pkg, cls}
        public final Set<String> allPkgs = new HashSet<>();
        /** 这份基线从哪来（日志用） */
        public String source = "?";

        public static final class FolderSpec {
            public final String title;
            public final List<String[]> members = new ArrayList<>();
            FolderSpec(String title) { this.title = title == null ? "" : title; }
        }

        void index() {
            for (FolderSpec f : folders) for (String[] m : f.members) allPkgs.add(m[0]);
            for (String[] d : dock) allPkgs.add(d[0]);
            for (String[] i : icons) allPkgs.add(i[0]);
        }

        /** 从 XML 文本解析（兼容导出器产出的 autoinstall / folder / appicon / hotseat） */
        public static CurrentLayout parse(String xml) {
            CurrentLayout out = new CurrentLayout();
            try {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new StringReader(xml));
                FolderSpec cur = null;
                int type;
                while ((type = p.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type == XmlPullParser.END_TAG) {
                        if ("folder".equals(p.getName())) cur = null;
                        continue;
                    }
                    if (type != XmlPullParser.START_TAG) continue;

                    String name = p.getName();
                    if ("folder".equals(name)) {
                        cur = new FolderSpec(attr(p, "titleText"));
                        out.folders.add(cur);
                    } else if ("appicon".equals(name) || "autoinstall".equals(name)) {
                        String pkg = attr(p, "packageName");
                        String cls = attr(p, "className");
                        if (pkg == null || cls == null) continue;
                        String[] e = {pkg, cls};
                        if (cur != null) {
                            cur.members.add(e);
                        } else if ("hotseat".equals(attr(p, "container"))) {
                            out.dock.add(e);
                        } else {
                            out.icons.add(e);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "解析 current-layout.xml 失败: " + e);
                return null;
            }
            out.index();
            return out;
        }

        private static String attr(XmlPullParser p, String name) {
            String v = p.getAttributeValue(null, name);
            if (v == null) {
                v = p.getAttributeValue(
                        "http://schemas.android.com/apk/res-auto/com.android.launcher3", name);
            }
            return v;
        }

        /**
         * 取合并基线，优先级：
         * 1. {@code current-layout.xml} —— launcher 真实布局（需 root 才能导出，见 notes/10）
         * 2. {@code last-generated.xml} —— **自我记录式基线**（免 root）：本 provider 上次生成的布局
         */
        public static CurrentLayout load(Context ctx) {
            CurrentLayout c = loadFile(new File(ctx.getFilesDir(), "current-layout.xml"),
                    "current-layout.xml(真实布局)");
            if (c == null) {
                c = loadFile(new File(ctx.getFilesDir(), "last-generated.xml"),
                        "last-generated.xml(自我记录)");
            }
            return c;
        }

        private static CurrentLayout loadFile(File f, String label) {
            if (!f.exists() || f.length() == 0) return null;
            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                CurrentLayout c = parse(sb.toString());
                if (c != null) c.source = label;
                return c;
            } catch (IOException e) {
                Log.w(TAG, "读取 " + label + " 失败: " + e);
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ ① 枚举

    public static List<Entry> loadApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        String self = ctx.getPackageName();

        // 排除"启动器自身"：按 **组件** 而非包名——AOSP 的 Settings 包里含 FallbackHome（声明了 HOME），
        // 按包名排除会把整个 Settings 误杀。
        Set<String> homeComponents = new HashSet<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai != null && ai.packageName != null && ai.name != null) {
                homeComponents.add(ai.packageName + "/" + ai.name);
            }
        }

        // 按包分组：同一应用可能有多个启动入口（如 Google 的 SearchActivity + VoiceSearchActivity）
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, List<Entry>> byPkg = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName == null || ai.name == null) continue;
            if (self.equals(ai.packageName)
                    || homeComponents.contains(ai.packageName + "/" + ai.name)) continue;

            String label;
            try {
                label = ri.loadLabel(pm).toString();
            } catch (Exception ex) {
                label = ai.name;
            }
            int cat = ai.applicationInfo != null
                    ? ai.applicationInfo.category : ApplicationInfo.CATEGORY_UNDEFINED;

            List<Entry> list = byPkg.get(ai.packageName);
            if (list == null) {
                list = new ArrayList<>();
                byPkg.put(ai.packageName, list);
            }
            list.add(new Entry(ai.packageName, ai.name, label, cat));
        }

        // 每包只留一个入口：优先用系统认定的"默认启动 Activity"
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> e : byPkg.entrySet()) {
            List<Entry> cands = e.getValue();
            Entry chosen = cands.get(0);
            Intent launch = pm.getLaunchIntentForPackage(e.getKey());
            if (launch != null && launch.getComponent() != null) {
                String cls = launch.getComponent().getClassName();
                for (Entry c : cands) {
                    if (c.cls.equals(cls)) {
                        chosen = c;
                        break;
                    }
                }
            }
            out.add(chosen);
        }

        out.sort(Comparator.comparing(e -> e.label));
        return out;
    }

    // ------------------------------------------------------------------ ② 分类

    /** 用户覆盖优先（UI 里可以改分类 / 排除） */
    static String bucketOf(AppConfig cfg, Entry e) {
        String ov = cfg.overrideBucket(e.pkg);
        if (ov != null) return ov;
        return bucketOfAuto(e);
    }

    static String bucketOfAuto(Entry e) {
        switch (e.category) {
            case ApplicationInfo.CATEGORY_GAME:          return "游戏";
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO:
            case ApplicationInfo.CATEGORY_IMAGE:         return "影音";
            case ApplicationInfo.CATEGORY_SOCIAL:        return "社交";
            case ApplicationInfo.CATEGORY_NEWS:          return "资讯";
            case ApplicationInfo.CATEGORY_MAPS:          return "出行";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY:  return "效率";
            case ApplicationInfo.CATEGORY_ACCESSIBILITY: return "工具";
            default: break;
        }

        String s = (e.pkg + " " + e.label).toLowerCase();
        if (s.contains("dialer") || s.contains("phone") || s.contains("contacts")) return "社交";
        if (s.contains("message") || s.contains("mms") || s.contains("mail"))       return "社交";
        if (s.contains("maps") || s.contains("navig") || s.contains("ride"))        return "出行";
        if (s.contains("youtube") || s.contains("music") || s.contains("photo")
                || s.contains("video") || s.contains("gallery") || s.contains("movie")) return "影音";
        if (s.contains("game"))                                                     return "游戏";
        if (s.contains("chrome") || s.contains("browser") || s.contains("docs"))     return "效率";
        if (s.contains("camera") || s.contains("clock") || s.contains("calendar")
                || s.contains("settings") || s.contains("calculat") || s.contains("file")) return "工具";
        return "其他";
    }

    // ------------------------------------------------------------------ ③④⑤ 规划

    public static Plan buildPlan(Context ctx, AppConfig cfg, int gw, int gh, int hs) {
        return buildPlan(ctx, cfg, gw, gh, hs, false);
    }

    /**
     * @param forceFull 忽略合并基线，强制走全量重建。用于 UI 的「预览全量重建」与
     *                  回归测试（免 root 也能稳定拿到全量模式的输出）
     */
    public static Plan buildPlan(Context ctx, AppConfig cfg, int gw, int gh, int hs, boolean forceFull) {
        if (gw < 2) gw = 2;
        if (gh < 3) gh = 3;

        // 只在真的用到时才去查使用频率（查询可能 200–800ms）；不可用就当作没有 → 自动降级
        boolean needUsage = "usage".equals(cfg.dockMode)
                || "usage".equals(cfg.folderSort)
                || "usage".equals(cfg.screenStrategy);
        UsageRank usage = needUsage ? UsageRank.load(ctx) : null;
        if (usage != null && !usage.available()) usage = null;

        List<Entry> allApps = loadApps(ctx);
        Plan plan = new Plan();
        plan.totalApps = allApps.size();

        // 先按用户覆盖过滤（排除的不参与排布）
        List<Entry> apps = new ArrayList<>();
        for (Entry e : allApps) {
            if (cfg.isExcluded(e.pkg)) {
                plan.excluded++;
                continue;
            }
            apps.add(e);
        }

        Grid grid = new Grid(gw, gh);

        // ④-a 小组件（可选）：先把它们占位，图标再绕开
        if (cfg.widgets) {
            String[] w = firstInstalledWidget(ctx);
            if (w != null) {
                int[] pos = grid.place(Integer.parseInt(w[2]), Integer.parseInt(w[3]));
                if (pos != null) {
                    plan.placements.add(new Placement("appwidget", pos[0], pos[1], pos[2],
                            Integer.parseInt(w[2]), Integer.parseInt(w[3]), null, null, w[0], w[1]));
                    plan.widgetNote = "widget=" + w[0] + "/" + w[1]
                            + "@" + pos[0] + ":(" + pos[1] + "," + pos[2] + ")";
                }
            } else {
                plan.widgetNote = "widget=未找到可用 provider";
            }
        }
        if (cfg.searchWidget) {
            // ⚠️ 实测：Lawnchair 的 SearchWidgetParser 必然 NPE，会让**整份布局**解析失败，
            //    所以默认关闭；仅用于复现该 bug。
            int[] sw = grid.placeAtRow(0, gh - 1, gw, 1);
            if (sw != null) {
                plan.placements.add(new Placement("searchwidget", sw[0], sw[1], sw[2], gw, 1,
                        null, null, null, null));
                plan.widgetNote += "; searchwidget@(0," + (gh - 1) + ") span " + gw + "x1";
            }
        }

        // ⑤ dock + ② 分类 + ③ 成组
        Map<String, Entry> installed = new HashMap<>();
        for (Entry e : apps) installed.put(e.pkg, e);

        CurrentLayout cur = forceFull ? null : CurrentLayout.load(ctx);
        Set<String> dockPkgs = new HashSet<>();
        List<Placement> slots = new ArrayList<>();

        // ---- 用户手动分组：优先于自动分类，两种模式都生效 ----
        Set<String> customPkgs = new HashSet<>();
        for (AppConfig.CustomFolder cf : cfg.customFolders()) {
            List<Entry> members = new ArrayList<>();
            for (String pkg : cf.pkgs) {
                Entry e = installed.get(pkg);
                if (e != null && !customPkgs.contains(pkg)) {
                    members.add(e);
                    customPkgs.add(pkg);
                }
            }
            if (members.size() >= 2) {
                sortMembersIfUsage(cfg, usage, members);
                slots.add(new Placement("folder", 0, 0, 0, 1, 1, cf.name, members, null, null));
                plan.customFolders++;
            } else {
                plan.customSkipped++;   // Launcher3 不允许只有 1 项的文件夹
            }
        }

        if (cur != null && !cfg.spread) {
            // ===================== 合并（增量）模式 =====================
            // 保留用户已有的 dock 顺序 / 文件夹分组；只补新应用、清失效项、重排位置。
            plan.mergeMode = true;
            plan.mergeSource = cur.source;

            // ⚠️ 只有 auto 模式才保留用户已有的 dock。
            // usage / manual 是用户的**显式选择**，若还被旧值覆盖，用户会以为功能没生效
            // （实测截图里就是这个矛盾：设了手动 Dock，预览却仍是旧的 4 个）。
            if ("auto".equals(cfg.dockMode)) {
                for (String[] d : cur.dock) {
                    Entry e = installed.get(d[0]);
                    if (e != null && dockPkgs.add(d[0])) plan.hotseat.add(e);
                }
            }
            for (String p : dockCandidates(cfg, usage, apps)) {     // 空位补齐（usage/manual 模式下是主来源）
                if (plan.hotseat.size() >= hs) break;
                if (dockPkgs.contains(p)) continue;
                Entry e = installed.get(p);
                if (e != null && dockPkgs.add(p)) plan.hotseat.add(e);
            }
            while (plan.hotseat.size() > hs) plan.hotseat.remove(plan.hotseat.size() - 1);

            Set<String> placed = new HashSet<>(dockPkgs);
            placed.addAll(customPkgs);          // 手动分组的成员不再参与自动归类
            List<Entry> singles = new ArrayList<>();
            Map<String, List<Entry>> folderMembers = new LinkedHashMap<>();

            for (CurrentLayout.FolderSpec f : cur.folders) {
                List<Entry> members = new ArrayList<>();
                for (String[] m : f.members) {
                    Entry e = installed.get(m[0]);
                    if (e != null && !placed.contains(m[0])) {
                        members.add(e);
                        placed.add(m[0]);
                    } else {
                        plan.removed++;
                    }
                }
                if (members.size() >= 2) {
                    sortMembersIfUsage(cfg, usage, members);
                    slots.add(new Placement("folder", 0, 0, 0, 1, 1, f.title, members, null, null));
                    folderMembers.put(f.title, members);
                    plan.keptFolders++;
                } else {
                    singles.addAll(members);       // 只剩 1 个 → 降级为单图标
                }
            }
            for (String[] i : cur.icons) {
                Entry e = installed.get(i[0]);
                if (e != null && !placed.contains(i[0])) {
                    singles.add(e);
                    placed.add(i[0]);
                }
            }

            // 新安装的应用：优先并入同名分类文件夹，否则按分类新建
            Map<String, List<Entry>> newBuckets = new LinkedHashMap<>();
            for (Entry e : apps) {
                if (placed.contains(e.pkg) || dockPkgs.contains(e.pkg)) continue;
                plan.added++;
                String b = bucketOf(cfg, e);
                List<Entry> exist = folderMembers.get(b);
                if (exist != null) exist.add(e);
                else newBuckets.computeIfAbsent(b, k -> new ArrayList<>()).add(e);
            }
            List<Map.Entry<String, List<Entry>>> groups = new ArrayList<>(newBuckets.entrySet());
            groups.sort((a, b) -> b.getValue().size() - a.getValue().size());
            for (Map.Entry<String, List<Entry>> g : groups) {
                plan.bucketSizes.put(g.getKey(), g.getValue().size());
                if (g.getValue().size() >= 2) {
                    slots.add(new Placement("folder", 0, 0, 0, 1, 1,
                            cfg.displayBucket(g.getKey()), g.getValue(), null, null));
                } else {
                    singles.addAll(g.getValue());
                }
            }
            for (Entry e : singles) {
                slots.add(new Placement("icon", 0, 0, 0, 1, 1, null,
                        Collections.singletonList(e), null, null));
            }

        } else {
            // ===================== 全量模式 =====================
            for (String p : dockCandidates(cfg, usage, apps)) {
                if (plan.hotseat.size() >= hs) break;
                if (dockPkgs.contains(p)) continue;
                for (Entry e : apps) {
                    if (e.pkg.equals(p)) {
                        plan.hotseat.add(e);
                        dockPkgs.add(p);
                        break;
                    }
                }
            }

            Map<String, List<Entry>> buckets = new LinkedHashMap<>();
            for (Entry e : apps) {
                if (dockPkgs.contains(e.pkg) || customPkgs.contains(e.pkg)) continue;
                String b = bucketOf(cfg, e);
                List<Entry> list = buckets.get(b);
                if (list == null) {
                    list = new ArrayList<>();
                    buckets.put(b, list);
                }
                list.add(e);
            }
            for (Map.Entry<String, List<Entry>> e : buckets.entrySet()) {
                plan.bucketSizes.put(e.getKey(), e.getValue().size());
                e.getValue().sort(Comparator.comparing(x -> x.label));
            }

            if (cfg.spread) {
                List<Entry> all = new ArrayList<>();
                for (List<Entry> g : buckets.values()) all.addAll(g);
                for (Entry e : all) slots.add(new Placement("icon", 0, 0, 0, 1, 1, null,
                        Collections.singletonList(e), null, null));
            } else {
                List<Map.Entry<String, List<Entry>>> groups = new ArrayList<>(buckets.entrySet());
                groups.sort((a, b) -> b.getValue().size() - a.getValue().size());
                List<Entry> singles = new ArrayList<>();
                for (Map.Entry<String, List<Entry>> g : groups) {
                    if (g.getValue().size() >= 2) {
                        sortMembersIfUsage(cfg, usage, g.getValue());
                        slots.add(new Placement("folder", 0, 0, 0, 1, 1,
                                cfg.displayBucket(g.getKey()), g.getValue(), null, null));
                    } else {
                        singles.addAll(g.getValue());
                    }
                }
                for (Entry e : singles) {
                    slots.add(new Placement("icon", 0, 0, 0, 1, 1, null,
                            Collections.singletonList(e), null, null));
                }
            }
        }

        // 首屏策略：常用优先 → 按"组内最高频"降序重排槽位
        final UsageRank use = usage;
        if ("usage".equals(cfg.screenStrategy) && use != null) {
            slots.sort((a, b) -> Integer.compare(peak(b, use), peak(a, use)));
        }

        // repeat：把槽位复制 N 份（调试用，用来触发多页）
        List<Placement> expanded = new ArrayList<>();
        for (int i = 0; i < cfg.repeat; i++) expanded.addAll(slots);

        // ④-b 真正落格（占用网格会自动跳过已占的格子，放不下就开新屏）
        for (Placement p : expanded) {
            int[] pos = grid.place(p.spanX, p.spanY);
            if (pos == null) {
                plan.dropped++;
                continue;
            }
            plan.placements.add(new Placement(p.kind, pos[0], pos[1], pos[2],
                    p.spanX, p.spanY, p.folderName, p.apps, p.pkg, p.cls));
        }

        return plan;
    }

    // ------------------------------------------------------------------ 使用频率相关的排布策略

    /** Dock 候选顺序：auto → 硬编码优先级；usage → 按频率；manual → 用户指定（不足则补齐） */
    private static List<String> dockCandidates(AppConfig cfg, UsageRank usage, List<Entry> apps) {
        if ("manual".equals(cfg.dockMode)) {
            List<String> out = new ArrayList<>();
            for (String p : cfg.manualDock) {
                for (Entry e : apps) {
                    if (e.pkg.equals(p)) { out.add(p); break; }
                }
            }
            for (String p : HOTSEAT_PRIORITY) {     // 用户没选够就用默认优先级补
                if (!out.contains(p)) out.add(p);
            }
            return out;
        }
        if (!"usage".equals(cfg.dockMode) || usage == null) {
            return Arrays.asList(HOTSEAT_PRIORITY);
        }
        List<Entry> ranked = new ArrayList<>(apps);
        ranked.sort((a, b) -> {
            int c = Integer.compare(usage.score(b.pkg), usage.score(a.pkg));
            return c != 0 ? c : a.label.compareTo(b.label);
        });
        List<String> out = new ArrayList<>();
        for (Entry e : ranked) {
            if (usage.score(e.pkg) > 0) out.add(e.pkg);
        }
        for (String p : HOTSEAT_PRIORITY) {     // 空位用原优先级补齐
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    /** 文件夹内排序：**仅当 folderSort=usage 时才动**（默认保持原顺序 → 行为不变） */
    private static void sortMembersIfUsage(AppConfig cfg, UsageRank usage, List<Entry> members) {
        if (!"usage".equals(cfg.folderSort) || usage == null || members == null) return;
        members.sort((a, b) -> {
            int c = Integer.compare(usage.score(b.pkg), usage.score(a.pkg));
            return c != 0 ? c : a.label.compareTo(b.label);
        });
    }

    /** 组内最高频（用于"常用优先"的首屏排序） */
    private static int peak(Placement p, UsageRank usage) {
        int best = 0;
        if (p.apps != null) {
            for (Entry e : p.apps) best = Math.max(best, usage.score(e.pkg));
        }
        return best;
    }

    static String[] firstInstalledWidget(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String[] w : WIDGET_CANDIDATES) {
            try {
                pm.getReceiverInfo(new ComponentName(w[0], w[1]), 0);
                return w;
            } catch (Exception ignored) {
                // 未安装，试下一个
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 渲染 XML

    public static String render(Plan plan, int gw, int gh) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<workspace>\n");

        for (Placement p : plan.placements) {
            switch (p.kind) {
                case "folder":
                    sb.append("  <folder container=\"desktop\" screen=\"").append(p.screen)
                      .append("\" x=\"").append(p.x).append("\" y=\"").append(p.y)
                      .append("\" titleText=\"").append(esc(p.folderName)).append("\">\n");
                    for (Entry e : p.apps) {
                        sb.append("    <appicon packageName=\"").append(esc(e.pkg))
                          .append("\" className=\"").append(esc(e.cls)).append("\" />\n");
                    }
                    sb.append("  </folder>\n");
                    break;

                case "appwidget":
                    sb.append("  <appwidget container=\"desktop\" screen=\"").append(p.screen)
                      .append("\" x=\"").append(p.x).append("\" y=\"").append(p.y)
                      .append("\" packageName=\"").append(esc(p.pkg))
                      .append("\" className=\"").append(esc(p.cls))
                      .append("\" spanX=\"").append(p.spanX)
                      .append("\" spanY=\"").append(p.spanY).append("\" />\n");
                    break;

                case "searchwidget":
                    sb.append("  <searchwidget container=\"desktop\" screen=\"").append(p.screen)
                      .append("\" x=\"").append(p.x).append("\" y=\"").append(p.y)
                      .append("\" spanX=\"").append(p.spanX)
                      .append("\" spanY=\"").append(p.spanY).append("\" />\n");
                    break;

                default: {   // icon
                    Entry e = p.apps.get(0);
                    sb.append("  <appicon container=\"desktop\" screen=\"").append(p.screen)
                      .append("\" x=\"").append(p.x).append("\" y=\"").append(p.y)
                      .append("\" packageName=\"").append(esc(e.pkg))
                      .append("\" className=\"").append(esc(e.cls)).append("\" />\n");
                }
            }
        }

        for (int i = 0; i < plan.hotseat.size(); i++) {
            Entry e = plan.hotseat.get(i);
            sb.append("  <appicon container=\"hotseat\" rank=\"").append(i)
              .append("\" packageName=\"").append(esc(e.pkg))
              .append("\" className=\"").append(esc(e.cls)).append("\" />\n");
        }

        sb.append("</workspace>\n");
        return sb.toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
