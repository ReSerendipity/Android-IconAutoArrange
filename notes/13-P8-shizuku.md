# 13 · P8：Shizuku 接入 —— 免 root 一键应用 ✅

> 执行日期：2026-10-02
> 结论：**打通了**。授权一次 Shizuku 后，app 内**一键**即可完成「写 Secure Settings → 重置桌面 → 回桌面」，
> **全程不需要 root、不需要 adb**。
> 产物：`poc/src/.../MainActivity.java`（Shizuku 集成）、`poc/shizuku/`（SDK）、`poc/artifacts/ui-v8-*.png`

---

## 1. 为什么可行

`notes/12` 已实测：**shell（uid 2000）能做完整流程**（写设置 ✅ / `pm clear` ✅ / `am start HOME` ✅）。
Shizuku 的服务进程正是跑在 uid 2000，所以它天然具备这些能力。

---

## 2. 集成步骤（**不用 Gradle**）

### 2.1 依赖（全部从 AAR 里抽 `classes.jar`，这些 AAR **无资源**）

| 坐标 | 用途 |
|---|---|
| `dev.rikka.shizuku:api:13.1.5` | `Shizuku` / `ShizukuBinderWrapper` 等 |
| `dev.rikka.shizuku:provider:13.1.5` | **`ShizukuProvider`**（收 binder 的关键） |
| `dev.rikka.shizuku:aidl:13.1.5` | `IShizukuService` / `IRemoteProcess` |
| `dev.rikka.shizuku:shared:13.1.5` | 共享常量 |
| `androidx.annotation:annotation:1.3.0` | 注解（**在 Google Maven，不在 Central**） |

下载后逐个解压出 `classes.jar`，放进 `poc/shizuku/`。

### 2.2 manifest 三处

```xml
<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />

<application ...>
    <meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" />

    <!-- ⚠️ 必须声明，否则 binder 永远收不到（最容易漏的一步） -->
    <provider
        android:name="rikka.shizuku.ShizukuProvider"
        android:authorities="com.example.layoutprovider.shizuku"
        android:enabled="true"
        android:exported="true"
        android:multiprocess="false"
        android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
</application>
```

### 2.3 构建脚本

```bash
# javac：classpath 列表 —— ⚠️ 必须用 `:` 分隔（见 §4.2）
javac -classpath "$PLATFORM:$SZK_JARS" ...

# d8：把 4 个 classes.jar 一起 dex 进去
d8 --lib "$PLATFORM" --min-api 26 --output out/dex <我们的 class> "$SZK"/*-classes.jar
```

### 2.4 代码

```java
// 主线程注册监听
Shizuku.addRequestPermissionResultListener((code, result) -> { ... });
Shizuku.addBinderReceivedListenerSticky(() -> { ... });

// 申请授权
Shizuku.requestPermission(1001);

// 执行命令（⚠️ Shizuku.newProcess 在 13.x 是 private，走 AIDL）
IBinder binder = Shizuku.getBinder();
IShizukuService svc = IShizukuService.Stub.asInterface(new ShizukuBinderWrapper(binder));
IRemoteProcess rp = svc.newProcess(new String[]{"sh", "-c", cmd}, null, null);
int code = rp.waitFor();
```

---

## 3. 实测结果

### 3.1 binder 连接

```
D ShizukuProvider: binder received
I LayoutProviderUI: Shizuku binder 已连接
```

### 3.2 授权

弹出系统对话框「**Allow 桌面自动排列 to access Shizuku?**」→ 点 Allow all the time：

```
I LayoutProviderUI: Shizuku 授权结果 code=1001 result=0      ← 0 = PERMISSION_GRANTED
```

### 3.3 一键应用（**免 root**）

点击「Shizuku 一键（会重置桌面）」：

```
I LayoutProviderUI: [Shizuku] sh -c settings put secure launcher3.layout.provider com.example.layoutprovider
                    && pm clear app.lawnchair.nightly
                    && am start -a android.intent.action.MAIN -c android.intent.category.HOME
I LayoutProviderUI: [Shizuku] 退出码=0
```

launcher 随即重建（1.2 秒后）：

```
I LayoutProviderPoC: 规划结果: 共 19 个应用; 【合并模式 · 基线 last-generated.xml(自我记录)】
                     保留文件夹 4 个, 新增 1 个应用, 清理失效 0 项; 桌面 文件夹4 + 单图标2 = 6 个槽位; dock=4
```

`Item position overlap` = **0**。桌面截图 `poc/artifacts/ui-v8-launcher-after-shizuku.png`：
4 个文件夹（效率/工具/影音/其他）+ Contacts/Maps + Dock 四个图标，**全部由 Shizuku 一键完成**。

> 彩蛋：应用数从 18 变 19 —— 因为装上了 Shizuku 自己，它也是启动器应用，被自动分类并「新增」进布局。
> 顺带证明了**增量合并的"补新"逻辑真的在工作**。

---

## 4. 踩坑（四个，都卡了一阵）

### 4.1 `androidx.annotation` 不在 Maven Central

`repo1.maven.org` 上 404。正确仓库是 Google Maven：
`https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar`

### 4.2 给 Windows 版 `javac` 传 classpath 列表，**必须用 `:` 分隔**

Git Bash(MSYS) 会把 `:` 分隔的路径列表**逐项**转成 Windows 路径；用 `;` 分隔会被当成**单个路径**，
报出一堆「程序包 android.content 不存在」。改成 `:` 后正常。

### 4.3 `Shizuku.newProcess` 在 13.x 是 **private**

`javap` 确认签名存在但不可见。改走 AIDL：

```java
IShizukuService.Stub.asInterface(new ShizukuBinderWrapper(Shizuku.getBinder()))
        .newProcess(new String[]{"sh","-c",cmd}, null, null)
```

`IRemoteProcess` 的 `waitFor()` / `getErrorStream()` 都是公开的，够用。

### 4.4 **必须声明 `rikka.shizuku.ShizukuProvider`**（否则 binder 永远收不到）

只加 `<uses-permission API_V23>` 是不够的 —— 界面会一直显示「Shizuku 未运行」。
补上 `<provider>` 声明后立刻变成「未授权」，再申请即成功。

### 4.5 模拟器上 Shizuku **无法用 root 方式启动**

Shizuku 界面里「Start (for rooted devices)」报
`Can't start service because root permission is not granted or this device is not rooted`。
原因：AOSP 的 `/system/xbin/su` 权限是 **`rwsr-x--- root:shell`** —— **只有 root 和 shell 组能执行**，
普通 app 调不动。（真机上 Magisk 的 `su` 是全局可执行 + 弹窗授权，所以那边没问题。）

**正确做法**：走「Start by connecting to a computer」→「View command」拿到命令，用 adb 执行：

```bash
adb shell /data/app/~~xxxx==/moe.shizuku.privileged.api-xxxx==/lib/x86_64/libshizuku.so
```

执行后 `ps -A` 里出现 **`shell  4700  ...  shizuku_server`** —— 服务以 **uid 2000** 跑起来了。

---

## 5. 体积与代价

| 项 | 值 |
|---|---|
| APK | 37.4 KB → **62.0 KB**（+24.6 KB，Shizuku SDK 4 个 jar） |
| 依赖 | 5 个 jar（4 个 Shizuku + annotation），**全部离线可用** |
| 用户成本 | 装 Shizuku + 授权一次（重启后需重新启动 Shizuku 服务） |

---

## 6. 现在 app 的四种应用方式（完整能力矩阵）

| 方式 | 免 root | 需 adb | 一键 | 说明 |
|---|---|---|---|---|
| ① 直接写（`pm grant`） | ✅ | 一次性 | ✅ | 仅 userdebug/eng 构建 |
| ② 借 `su` | ❌ | ❌ | ✅ | 需 root |
| ③ **Shizuku** | ✅ | 一次性（启动服务） | ✅ | **推荐**，生产设备可用 |
| ④ adb 命令 | ✅ | 每次 | ❌ | 兜底 |

---

## 7. 遗留

- **Shizuku 重启后失效**：设备重启需重新启动 Shizuku 服务（Shizuku 自身的限制）。
- **读取当前布局仍然不行**：Shizuku = shell 身份，读不到 launcher 的 `signatureOrSystem` provider（见 `notes/12` §2）。
  所以"增量"仍只能靠**自我记录基线**。
- 未在**真机**上验证（本机真机未接）；真机上 Shizuku 的启动/授权流程与模拟器不同（走无线调试）。
