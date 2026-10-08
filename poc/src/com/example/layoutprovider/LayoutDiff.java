package com.example.layoutprovider;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 变更预览：把「本次规划」与「上次生成的布局」对比，产出四类差异。
 *
 * <p><b>只比容器级变化，不比坐标</b>：一旦新增一个应用，后面所有图标的 (x,y) 都会平移，
 * 按坐标比对会得出"几乎所有东西都移动了"这种无用结论。用户真正关心的是
 * 「这个应用换文件夹了 / 被排到桌面了 / 进 Dock 了」。
 *
 * <p>⚠️ 基线是 {@code last-generated.xml}（**本应用上次生成的布局**），
 * 不是 launcher 的真实当前布局 —— 免 root 读不到后者（见 notes/10）。
 * 所以 UI 上要写明这一点，否则用户会以为"它知道我手工拖过什么"。
 */
public final class LayoutDiff {

    public final List<String> added = new ArrayList<>();     // 新增的应用名
    public final List<String> removed = new ArrayList<>();   // 消失的应用名
    public final List<String> moved = new ArrayList<>();     // "名称：旧位置 → 新位置"
    public int kept;
    public boolean hasBaseline;

    private LayoutDiff() { }

    public int total() { return added.size() + removed.size() + moved.size(); }

    public static LayoutDiff compute(Context ctx, LayoutPlanner.Plan plan) {
        LayoutDiff d = new LayoutDiff();
        LayoutPlanner.CurrentLayout base = LayoutPlanner.CurrentLayout.load(ctx);
        if (base == null) return d;          // 没有基线（首次运行）
        d.hasBaseline = true;

        Map<String, String> before = new HashMap<>();
        for (LayoutPlanner.CurrentLayout.FolderSpec f : base.folders) {
            for (String[] m : f.members) before.put(m[0], "folder:" + f.title);
        }
        for (String[] x : base.dock) before.put(x[0], "dock");
        for (String[] x : base.icons) before.put(x[0], "desktop");

        Map<String, String> after = new HashMap<>();
        for (LayoutPlanner.Entry e : plan.hotseat) after.put(e.pkg(), "dock");
        for (LayoutPlanner.Placement p : plan.placements) {
            if ("folder".equals(p.kind) && p.apps != null) {
                for (LayoutPlanner.Entry e : p.apps) after.put(e.pkg(), "folder:" + p.folderName);
            } else if ("icon".equals(p.kind) && p.apps != null) {
                after.put(p.apps.get(0).pkg(), "desktop");
            }
        }

        Map<String, String> labels = new HashMap<>();
        for (LayoutPlanner.Entry e : LayoutPlanner.loadApps(ctx)) labels.put(e.pkg(), e.label());

        for (Map.Entry<String, String> e : after.entrySet()) {
            String pkg = e.getKey();
            if (!before.containsKey(pkg)) {
                d.added.add(labels.getOrDefault(pkg, pkg));
            } else if (before.get(pkg).equals(e.getValue())) {
                d.kept++;
            } else {
                d.moved.add(labels.getOrDefault(pkg, pkg) + "：" + human(before.get(pkg))
                        + " → " + human(e.getValue()));
            }
        }
        for (Map.Entry<String, String> e : before.entrySet()) {
            if (!after.containsKey(e.getKey())) {
                d.removed.add(labels.getOrDefault(e.getKey(), e.getKey()));
            }
        }
        return d;
    }

    private static String human(String where) {
        if (where == null) return "?";
        if ("dock".equals(where)) return "Dock";
        if ("desktop".equals(where)) return "桌面";
        if (where.startsWith("folder:")) return "「" + where.substring(7) + "」";
        return where;
    }
}
