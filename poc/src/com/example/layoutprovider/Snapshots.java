package com.example.layoutprovider;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 布局快照：每次生成后留一份，支持回滚。
 *
 * <p>⚠️ <b>快照只包含"本应用生成过的布局"</b>，不含用户手工拖动的结果 ——
 * 因为免 root 读不到 launcher 的真实布局（见 notes/10）。所以"回滚"的语义是
 * 「回到我们上次生成的某一版」，不是「回到桌面当时的样子」。UI 必须写明这点。
 *
 * <p>存放：{@code <filesDir>/snapshots/<时间戳>.xml}，最多保留 {@link #KEEP} 份。
 */
public final class Snapshots {

    private static final String TAG = LayoutPlanner.TAG;
    private static final String DIR = "snapshots";
    private static final int KEEP = 5;

    private Snapshots() { }

    public static final class Item {
        public final String id;
        public final long time;
        public final int folders;
        public final int apps;
        public final boolean current;

        Item(String id, long time, int folders, int apps, boolean current) {
            this.id = id;
            this.time = time;
            this.folders = folders;
            this.apps = apps;
            this.current = current;
        }
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 每次成功生成布局后调用。与最新一份内容相同则跳过，避免连点两次产生重复快照。 */
    public static void capture(Context ctx, String xml) {
        try {
            List<Item> existing = list(ctx);
            if (!existing.isEmpty()) {
                File last = new File(dir(ctx), existing.get(0).id + ".xml");
                if (last.exists() && read(last).equals(xml)) return;
            }
            long ts = System.currentTimeMillis();
            File f = new File(dir(ctx), ts + ".xml");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write(xml);
            }
            prune(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "[snapshot] 保存失败: " + t);
        }
    }

    /** 按时间倒序列出（最新的在前）；标记哪一份是当前基线 */
    public static List<Item> list(Context ctx) {
        List<Item> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles();
        if (files == null) return out;

        String currentXml = null;
        File cur = new File(ctx.getFilesDir(), "last-generated.xml");
        if (cur.exists()) currentXml = read(cur);

        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".xml")) continue;
            String id = n.substring(0, n.length() - 4);
            long ts;
            try {
                ts = Long.parseLong(id);
            } catch (NumberFormatException e) {
                continue;
            }
            String xml = read(f);
            LayoutPlanner.CurrentLayout cl = LayoutPlanner.CurrentLayout.parse(xml);
            int folders = cl == null ? 0 : cl.folders.size();
            int apps = cl == null ? 0 : cl.allPkgs.size();
            boolean isCurrent = currentXml != null && currentXml.equals(xml);
            out.add(new Item(id, ts, folders, apps, isCurrent));
        }
        return out;
    }

    /** 把某份快照写回 {@code last-generated.xml}，下次生成就会以它为合并基线 */
    public static boolean restore(Context ctx, String id) {
        try {
            File src = new File(dir(ctx), id + ".xml");
            if (!src.exists()) return false;
            String xml = read(src);
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(new File(ctx.getFilesDir(), "last-generated.xml")),
                    StandardCharsets.UTF_8)) {
                w.write(xml);
            }
            Log.i(TAG, "[snapshot] 已回滚到 " + id);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[snapshot] 回滚失败: " + t);
            return false;
        }
    }

    private static void prune(Context ctx) {
        File[] files = dir(ctx).listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        for (int i = KEEP; i < files.length; i++) {
            if (files[i].getName().endsWith(".xml") && !files[i].delete()) {
                Log.w(TAG, "[snapshot] 删除旧快照失败: " + files[i].getName());
            }
        }
    }

    private static String read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            return new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
