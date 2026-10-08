package com.example.layoutprovider;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/**
 * 项目的 UI：一个 WebView 壳。
 *
 * <p>为什么用 WebView：整个 APK 是**不用 Gradle/AGP 手搓**的（aapt2 + d8 + apksigner），
 * 引入 AndroidX/Material 的成本很高；而 WebView + 本地 assets 的 HTML 既能做出像样的界面，
 * 又零依赖。桌面预览直接用 HTML/CSS 画网格，比写 Android 自定义 View 简单得多。
 *
 * <p>数据来源：**同一个 app 里的 {@link LayoutProvider}**，通过
 * {@code ContentResolver.call(...)} 走 {@code PLAN_JSON} 方法 —— 这样"预览"与"实际导入"
 * 用的是**同一份规划代码**，不会出现预览与实际不一致。
 */
public class MainActivity extends Activity {

    private static final String TAG = "LayoutProviderUI";
    private static final String AUTHORITY = "com.example.layoutprovider";
    private static final int REQ_SHIZUKU = 1001;

    private WebView web;
    private volatile String shizukuResult = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/ui.html");

        // Shizuku 的监听器必须在主线程注册
        try {
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                Log.i(TAG, "Shizuku 授权结果 code=" + requestCode + " result=" + grantResult);
                shizukuResult = grantResult == PackageManager.PERMISSION_GRANTED ? "已授权" : "已拒绝";
            });
            Shizuku.addBinderReceivedListenerSticky(() -> Log.i(TAG, "Shizuku binder 已连接"));
            Shizuku.addBinderDeadListener(() -> Log.w(TAG, "Shizuku binder 断开"));
        } catch (Throwable t) {
            Log.w(TAG, "注册 Shizuku 监听器失败: " + t);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** 暴露给页面 JS 的桥（window.Android.*） */
    public class Bridge {

        /** 取规划快照（JSON 字符串） */
        @JavascriptInterface
        public String getPlan() {
            try {
                Bundle b = getContentResolver().call(
                        Uri.parse("content://" + AUTHORITY), "PLAN_JSON", null, null);
                String json = b == null ? null : b.getString("JSON");
                return json != null ? json : "{\"error\":\"provider 未返回 JSON\"}";
            } catch (Throwable t) {
                Log.e(TAG, "getPlan failed", t);
                return "{\"error\":" + quote(String.valueOf(t)) + "}";
            }
        }

        /** 尝试应用（需要 root：借 su 写 Secure Settings） */
        @JavascriptInterface
        public String apply() {
            return callProvider("APPLY", null);
        }

        /** 一键：写设置 + 重置 launcher 数据 + 回桌面（需要 root） */
        @JavascriptInterface
        public String applyFull() {
            return callProvider("APPLY_FULL", null);
        }

        /** 改分类 / 排除某个应用 */
        @JavascriptInterface
        public String setOverride(String pkg, String bucket, boolean excluded) {
            Bundle ex = new Bundle();
            ex.putString("pkg", pkg);
            ex.putString("bucket", bucket == null ? "" : bucket);
            ex.putBoolean("excluded", excluded);
            return callProvider("SET_OVERRIDE", ex);
        }

        /** 清空所有用户覆盖（恢复自动分类） */
        @JavascriptInterface
        public String clearOverrides() {
            return callProvider("CLEAR_OVERRIDES", null);
        }

        /** 保存一个手动分组（勾选的一批应用 → 桌面上的一个文件夹） */
        @JavascriptInterface
        public String setCustomFolder(String id, String name, String pkgsCsv) {
            Bundle ex = new Bundle();
            ex.putString("id", id == null ? "" : id);
            ex.putString("name", name == null ? "" : name);
            ex.putString("pkgs", pkgsCsv == null ? "" : pkgsCsv);
            return callProvider("SET_CUSTOM_FOLDER", ex);
        }

        @JavascriptInterface
        public String deleteCustomFolder(String id) {
            Bundle ex = new Bundle();
            ex.putString("id", id == null ? "" : id);
            return callProvider("DELETE_CUSTOM_FOLDER", ex);
        }

        /** 排布策略开关：key ∈ {dockMode, folderSort, screenStrategy} */
        @JavascriptInterface
        public String setLayoutOption(String key, String value) {
            Bundle ex = new Bundle();
            ex.putString("key", key == null ? "" : key);
            ex.putString("value", value == null ? "" : value);
            return callProvider("SET_LAYOUT_OPTION", ex);
        }

        /** 设置 Dock 成员（有序，逗号分隔的包名） */
        @JavascriptInterface
        public String setManualDock(String pkgsCsv) {
            Bundle ex = new Bundle();
            ex.putString("pkgs", pkgsCsv == null ? "" : pkgsCsv);
            return callProvider("SET_MANUAL_DOCK", ex);
        }

        /** 列出布局快照（JSON 数组） */
        @JavascriptInterface
        public String listSnapshots() {
            try {
                Bundle b = getContentResolver().call(
                        Uri.parse("content://" + AUTHORITY), "LIST_SNAPSHOTS", null, null);
                String json = b == null ? null : b.getString("JSON");
                return json != null ? json : "[]";
            } catch (Throwable t) {
                return "[]";
            }
        }

        /** 回滚到某份快照（写回 last-generated.xml，下次应用即以它为基线） */
        @JavascriptInterface
        public String restoreSnapshot(String id) {
            Bundle ex = new Bundle();
            ex.putString("id", id == null ? "" : id);
            return callProvider("RESTORE_SNAPSHOT", ex);
        }

        /** 列出方案（JSON） */
        @JavascriptInterface
        public String listProfiles() {
            try {
                Bundle b = getContentResolver().call(
                        Uri.parse("content://" + AUTHORITY), "PROFILE_LIST", null, null);
                String json = b == null ? null : b.getString("JSON");
                return json != null ? json : "[]";
            } catch (Throwable t) {
                return "[]";
            }
        }

        /** 把当前配置另存为方案（同名则覆盖） */
        @JavascriptInterface
        public String saveProfile(String name) {
            Bundle ex = new Bundle();
            ex.putString("name", name == null ? "" : name);
            return callProvider("PROFILE_SAVE", ex);
        }

        /** 应用某个方案 */
        @JavascriptInterface
        public String loadProfile(String name) {
            Bundle ex = new Bundle();
            ex.putString("name", name == null ? "" : name);
            return callProvider("PROFILE_LOAD", ex);
        }

        @JavascriptInterface
        public String deleteProfile(String name) {
            Bundle ex = new Bundle();
            ex.putString("name", name == null ? "" : name);
            return callProvider("PROFILE_DELETE", ex);
        }

        /** 手动指定网格（解决"网格未知"） */
        @JavascriptInterface
        public String setGrid(int w, int h, int hs) {
            Bundle ex = new Bundle();
            ex.putInt("w", w);
            ex.putInt("h", h);
            ex.putInt("hs", hs);
            return callProvider("SET_GRID", ex);
        }

        /** 重命名分类（留空 = 恢复原名） */
        @JavascriptInterface
        public String setBucketName(String bucket, String name) {
            Bundle ex = new Bundle();
            ex.putString("bucket", bucket == null ? "" : bucket);
            ex.putString("name", name == null ? "" : name);
            return callProvider("SET_BUCKET_NAME", ex);
        }

        /**
         * 导出配置到剪贴板。
         *
         * <p>为什么用剪贴板而不是文件：分享文件需要 FileProvider（要有 `res/xml/file_paths.xml`），
         * 而本项目是**不用 Gradle 手搓构建**的、目前没有任何资源目录。配置 JSON 很小，
         * 剪贴板方案零依赖、零权限，粘贴到聊天/笔记里就能备份或分享。
         */
        @JavascriptInterface
        public String exportConfig() {
            try {
                Bundle b = getContentResolver().call(
                        Uri.parse("content://" + AUTHORITY), "EXPORT_CONFIG", null, null);
                String json = b == null ? null : b.getString("JSON");
                if (json == null || json.isEmpty()) return "导出失败：没有拿到配置";
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return "导出失败：没有剪贴板";
                cm.setPrimaryClip(ClipData.newPlainText("layout-config", json));
                Log.i(TAG, "导出配置 " + json.length() + " 字节");
                return "已复制到剪贴板（" + json.length() + " 字节），粘贴到任何地方保存即可";
            } catch (Throwable t) {
                return "导出失败: " + t;
            }
        }

        /** 从剪贴板导入配置 */
        @JavascriptInterface
        public String importConfig() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                        || cm.getPrimaryClip().getItemCount() == 0) {
                    return "剪贴板是空的 —— 请先复制一段配置 JSON";
                }
                CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                String json = cs == null ? "" : cs.toString().trim();
                if (!json.startsWith("{")) return "剪贴板内容不像配置 JSON";
                Bundle b = getContentResolver().call(
                        Uri.parse("content://" + AUTHORITY), "IMPORT_CONFIG", json, null);
                String r = b == null ? "ERR" : String.valueOf(b.getString("RESULT"));
                Log.i(TAG, "导入配置 -> " + r);
                return "OK".equals(r) ? "已导入配置（" + json.length() + " 字节）" : r;
            } catch (Throwable t) {
                return "导入失败: " + t;
            }
        }

        /** 跳「使用情况访问」授权页（免 root，用户手动开启） */
        @JavascriptInterface
        public void openUsageSettings() {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                Log.w(TAG, "打不开使用情况访问设置: " + t);
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable ignored) { }
            }
        }

        /** 当前系统是否深色（WebView 的 prefers-color-scheme 不可靠，由 Java 判定） */
        @JavascriptInterface
        public String getTheme() {
            int m = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m == android.content.res.Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
        }

        private String callProvider(String method, Bundle extras) {
            try {
                Bundle b = getContentResolver().call(
                        Uri.parse("content://" + AUTHORITY), method, null, extras);
                return b == null ? "ERR: 无返回" : String.valueOf(b.getString("RESULT"));
            } catch (Throwable t) {
                Log.e(TAG, method + " failed", t);
                return "ERR: " + t;
            }
        }

        // ---------------- Shizuku：借 shell 身份执行命令（见 notes/12 §5） ----------------

        @JavascriptInterface
        public String shizukuState() {
            try {
                if (!Shizuku.pingBinder()) return "Shizuku 未运行";
                if (Shizuku.isPreV11()) return "Shizuku 版本过旧（需 v11+）";
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return "已授权";
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    return "已被拒绝（请到 Shizuku 应用里手动授权）";
                }
                return "未授权";
            } catch (Throwable t) {
                return "不可用：" + t.getClass().getSimpleName();
            }
        }

        @JavascriptInterface
        public void requestShizuku() {
            runOnUiThread(() -> {
                try {
                    Shizuku.requestPermission(REQ_SHIZUKU);
                } catch (Throwable t) {
                    Log.e(TAG, "requestPermission 失败", t);
                }
            });
        }

        /** 借 Shizuku（= shell 身份）执行：写设置 [+ 重置桌面 + 回桌面] */
        @JavascriptInterface
        public String applyViaShizuku(boolean full) {
            try {
                if (!Shizuku.pingBinder()) return "Shizuku 未运行";
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return "Shizuku 未授权";

                StringBuilder cmd = new StringBuilder(
                        "settings put secure launcher3.layout.provider " + AUTHORITY);
                if (full) {
                    String lp = launcherPackage();
                    if (lp == null) return "找不到默认 HOME 应用";
                    cmd.append(" && pm clear ").append(lp)
                       .append(" && am start -a android.intent.action.MAIN -c android.intent.category.HOME");
                }
                return runAsShell(cmd.toString());
            } catch (Throwable t) {
                Log.e(TAG, "applyViaShizuku 失败", t);
                return "失败: " + t;
            }
        }

        /**
         * 以 shell 身份执行命令。
         * {@code Shizuku.newProcess} 在 13.x 里是 private，所以直接走 AIDL：
         * {@code IShizukuService.newProcess(...)} 拿 {@code IRemoteProcess}（其 waitFor/getErrorStream 都是公开的）。
         */
        private String runAsShell(String cmd) {
            try {
                android.os.IBinder binder = Shizuku.getBinder();
                if (binder == null) return "Shizuku binder 为空";
                moe.shizuku.server.IShizukuService svc = moe.shizuku.server.IShizukuService.Stub
                        .asInterface(new rikka.shizuku.ShizukuBinderWrapper(binder));
                Log.i(TAG, "[Shizuku] sh -c " + cmd);
                moe.shizuku.server.IRemoteProcess rp =
                        svc.newProcess(new String[]{"sh", "-c", cmd}, null, null);
                int code = rp.waitFor();
                String err = "";
                try (java.io.InputStream is = new android.os.ParcelFileDescriptor
                        .AutoCloseInputStream(rp.getErrorStream())) {
                    byte[] buf = new byte[2048];
                    int n = is.read(buf);
                    if (n > 0) err = new String(buf, 0, n).trim();
                } catch (Throwable ignored) { }
                Log.i(TAG, "[Shizuku] 退出码=" + code + (err.isEmpty() ? "" : " stderr=" + err));
                if (code == 0) return "OK";
                return "退出码 " + code + (err.isEmpty() ? "" : "：" + err);
            } catch (Throwable t) {
                Log.e(TAG, "runAsShell 失败", t);
                return "失败: " + t;
            }
        }

        private String launcherPackage() {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return (ri == null || ri.activityInfo == null) ? null : ri.activityInfo.packageName;
        }

        /** 需要用户手工执行的 adb 命令（免 root 路径） */
        @JavascriptInterface
        public String adbCommand() {
            return "adb shell settings put secure launcher3.layout.provider " + AUTHORITY;
        }

        @JavascriptInterface
        public void copy(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("layout", text));
                    toast("已复制");
                }
            } catch (Throwable t) {
                toast("复制失败");
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
