# 07 · P2 实测：路径⑤（Launcher3 官方布局通道）端到端验证 ✅

> 执行日期：2026-10-01
> 结论：**成功**。在 **Android 15 模拟器**上，通过「ContentProvider + Secure Settings」把一份 `<workspace>` XML
> 完整导入，桌面被按 XML 重建（含文件夹、多页、dock）。
> 验证目标：**Pixel Launcher**（`com.google.android.apps.nexuslauncher`）与 **Lawnchair**（`app.lawnchair.nightly`）**都通过**。
> 产物：`poc/`（PoC 工程 + 构建脚本 + APK + 数据库/截图取证）

---

## 1. 环境

| 项 | 值 |
|---|---|
| AVD | `LawnchairApi35`（pixel_6 / API 35 / google_apis / x86_64） |
| 系统 | Android 15（SDK 35），`userdebug`（`adb root` 可用） |
| 被测 launcher 1 | `com.google.android.apps.nexuslauncher`（Pixel Launcher，Launcher3 fork），**网格 4×5，hotseat 4** |
| 被测 launcher 2 | `app.lawnchair.nightly`（Lawnchair nightly `126b225`，与本仓 `references/lawnchair` 同 commit），**网格 4×6，hotseat 4** |
| PoC provider | `com.example.layoutprovider`（自己手搓的最小 APK，见 §3） |

---

## 2. 触发流程（**关键**：只在"DB 被清空重建"时才读 provider）

源码依据 `ModelDbController.loadDefaultFavoritesIfNecessary()`：

```java
if (mPrefs.get(getEmptyDbCreatedKey())) {          // ← 只有这个标记为真才走外部布局
    AutoInstallsLayout loader = mLayoutParserFactory.createExternalLayoutParser(...);  // ① 读 Secure Settings
    if (loader == null) loader = getDefaultLayoutParser(...);                          // ② 兜底：内置默认布局
    mOpenHelper.createEmptyDB(...);                                                     // ← 清空重建
    if (loadFavorites(...) <= 0 && usingExternallyProvidedLayout) {                     // ③ 外部布局一条都没进来
        createEmptyDB(...); loadFavorites(getDefaultLayoutParser(...));                 //    → 回退内置布局
    }
    clearFlagEmptyDbCreated();
}
```

`EMPTY_DATABASE_CREATED` 标记由 `createEmptyDB()` 写入 → 所以**单纯写 Secure Settings 不会立即生效**，
必须让 DB 处于"刚被清空重建"的状态。

**实测可复现的触发序列**：

```bash
ADB="…/platform-tools/adb.exe"; D="-s emulator-5554"
LP="app.lawnchair.nightly"   # 或 com.google.android.apps.nexuslauncher

$ADB $D shell "settings put secure launcher3.layout.provider com.example.layoutprovider"
$ADB $D shell "pm clear $LP"        # ← 清数据 = 下次启动必然新建空 DB（也清掉那个标记，靠新建触发）
$ADB $D shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
# 等 ~20-30s，launcher 完成重建
```

**验证落库**（emulator 是 userdebug，可 root）：

```bash
export MSYS_NO_PATHCONV=1            # ← 不设的话 adb 会把 /data/... 当成 Windows 路径改坏
$ADB $D root
$ADB $D pull /data/data/$LP/databases/<DB名> out.db
# DB 名随网格变化：Pixel → launcher_4_by_5.db；Lawnchair → launcher_6_4_4.db
```

---

## 3. PoC 实现

### 3.1 不依赖 Gradle 的 APK 构建（`poc/build.sh`）

只用 SDK 自带工具，**完全绕开 Gradle/AGP**：

| 步骤 | 工具 | 命令要点 |
|---|---|---|
| 编译 | `javac` | `-source 11 -target 11 -classpath <android-35/android.jar>` |
| 打包资源 | `aapt2 link` | `--manifest` + `-A assets` + `--min-sdk-version 26 --target-sdk-version 35` |
| 转 dex | `d8` | `--lib android.jar --min-api 26 --output out/dex *.class` |
| 塞 dex | Python `zipfile` | 把 `classes.dex` 追加进 aapt2 产出的 apk |
| 对齐 | `zipalign` | `-f -p 4` |
| 签名 | `apksigner` | debug keystore（`keytool` 现生成） |

产物仅 **12.7 KB**，`install` 即用。

### 3.2 Provider 关键点（`poc/src/.../LayoutProvider.java`）

- 实现 `ContentProvider.openFile()`，把生成的 XML 写进 `cacheDir` 再返回 `ParcelFileDescriptor`。
  （launcher 走 `ContentResolver.openInputStream()` → 默认落到 `openFile()`。）
- **读取 URI 上的网格参数**：`gridWidth / gridHeight / hotseatSize`（见笔记 06 §7 契约），据此**动态生成**布局。
- Manifest：`<provider android:exported="true" android:authorities="com.example.layoutprovider">`。

---

## 4. 实测结果

### 4.1 Provider 收到的请求（日志实证，与笔记 06 §7 契约完全一致）

```
Pixel Launcher   : content://com.example.layoutprovider/launcher_layout?version=1&gridWidth=4&gridHeight=5&hotseatSize=4
Lawnchair        : content://com.example.layoutprovider/launcher_layout?version=1&gridWidth=4&gridHeight=6&hotseatSize=4
```

### 4.2 最终落库（Lawnchair，4×6，17 行，**0 冲突**）

| _id | itemType | container | screen | cellX | cellY | title | 说明 |
|---|---|---|---|---|---|---|---|
| 1 | **2 (FOLDER)** | -100 | 0 | 0 | 1 | **工作** | 文件夹 |
| 2 | 0 | **1** | — | — | — | Settings | ← 在文件夹内（container = 文件夹 `_id`） |
| 3 | 0 | **1** | — | — | — | Gmail | ← 同上 |
| 4 | 0 | **1** | — | — | — | Contacts | ← 同上 |
| 5–7 | 0 | -100 | 0 | 1–3 | 1 | Chrome / Maps / Clock | 桌面第 1 行 |
| 8–11 | 0 | -100 | 0 | 0–3 | 2 | Camera / Calendar / Photos / YouTube | 桌面第 2 行 |
| 12–13 | 0 | -100 | 0 | 0–1 | 3 | Drive / YT Music | 桌面第 3 行 |
| 14–17 | 0 | **-101** | 0–3 | — | — | Phone / Messages / Chrome / Camera | **dock（rank→screen）** |

- 日志 `Item position overlap` 次数：**0**。
- 截图取证：`poc/artifacts/lawnchair-result.png`（文件夹「工作」+ 9 图标 + 4 dock 图标，完全按 XML 重建）。

---

## 5. 关键发现（**对 P3 直接有用**）

### 5.1 ✅ 路径⑤ 在 Android 15 的两大 launcher 上都成立

- **Pixel Launcher**（Google 的 Launcher3 fork）**也支持** —— 说明这不是 Lawnchair 特有。
- 入口 `createWorkspaceLoaderFromAppRestriction()` **不受 `ENABLE_AUTO_INSTALLS_LAYOUT` 开关约束**（该开关只管 Play Auto Installs 那条）。
- 只要写一次 Secure Settings + 让 DB 重建，就能整份替换桌面。

### 5.2 ⚠️ 网格**因 launcher 而异**，必须读 `gridWidth/gridHeight`

同一台设备上：Pixel Launcher 是 **4×5**，Lawnchair 是 **4×6**。

**反例实证**：v1 PoC 硬编码 5 列，在 4×5 的设备上 `x=4` 越界，日志报
`out of screen bounds ( 4x5)`，那一行图标**被静默丢弃**（不报错、不中断）。

→ **结论：provider 必须按 URI 传入的网格生成布局**（v2 已实现，正是 P3 要做的"规划器"雏形）。

### 5.3 ⚠️ **屏幕 0 的第 0 行被 launcher 自己占用**，别往那放图标

- Pixel Launcher：`BcSmartspaceView`（"At a Glance"）
- Lawnchair：`app.lawnchair.smartspace.SmartspaceAppWidgetProvider`（`addWidgetLocked()` 插入）

放在 y=0 的项在 `LoaderCursor` 阶段被判
`into cell (0-0:0,0,1,1) already occupied` / `Item position overlap` → **整项丢弃**。
（第一次实测：文件夹 + 4 个图标全丢，桌面只剩 y≥1 的项。）

→ **规避**：屏幕 0 从 **y=1** 开始排布（v2 已改，冲突数从 N 降到 **0**）。

### 5.4 其他实测细节

- **文件夹语义确认**：文件夹本身 `container=-100`（在桌面），其子项 `container=<文件夹的 _id>`，且子项**没有** cellX/cellY/screen。
- **文件夹 <2 子项会被删掉**（源码 `FolderParser`），子项解析失败也计入；本 PoC 用 3 个稳定存在的应用。
- `intent` 里 `$` 会被编码成 `%24`（如 `Shell%24HomeActivity`），不影响解析。
- **失败不会崩**：外部布局一条都没进来时，launcher 会 `createEmptyDB` 回退到内置默认布局（源码 ③ 分支）。

---

## 6. 复现步骤（TL;DR）

```bash
# 0) 前提：AVD LawnchairApi35 已建、模拟器已启动
# 1) 构建 + 安装 PoC
cd poc && bash build.sh
adb -s emulator-5554 install -r poc-layoutprovider.apk

# 2) 触发
adb -s emulator-5554 shell "settings put secure launcher3.layout.provider com.example.layoutprovider"
adb -s emulator-5554 shell "pm clear app.lawnchair.nightly"
adb -s emulator-5554 shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
sleep 25

# 3) 验证（看 provider 是否收到请求 + 落库）
adb -s emulator-5554 logcat -d -s LayoutProviderPoC | head
export MSYS_NO_PATHCONV=1
adb -s emulator-5554 root
adb -s emulator-5554 pull /data/data/app.lawnchair.nightly/databases/launcher_6_4_4.db out.db
```

**撤销**：`adb shell settings delete secure launcher3.layout.provider`，再 `pm clear <launcher>` 重建即可。

---

## 7. 遗留 / 未验证

- **厂商 ROM**（小米/华为/OPPO）是否保留该通道 —— 未验证（本机真机 RMX5010 不在本项目测试范围）。
- **`appwidget` / `searchwidget`** 尚未在 PoC 中实测（需要精确的 provider 组件名；笔记 06 §2 已给出格式）。
- **多页**：本 PoC 的 9 个图标正好一页放完，**跨页（screen≥1）尚未实测**。
- **重复导入**：连续两次导入是否稳定（`createEmptyDB` 的幂等性）未压测。
- PoC 里的图标清单是硬编码的（"选哪些 App"）；**按分类/规则自动选**属于 **P3**。
