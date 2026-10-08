# 06 · Launcher3 布局 XML 格式规格（P1 产出）

> 来源：Lawnchair 源码（Apache-2.0），均在本仓 `references/lawnchair/`
> - `src/com/android/launcher3/AutoInstallsLayout.java` —— **解析器（权威）**，定义全部 tag/attr
> - `src/com/android/launcher3/DefaultLayoutParser.java` —— 另一套格式（`<favorites>` 根）
> - `src/com/android/launcher3/util/LauncherLayoutBuilder.kt` —— **导出器**，定义 XML 的实际长相
> - `src/com/android/launcher3/util/LayoutImportExportHelper.kt` —— 导入/导出流程
> - `src/com/android/launcher3/model/LayoutParserFactory.kt` —— 外部布局的加载入口
> - `src/com/android/launcher3/model/ModelDbController.java` —— `getLayoutUri()` 契约
> - `src/com/android/launcher3/LauncherSettings.java` —— `containerToString()` / 常量
> - `lawnchair/res/xml/default_workspace_*.xml` —— **真实样例**（`<favorites>` 格式）
> 精读日期：2026-10-01
> 一句话：**布局 XML 有两套格式，根 tag 不同**——路径⑤（外部提供者）必须用 `<workspace>`；内置/partner 默认布局用 `<favorites>`。

---

## 0. 先分清两套格式（最容易踩的坑）

| | **`<workspace>` 格式** | **`<favorites>` 格式** |
|---|---|---|
| 解析器 | `AutoInstallsLayout`（rootTag=`workspace`） | `DefaultLayoutParser extends AutoInstallsLayout`（rootTag=`favorites`） |
| 谁在用 | **路径⑤ 外部布局提供者**（ContentProvider / Blob）、Play Auto Installs | **内置默认布局**（`default_workspace_*.xml`）、partner 布局 |
| 根 tag | `workspace` | `favorites` |
| container 取值 | **字符串** `desktop` / `hotseat` | **整数** `-100` / `-101` |
| 应用图标 tag | `appicon` / `autoinstall` | `favorite`（支持 `uri`） |
| 特有 tag | — | `resolve`（回退组）、`partner-folder` |
| 属性命名空间 | 可无（导出器就不带） | `launcher:`（`res-auto`） |

**判定依据**：`LayoutParserFactory.getAutoInstallsLayoutFromIS()` 构造时写死
`rootTag = AutoInstallsLayout.TAG_WORKSPACE` → **路径⑤ 的 XML 根节点必须是 `<workspace>`**。

---

## 1. `<workspace>` 格式：属性全表

| 属性 | 适用 | 含义 / 取值 |
|---|---|---|
| `container` | 全部 | `"desktop"` 或 `"hotseat"`。**只认这两个字符串**：等于 `hotseat` 走 hotseat 分支，**其余一律当 desktop** |
| `screen` | desktop | 桌面页号（0 起） |
| `rank` | hotseat | 在 dock 中的位置（**当 `container=hotseat` 时它被当作 screenId 用**，见 §5） |
| `x` / `y` | 全部 | 单元格坐标（0 起）。**可为负**：`-1` = 最后一行/列，`-2` = 倒数第二，依此类推 |
| `spanX` / `spanY` | appwidget | 占格数（其余类型硬编码为 1） |
| `packageName` | appicon/autoinstall/appwidget/shortcut | 包名 |
| `className` | appicon/autoinstall/appwidget | 组件类名（autoinstall 中省略时取 packageName） |
| `shortcutId` | shortcut | 深链接 shortcut 的 id |
| `title` | folder | **字符串资源 id**（数字）。⚠️ 见 §6 的 raw-XML 陷阱 |
| `titleText` | folder | **字面标题**（路径⑤ 用这个） |
| `userType` | 全部 | `"work"` / `"cloned"`（工作资料 / 应用分身） |
| `key` / `value` | `extra` | 小部件附加参数 |

### x / y 负值的解析（`convertToDistanceFromEnd`）

```java
// AutoInstallsLayout.java
private static String convertToDistanceFromEnd(String value, int endValue) {
    if (!TextUtils.isEmpty(value)) {
        int x = Integer.parseInt(value);
        if (x < 0) return Integer.toString(endValue + x);   // x=-1 → endValue-1
    }
    return value;
}
// x 用 mColumnCount，y 用 mRowCount
```
即 `x="-1"` 在 5 列网格上等于 `x="4"`。**负值依赖当前设备网格**——这正是提供者会收到
`gridWidth/gridHeight` 参数的原因（§7）。

---

## 2. `<workspace>` 格式：标签全表

| Tag | 必填属性 | 作用 | 解析器 | 缺失必填时 |
|---|---|---|---|---|
| `appicon` | `packageName`, `className` | 已安装应用图标；标题取**应用真实 label** | `AppShortcutParser` | 跳过该节点 |
| `autoinstall` | `packageName`, `className` | 自动安装占位图标；标题=`package_state_unknown`，`RESTORED=FLAG_AUTOINSTALL_ICON` | `AutoInstallParser` | 跳过 |
| `folder` | `title` 或 `titleText` | 文件夹；子节点仅允许 `appicon`/`autoinstall`/`shortcut` | `FolderParser` | 标题为空串 |
| `appwidget` | `packageName`, `className`, `spanX`, `spanY` | 小部件（pending）；子节点**仅允许** `<extra>` | `PendingWidgetParser` | 跳过 |
| `searchwidget` | 无（但**建议显式给 `spanX/spanY`**） | 搜索框（QSB），组件由 `QsbContainerView.getSearchComponentName()` 提供 | `SearchWidgetParser` | ⚠️ **实测必崩，勿用**（见下） |
| `shortcut` | `packageName`, `shortcutId` | 深链接快捷方式（会调 `LauncherApps.pinShortcuts`） | `ShortcutParser` | 跳过 |
| `include` | `workspace`(resId) | 递归引入另一个布局资源 | 特判 | 返回 0 |
| `extra` | `key`, `value` | **仅**作为 `appwidget` 子节点 | — | 抛异常 |

**`<favorites>` 格式额外/不同的 tag**：`favorite`（=appicon，且支持 `uri` 属性）、`resolve`
（回退组：**第一个成功解析的子项胜出**）、`partner-folder`、`folderItems`（资源引用）。

> ⚠️ **`searchwidget` 在 Lawnchair（commit `126b225`）上必然抛 NPE**：
> `SearchWidgetParser.verifyAndInsert` 先读 `mValues.getAsInteger(RESTORED)`，而 `RESTORED` 要到
> `super.verifyAndInsert` 才写入 → 恒为 null。**后果是整份布局解析中断**（`loadLayout` 返回 -1），
> launcher 会**丢弃你的全部布局、回退到内置默认布局**。实测细节见 `notes/09` §3。
> **结论：外部布局里不要用 `searchwidget`。**

---

## 3. 解析规则与容错（决定"写错会不会炸"）

| 情况 | 行为 |
|---|---|
| 根 tag 不匹配 | **抛异常** `Unexpected start tag: found X, expected workspace` |
| 未知 tag | **静默忽略**（返回 0，不报错） |
| `appicon`/`autoinstall` 缺 packageName/className | 跳过该节点，**不中断整体** |
| `appicon` 指向不存在的组件 | 跳过该节点（`NameNotFoundException`） |
| `folder` 内出现非法子 tag | **抛异常** `Invalid folder item <tag>` |
| `appwidget` 内出现非 `extra` 子节点 | **抛异常** `Widgets can contain only extras` |
| `extra` 缺 key 或 value | **抛异常** `Widget extras must have a key and value` |
| `folder` 解析后成功子项 **< 2** | 删除该文件夹；若**恰好 1 项**，把该项**提升到文件夹的位置**（复制 container/screen/cellX/cellY） |
| `x`/`y` 为负 | 转成 `网格数 + x` |

> 结论：**单条坏数据不会毁掉整份布局**（未知 tag / 无效组件都只是跳过），但
> **结构性错误（folder/appwidget 内放错子节点、根 tag 错）会让整次导入抛异常**。

---

## 4. 完整 XML 样例（`<workspace>` 格式，路径⑤ 用）

下面这份**手写样例**完全对应 `LauncherLayoutBuilder` 的输出结构（导出器用 `startTag(null,…)`，
所以**属性不带命名空间前缀**）：

```xml
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<workspace>

    <!-- ===== 桌面第 0 页 ===== -->
    <appicon container="desktop" screen="0" x="0" y="0"
             packageName="com.android.chrome" className="com.android.chrome.Main" />

    <appicon container="desktop" screen="0" x="1" y="0"
             packageName="com.android.settings" className="com.android.settings.Settings" />

    <!-- 文件夹（子节点只能是 appicon / autoinstall / shortcut） -->
    <folder container="desktop" screen="0" x="2" y="0" titleText="工作">
        <appicon packageName="com.tencent.mm" className="com.tencent.mm.ui.LauncherUI" />
        <appicon packageName="com.alibaba.android.rimet" className="com.alibaba.android.rimet.biz.LaunchHomeActivity" />
        <shortcut packageName="com.tencent.mm" shortcutId="wechat_pay_shortcut" />
    </folder>

    <!-- 小部件：spanX/spanY 必填；附加参数只能放 <extra> -->
    <appwidget container="desktop" screen="0" x="0" y="2"
               packageName="com.android.deskclock" className="com.android.alarmclock.DigitalAppWidgetProvider"
               spanX="4" spanY="2">
        <extra key="timeZone" value="Asia/Shanghai" />
    </appwidget>

    <!-- 搜索框（无属性） -->
    <searchwidget container="desktop" screen="0" x="0" y="4" />

    <!-- 深链接快捷方式 -->
    <shortcut container="desktop" screen="0" x="3" y="1"
              packageName="com.android.chrome" shortcutId="new_incognito_tab" />

    <!-- ===== 桌面第 1 页（x/y 负值：y=-1 = 最后一行） ===== -->
    <autoinstall container="desktop" screen="1" x="0" y="-1"
                 packageName="com.example.pending" className="com.example.pending.MainActivity" />

    <!-- ===== Hotseat / Dock：用 container=hotseat + rank ===== -->
    <appicon container="hotseat" rank="0"
             packageName="com.android.dialer" className="com.android.dialer.main.impl.MainActivity" />
    <appicon container="hotseat" rank="1"
             packageName="com.android.mms" className="com.android.mms.ui.ConversationList" />
    <appicon container="hotseat" rank="2"
             packageName="com.android.chrome" className="com.android.chrome.Main" />
    <appicon container="hotseat" rank="3"
             packageName="com.android.camera2" className="com.android.camera.CameraLauncher" />

</workspace>
```

> **注意**：`container="desktop"` 其实是**可省略的**——`parseContainerAndScreen()` 只在
> 值等于 `"hotseat"` 时特殊处理，其余（含缺失）都当 desktop。导出器仍会显式写出来。

---

## 5. Hotseat 的"偷梁换柱"（重要）

```java
// AutoInstallsLayout.parseContainerAndScreen()
if (HOTSEAT_CONTAINER_NAME.equals(getAttributeValue(parser, ATTR_CONTAINER))) {  // "hotseat"
    out[0] = CONTAINER_HOTSEAT;              // -101
    out[1] = getAttributeValueAsInt(parser, ATTR_RANK);   // ← 用 rank 当 screenId！
} else {
    out[0] = CONTAINER_DESKTOP;              // -100
    out[1] = getAttributeValueAsInt(parser, ATTR_SCREEN);
}
```

- `HOTSEAT_CONTAINER_NAME = Favorites.containerToString(CONTAINER_HOTSEAT)` = **`"hotseat"`**。
- Dock 项在数据库里用 `screen` 存位置（注释原话：*"Hack: hotseat items are stored using screen ids"*）。
- 所以 hotseat 项要写 **`container="hotseat" rank="N"`**，**不要**写 `screen`。
- 若 hotseat 项漏写 `rank` → `getAttributeValueAsInt` 直接**抛 `Missing attribute rank`**。

### `containerToString()` 全表（LauncherSettings.java）

| 常量 | 值 | 字符串 |
|---|---|---|
| `CONTAINER_DESKTOP` | -100 | `desktop` |
| `CONTAINER_HOTSEAT` | -101 | `hotseat` |
| `CONTAINER_ALL_APPS_PREDICTION` | -102 | `prediction` |
| `CONTAINER_ALL_APPS` | -104 | `all_apps` |
| `CONTAINER_WIDGETS_TRAY` | -105 | `widgets_tray` |
| `CONTAINER_SHORTCUTS` | -107 | `shortcuts` |
| 其他 | — | 数字字符串 |

---

## 6. ⚠️ raw-XML 陷阱（路径⑤ 必读）

路径⑤ 的 XML 是**从字符串解析**的（`LayoutParserFactory` 用 `StringReader`），**不是编译进 APK 的资源**。因此：

| 别写 | 要写 | 原因 |
|---|---|---|
| `title="@string/work"` | `titleText="工作"` | `getAttributeResourceValue` 在 raw XML 上**解析不出资源 id**，恒返回默认值 0 → 标题变空串 |
| `<include workspace="@xml/xxx"/>` | 直接内联展开 | 同上，资源引用无效 |
| `folderItems="@xml/xxx"`（`<favorites>` 格式） | 直接内联 | 同上 |

**属性命名空间**：解析器 `getAttributeValue()` 先试
`http://schemas.android.com/apk/res-auto/com.android.launcher3`，**失败回退到无命名空间**。
所以 `launcher:x="0"` 和 `x="0"` **都行**——导出器走的就是无命名空间那条路。

---

## 7. 路径⑤ 的对接契约（PoC 要实现的）

### 7.1 提供者（第三方 App 侧）

```java
// ModelDbController.getLayoutUri()
new Uri.Builder().scheme("content").authority(authority).path("launcher_layout")
    .appendQueryParameter("version", "1")
    .appendQueryParameter("gridWidth",  numColumns)              // 当前设备列数
    .appendQueryParameter("gridHeight", numRows)                 // 当前设备行数
    .appendQueryParameter("hotseatSize", numDatabaseHotseatIcons)
    .build();
```

→ 实际请求：`content://<authority>/launcher_layout?version=1&gridWidth=5&gridHeight=5&hotseatSize=5`
→ **返回体 = `<workspace>` 根的布局 XML 文本**（ContentProvider 的 `openFile`/`openAssetFile` 返回输入流）。

**网格参数怎么用**：`gridWidth/gridHeight` 就是 `x`/`y` 的取值范围；若你用负值坐标，
解析器也会按同一网格换算，所以只要保证 `x ∈ [0,gridWidth)`、`y ∈ [0,gridHeight)` 即可。

### 7.2 触发者（需 `WRITE_SECURE_SETTINGS`，adb 即可）

```bash
adb shell settings put secure launcher3.layout.provider com.your.app
# 然后让 launcher 重载（重启 App / 杀进程 / 切桌面）
```

### 7.3 加载顺序（`LayoutParserFactory.createExternalLayoutParser`）

```
1. createWorkspaceLoaderFromAppRestriction()   ← 路径⑤：读 Secure Settings 的 launcher3.layout.provider
2. AutoInstallsLayout.get()                    ← Play Auto Installs（受 BuildConfig.ENABLE_AUTO_INSTALLS_LAYOUT 开关约束）
3. Partner default layout                      ← 内置 partner 布局
4. null                                        ← 用系统默认
```

> **关键**：**第 1 步不检查 `ENABLE_AUTO_INSTALLS_LAYOUT` 开关**（该开关只管第 2 步）。
> 所以即使某构建把 auto-installs 关了，**路径⑤ 依然生效**——这对 PoC 是好消息。

---

## 8. 与 `<favorites>` 真实样例对照

`lawnchair/res/xml/default_workspace_5x5.xml`（节选，注意**整数 container + `launcher:` 前缀 + `<resolve>`**）：

```xml
<favorites xmlns:launcher="http://schemas.android.com/apk/res-auto/com.android.launcher3">

    <!-- Hotseat：container=-101，screen=位置 -->
    <resolve launcher:container="-101" launcher:screen="0" launcher:x="0" launcher:y="0">
        <favorite launcher:className="com.android.contacts.activities.TwelveKeyDialer"
                  launcher:packageName="com.android.contacts" />
        <favorite launcher:uri="#Intent;action=android.intent.action.DIAL;end" />
    </resolve>

    <!-- 桌面文件夹：title 是资源引用 -->
    <folder launcher:title="@string/google_folder_title"
            launcher:screen="0" launcher:x="0" launcher:y="4">
        <favorite launcher:packageName="com.google.android.gm"
                  launcher:className="com.google.android.gm.ConversationListActivityGmail"/>
    </folder>

    <!-- 桌面项：无 container = 默认 desktop；y=-1 = 最后一行 -->
    <resolve launcher:screen="1" launcher:x="0" launcher:y="-1">
        <favorite launcher:uri="mailto:" />
    </resolve>
</favorites>
```

**两套格式的差异速记**：
- `<favorites>` 的 container 是**数字**（`-101`），`<workspace>` 是**字符串**（`"hotseat"`）。
- `<favorites>` 有 `<resolve>` 回退组；`<workspace>` 没有。
- `<favorites>` 用 `<favorite uri="...">` 表达"任意能处理该 intent 的应用"；`<workspace>` 用 `<appicon packageName+className>` 精确指定。

---

## 9. P1 结论 & 交给 P2 的清单

> **✅ 2026-10-01 已实测验证**（见 `notes/07-PoC-layout-provider.md`）：本笔记的 `<workspace>` 格式在 Android 15 的
> **Pixel Launcher** 与 **Lawnchair** 上均成功导入（含文件夹 / dock / 多页）。
> 实测还补了两条本笔记**未覆盖**的坑，**以笔记 07 §5 为准**：
> ① **网格因 launcher 而异**（Pixel 4×5 / Lawnchair 4×6）→ 必须读 URI 上的 `gridWidth/gridHeight` **动态生成**；
> ② **屏幕 0 的第 0 行被 launcher 自己的 smartspace 小组件占用** → 排布要从 **y=1** 起，否则整项被丢弃。

**P1 完成**：`<workspace>` 格式的根节点、全部 tag、全部属性、负坐标语义、hotseat 特例、
解析容错边界、raw-XML 陷阱、ContentProvider 契约——**均已确认**，可直接照 §4 样例写 PoC。

**P2 要做的**（最小闭环）：

1. 写一个最小 App，实现 `ContentProvider`（authority 如 `com.example.layoutprovider`），
   `openFile` 返回 §4 的 XML（先硬编码 3~4 个已安装应用 + 一个 hotseat 项）。
2. 建 API 35 AVD，装 Lawnchair（或直接用系统 Launcher3）。
3. `adb shell settings put secure launcher3.layout.provider com.example.layoutprovider`
   → 重启 launcher → **验证桌面是否被重建**。
4. 记录：**失败时桌面会变成什么样**（`createEmptyDB + forceReload` 的后果）、
   日志里的 `AutoInstalls` tag 输出。

**仍需实测确认的点**：
- Lawnchair 实际发行版是否保留了该 Secure Settings 监听（定制 ROM 可能裁剪）。
- 系统 Launcher3 / Pixel Launcher 是否同样监听（AOSP 有，但 Pixel 可能限制）。
- `searchwidget` 在非 Google 环境（无 QSB 组件）下的行为。
