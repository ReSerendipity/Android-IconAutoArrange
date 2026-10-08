package com.example.layoutprovider;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「待归位」检测：对比**当前应用集合**与**上次归位时的集合**。
 *
 * <p>为什么不用 {@code PACKAGE_ADDED} 广播：按既定原则我们**不做静默重排、也不发通知**
 * （见 notes/15 §8）—— 用户不打开 app 时，接收器什么也做不了。既然提示只出现在 app 内，
 * **按需比对**就足够了，而且少一个"广播没收到就静默失效"的故障面。
 *
 * <p>「归位」的定义：每次成功生成布局后（{@code openFile} 返回前），
 * 把当时的应用集合记为"已知" —— 因为那份布局已经包含全部应用，按定义就是已归位。
 */
public final class PendingApps {

    private static final String TAG = LayoutPlanner.TAG;
    private static final String KEY_KNOWN = "knownApps";

    private PendingApps() { }

    public static final class Result {
        public final List<String> added = new ArrayList<>();     // 新装、还没进过布局
        public final List<String> removed = new ArrayList<>();   // 已卸载、但上次布局里还有

        public boolean any() { return !added.isEmpty() || !removed.isEmpty(); }
        public int count() { return added.size() + removed.size(); }
    }

    /** 记录"已归位"的应用集合（每次成功生成布局后调用） */
    public static void markKnown(Context ctx, List<String> pkgs) {
        try {
            ctx.getSharedPreferences("poc", Context.MODE_PRIVATE).edit()
                    .putString(KEY_KNOWN, String.join(",", pkgs)).apply();
        } catch (Throwable t) {
            Log.w(TAG, "[pending] markKnown 失败: " + t);
        }
    }

    /** 对比当前应用集合与上次归位时的集合 */
    public static Result pending(Context ctx, List<LayoutPlanner.Entry> current) {
        Result r = new Result();
        String stored = ctx.getSharedPreferences("poc", Context.MODE_PRIVATE)
                .getString(KEY_KNOWN, null);

        // 首次运行：没有基线可比，直接记为已知，避免"全部应用都是新装的"这种误报
        if (stored == null) {
            List<String> all = new ArrayList<>();
            for (LayoutPlanner.Entry e : current) all.add(e.pkg());
            markKnown(ctx, all);
            return r;
        }

        Set<String> known = new HashSet<>();
        for (String s : stored.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) known.add(s);
        }
        Set<String> now = new HashSet<>();
        for (LayoutPlanner.Entry e : current) {
            now.add(e.pkg());
            if (!known.contains(e.pkg())) r.added.add(e.label());
        }
        for (String k : known) {
            if (!now.contains(k)) r.removed.add(k);   // 已卸载（取不到标签，直接显示包名）
        }
        return r;
    }
}
