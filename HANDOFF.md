# 交接总结 —— 新对话从这里开始

> 生成时间：2026-10-01 00:00
> 最近复核：2026-10-08 14:45（**已开源发布** —— 公开仓库 `ReSerendipity/Android-IconAutoArrange`，Apache-2.0；构建脚本已去硬编码、可移植）
> 用途：当前对话上下文过长，此文档用于在新对话中无缝接续

---

## 一、一句话说明

**安卓端「批量移动 / 自动排列桌面图标」研究项目**。**P1–P6 全部完成**：现在是一个**带 UI 的 app** ——
可查看应用清单/分类、可视化桌面预览、生成布局 XML，并给出应用入口。
**产品形态与权限边界见 §十五，UI 见 §十六。**

## 二、需求与核心结论

- **需求**：安卓上能"批量移动图标、自动排列桌面"的工具。
- **结论**：安卓**没有公开 API** 让第三方重排桌面图标（图标位置存在各 launcher 私有数据库）。
- **可行路径 5 条，推荐 ⑤ 主攻 + ① 兜底**。

| 路径 | 粒度 | 门槛 | 适用 | 推荐 |
|---|---|---|---|---|
| **⑤ Launcher3 官方布局通道** | 整份布局 | adb / root | 仅 Launcher3 系 | ★★★★ |
| ① UI 模拟拖拽（UIAutomator2） | 单图标 | 无 | 任何 launcher | ★★★ |
| ② 直写 `launcher.db` | 整库 | Shizuku / root | Launcher3 系 | ★★ |
| ④ 自建 launcher | 整体 | 换桌面 | 无限制 | ★★ |
| ③ 解析备份文件 | 只读 | 无 | 对应 launcher | ★ |

## 三、项目位置与结构

```
C:\Users\Doro\Desktop\Android-IconAutoArrange\
├── README.md
├── LICENSE                    Apache-2.0
├── .gitignore                 排除 references/ 与第三方 APK 等
├── .gitattributes             固定 *.sh 为 LF
├── notes\
│   ├── 00-总览评估.md          ← 状态盘点 + 五路径对比 + 分阶段计划
│   ├── 01-android-app-organizer.md       UIAutomator2 + 快照混合
│   ├── 02-android-folderautomanager.md   自建 launcher + 8 级分类链
│   ├── 03-novalaunchereditor.md          Nova 备份格式（zip+SQLite+XML）
│   ├── 04-lawnchair-launcher3-db.md      favorites 表 19 列权威定义
│   ├── 05-launcher3-layout-provider.md   ★ 第五条路径（核心）
│   ├── 06-launcher3-layout-xml-format.md ★ P1 产出：布局 XML 完整规格 + 样例
│   ├── 07-PoC-layout-provider.md         ★ P2 产出：端到端实测记录（成功）
│   └── 08-P3-layout-planner.md           ★ P3 产出：自动分类 + 网格排布（成功）
│   └── 09-P4-widgets-and-multipage.md    ★ P4 产出：占位网格 / appwidget / 多页 + searchwidget 硬伤
│   └── 10-P5-export-import-and-merge.md  ★ P5 产出：导出接口 + 权限边界 + 增量合并
│   └── 11-P6-ui.md                       ★ P6 产出：WebView 界面（含截图）
│   └── 12-免root通道调研.md               ★ P7 产出：通道排查结论 + 三级应用策略
│   └── 13-P8-shizuku.md                  ★ P8 产出：Shizuku 接入（免 root 一键打通）
│   └── 14-P9-manual-grouping.md          ★ P9 产出：手动分组（勾选/搜索 → 一个文件夹）
│   └── 15-功能扩展路线图.md               ★ 下一步规划：三根支柱 + 优先级建议
│   └── 16-P10-实施计划与重构.md            ★ P10 计划 + 第 0–1 步（重构）完成记录
│   └── 17-P10-使用频率驱动.md              ★ P10 第 2–3 步：使用频率 + 三处策略
│   └── 18-P10-变更预览与Dock配置.md         ★ P10 第 4–5 步：Diff 预览 + Dock 手动指定
│   └── 19-P10-快照回滚与多套方案.md         ★ P10 第 6–7 步：快照回滚 + 多套方案 Profile
│   └── 20-P10-待归位与导入导出.md           ★ P10 第 8–10 步：待归位 + 导入导出 + P3 小项（含 P10 收尾）
├── poc\                        ★ 可运行的 PoC 工程
│   ├── build.sh                          ★ 无 Gradle 手搓 APK（aapt2+d8+apksigner，含 Shizuku SDK；SDK/版本/python 自动探测）
│   ├── tools/capture-golden.sh           ★ 回归基线抓取（带模式断言；用 forceFull，免 root；adb/python 自动探测）
│   ├── shizuku/                          从 AAR 抽出的 classes.jar（离线依赖）
│   ├── src/.../LayoutProvider.java       ← 只做 ContentProvider 契约
│   ├── src/.../LayoutPlanner.java        ★ 纯规划（枚举/分类/成组/占格/渲染 + 三处频率策略）
│   ├── src/.../AppConfig.java            ★ 全部配置收口（含 dockMode/folderSort/screenStrategy）
│   ├── src/.../UsageRank.java            ★ 使用频率（三层降级 + 缓存 + appop 检查）
│   ├── src/.../LayoutDiff.java           ★ 变更预览（只比容器，不比坐标）
│   ├── src/.../Snapshots.java            ★ 布局快照（最多 5 份，支持回滚）
│   ├── src/.../PendingApps.java          ★ 待归位（按需比对，不用广播接收器）
│   ├── src/.../MainActivity.java         UI 壳（WebView + JS 桥 + Shizuku）
│   ├── assets/ui.html                    界面本体（HTML/CSS/JS，零依赖）
│   ├── poc-layoutprovider.apk            产物（70KB）
│   └── artifacts/                        取证：golden-*/、launcher DB dump、截图
├── tools\                      ★ 仓库维护脚本
│   └── fetch-references.sh               确定性重建 references/（URL+commit+稀疏规则固化，带断言）
└── references\                4 个参考仓库（**不入公开仓**；用 tools/fetch-references.sh 还原）
```

## 四、已完成

- [x] 跨平台调研（Windows + 安卓）
- [x] 克隆 4 个参考仓库
- [x] 5 篇源码精读笔记
- [x] 总览评估（`notes/00-总览评估.md`）
- [x] 读 `LauncherLayoutBuilder.kt`（布局 XML 生成器）
- [x] 下载 `system-images;android-35;google_apis;x86_64` ✅ **已复核完成**（rev 9，`system.img` 3.57GB，kernel/ramdisk/vendor/package.xml 全部齐备）
- [x] **P1**：读 `AutoInstallsLayout.java` + `DefaultLayoutParser.java` + `LauncherLayoutBuilder.kt` + `LayoutParserFactory.kt` + 真实样例 → 产出 **`notes/06-launcher3-layout-xml-format.md`**（两套格式对比 / 属性与标签全表 / 解析容错 / raw-XML 陷阱 / ContentProvider 契约）
- [x] **P2**：建 API 35 AVD（`LawnchairApi35`）→ 手搓最小 ContentProvider APK（**不用 Gradle**）→ **在 Pixel Launcher 与 Lawnchair 上均导入成功** → 产出 **`notes/07-PoC-layout-provider.md`** + `poc/`（可运行工程 + DB/截图取证）
- [x] **P3**：把 PoC 升级为**真·布局规划器**（枚举全部应用 → `ApplicationInfo.category`+关键词分类 → ≥2 成文件夹 → 网格排布 → 自动挑 dock）→ 产出 **`notes/08-P3-layout-planner.md`**；实测 18 应用 → 22 行落库、0 冲突
- [x] **P4**：把"行优先"换成**真·占位网格**（支持 spanX/spanY）→ 实测 **`appwidget` ✅ / 多页 ✅ / `searchwidget` ❌（必然 NPE，会毁掉整份布局）** → 产出 **`notes/09-P4-widgets-and-multipage.md`**
- [x] **P5**：发现 launcher 侧 `LauncherProvider.call()` 的 **`EXPORT_LAYOUT_XML` / `IMPORT_LAYOUT_XML`** 接口 → 实测权限边界（adb ✅ / 普通 app ❌）→ 实现并验证**增量合并模式**（保留用户文件夹 4 个、补新 1、清失效 1）→ 产出 **`notes/10-P5-export-import-and-merge.md`**
- [x] **P6**：给 app 加 **WebView UI**（`MainActivity` + `assets/ui.html`，零依赖）——应用清单/分类、**可视化桌面预览**、XML 查看、应用入口；UI 通过 provider 的 `PLAN_JSON` 取数，**与真实导入共用同一份规划代码** → 产出 **`notes/11-P6-ui.md`**

## 五、待办（按顺序，P1 不依赖设备）

| 阶段 | 任务 | 状态 |
|---|---|---|
| **P1** | 读 `AutoInstallsLayout.java`，产出**完整 XML 格式样例**（笔记 06） | ✅ **已完成（2026-10-01）** → `notes/06-launcher3-layout-xml-format.md` |
| **P2** | 用 `avdmanager` 建 API 35 AVD → 装 Lawnchair APK → 最小 PoC（ContentProvider 返回固定 XML + `adb shell settings put secure launcher3.layout.provider <authority>`） | ✅ **已完成（2026-10-01）** → `notes/07` + `poc/`；**Pixel Launcher 与 Lawnchair 均通过** |
| **P3** | 布局规划算法（网格 / 分类，复用 `ApplicationInfo.category`） | ✅ **已完成（2026-10-01）** → `notes/08`；`poc` 里的 provider 已是 v3 规划器 |
| **P5** | 增量排列（导出 → 合并 → 再导入） | ✅ **已完成（2026-10-01）** → `notes/10`；发现 launcher 的 `call()` 导出接口 + 权限边界 + 合并模式实测通过 |

### 阶段总览（P0–P5 全部完成）

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 模拟器环境（API 35 AVD） | ✅ |
| P1 | 布局 XML 格式规格（`notes/06`） | ✅ |
| P2 | 路径⑤ 端到端 PoC（`notes/07`） | ✅ |
| P3 | 自动分类 + 网格排布（`notes/08`） | ✅ |
| P4 | 占位网格 / appwidget / 多页（`notes/09`） | ✅ |
| P5 | 导出接口 + 增量合并（`notes/10`） | ✅ |
| P6 | WebView UI（`notes/11`） | ✅ |
| P7 | 免 root 通道排查 + 三级应用策略（`notes/12`） | ✅ |
| P8 | **Shizuku 接入**：免 root 一键应用（`notes/13`） | ✅ |
| P9 | **手动分组**：勾选/搜索 → 一个桌面文件夹（`notes/14`） | ✅ |
| P10 | 功能扩展（7 项）+ 重构（`notes/15`~`notes/20`） | ✅ **10 步全部完成** |
| **P3** | 布局规划算法（网格 / 分类，复用 `ApplicationInfo.category`） | 未开始 |
| **P4** | 扩展到其他 launcher（Nova 走 ①/③） | 未开始 |

## 六、关键技术情报（避免重复研究）

### 1. Launcher3 `favorites` 表（19 列，SCHEMA_VERSION=32）
`_id, title, intent, container, screen, cellX, cellY, spanX, spanY, itemType, appWidgetId, icon, appWidgetProvider, modified, restored, profileId, rank, options, appWidgetSource`

**container 常量**：`-100` 桌面 / `-101` dock / `>0` 表示在文件夹内（值是文件夹项的 `_id`）

### 2. 官方布局通道（路径⑤，笔记 05）
- 布局格式：**XML**，tag = `workspace / autoinstall(appicon) / folder / appwidget / shortcut`
- 传递通道：**BlobStore**（`blob://`+SHA-256）或 **ContentProvider**
- **ContentProvider 契约**：`content://<authority>/launcher_layout?version=1&gridWidth=&gridHeight=&hotseatSize=`
- 触发：写 Secure Settings 的 **`launcher3.layout.provider`** 键 → 需 `WRITE_SECURE_SETTINGS`（**adb 即可，不需 root**）
- 导入动作：`createEmptyDB` + `forceReload`（**会清空重建**）

### 3. 分类策略（照搬 android-folderautomanager）
优先级链：**用户决策 → 内置映射表 → `ApplicationInfo.category`（Play Store 官方分类，免费离线）→ 厂商前缀 → 关键词 → LLM（可选，失败回退）**

### 4. UI 拖拽配方（android-app-organizer）
长按 **1.45s** 进编辑模式 → 分 4 段插值曲线移动 → 目标抖动悬停 **2s** 触发文件夹展开 → 释放。共 12 个时序参数。

## 七、环境事实（本机）

| 项 | 值 |
|---|---|
| Android SDK | `C:\Users\Doro\AppData\Local\Android\Sdk` |
| emulator | `<SDK>\emulator\emulator.exe`（v36.6.11） |
| avdmanager | `<SDK>\cmdline-tools\latest\bin\avdmanager.bat` |
| 已有 AVD | `GalgameApi26`（**API 26，太老，无官方布局通道**） |
| 已装 platforms | android-35 / 36 / 36.1 |
| 已装 system-images | android-26；**android-35 google_apis x86_64（新下载）** |

## 八、已知坑

1. ~~**目录 rename / 大目录删除被系统限制**~~ → ✅ **已清理（2026-10-01 00:31）**。桌面两份多余副本（各 363MB、**合计约 726MB**）已走**回收站**删除，主项目 `references/` 完好未受影响。
   - ~~`Desktop\Android-IconAutoArrange\AndroidIconAutoArrange\`（嵌套副本）~~ → 已删除
   - ~~`Desktop\AndroidIconAutoArrange\`（旧目录）~~ → 已删除
   - 注意：文件现位于**回收站**，**要真正腾出磁盘空间需清空回收站**（`Clear-RecycleBin -DriveLetter C -Force`）。
2. **lawnchair 体积从 33MB 膨胀到 327MB** → blobless clone 按需下载 blob，属预期。
3. **`novalaunchereditor` 无 License** → 仅参考思路，勿复制代码。
4. **API 26 验证不了路径⑤** → 官方通道是 2024 年 AOSP（Android 14/15）才引入。

## 九、新对话怎么开始

**P1–P6 已全部完成**。项目现在是一个**带界面的 app**（可安装、可交互），
处于「功能闭环已完整、待补产品化细节」状态。
接续时直接说：**"继续 Android-IconAutoArrange 项目，先看 HANDOFF 的 §十六"**，并让助手先读：
- `C:\Users\Doro\Desktop\Android-IconAutoArrange\notes\00-总览评估.md`（总览 + 五路径）
- `...\notes\06-launcher3-layout-xml-format.md`（XML 格式规格；**注意 searchwidget 的警告**）
- `...\notes\07-PoC-layout-provider.md`（P2 实测 + 三条关键坑）
- `...\notes\08-P3-layout-planner.md`（P3 规划器设计 + 三个踩坑）
- `...\notes\09-P4-widgets-and-multipage.md`（P4 占位网格 / appwidget / 多页 / searchwidget 硬伤）
- `...\notes\10-P5-export-import-and-merge.md`（P5 导出接口 / 权限边界 / 增量合并）
- `...\notes\11-P6-ui.md`（P6 界面设计与实现）
- `...\poc\src\com\example\layoutprovider\LayoutProvider.java`（**规划核心，改动都在这**）
- `...\poc\assets\ui.html`（**界面本体**）

即可无缝接续。

---

## 十一、P1 产出摘要（2026-10-01）

**`notes/06-launcher3-layout-xml-format.md`** 已把路径⑤ 要写的 XML 格式完全确定：

- **两套格式**：路径⑤ 必须用 **`<workspace>` 根**（`AutoInstallsLayout`）；内置/partner 默认布局用 `<favorites>` 根（`DefaultLayoutParser`）。**混用必失败。**
- **container 取值**：`<workspace>` 用字符串 `"desktop"`/`"hotseat"`；`<favorites>` 用数字 `-100`/`-101`。
- **hotseat 特例**：写 `container="hotseat" rank="N"`（**rank 被当 screenId**），漏 rank 直接抛异常。
- **负坐标**：`x/y=-1` = 最后一行/列（按当前网格换算）。
- **raw-XML 陷阱**：路径⑤ 的 XML 是字符串解析，**`title="@string/x"` 无效** → 必须用 `titleText="字面标题"`；`<include>`/`folderItems` 资源引用同样无效。
- **容错边界**：未知 tag / 无效组件 → 跳过；但根 tag 错、folder/appwidget 内放错子节点 → **整次导入抛异常**。
- **好消息**：`createWorkspaceLoaderFromAppRestriction()` 这条路径**不受 `ENABLE_AUTO_INSTALLS_LAYOUT` 开关约束**，PoC 更容易成立。
- **可直接照抄的完整 `<workspace>` 样例**（含 appicon / folder / appwidget+extra / searchwidget / shortcut / hotseat）见笔记 §4。

---

## 十二、P2 产出摘要（2026-10-01）

**`notes/07-PoC-layout-provider.md`** + `poc/`：路径⑤ **端到端验证成功**。

- **环境**：AVD `LawnchairApi35`（pixel_6 / API 35 / Android 15 / userdebug）。
- **PoC**：`poc/` 里的最小 APK（**12.7KB，不用 Gradle**，用 `aapt2 + d8 + zipalign + apksigner` 手搓），
  实现 `ContentProvider`，读 URI 上的 `gridWidth/gridHeight/hotseatSize` **动态生成** `<workspace>` XML。
- **触发条件（关键）**：`loadDefaultFavoritesIfNecessary()` **只在 `EMPTY_DATABASE_CREATED` 标记为真时**才读 provider
  → 所以流程是「写 Secure Settings → `pm clear <launcher>` → 启动 HOME」。
- **结果**：**Pixel Launcher（4×5）与 Lawnchair（4×6）都成功**；Lawnchair 上落库 17 行（含文件夹 `工作` + 其 3 个子项 + 9 个桌面图标 + 4 个 dock），**冲突 0 次**；截图见 `poc/artifacts/lawnchair-result.png`。

**三条实测教训（P3 必须遵守）**：

1. **网格因 launcher 而异**（Pixel 4×5 / Lawnchair 4×6）→ **必须**按 URI 传入的网格生成；硬编码列数会静默丢图标。
2. **屏幕 0 的第 0 行被 launcher 自己的 smartspace 小组件占用**（Pixel 的 `BcSmartspaceView` / Lawnchair 的 `SmartspaceAppWidgetProvider`）→ 排布从 **y=1** 起，否则整项被判"位置重叠"丢弃。
3. **文件夹语义**：文件夹本体 `container=-100`（桌面），子项 `container=<文件夹 _id>`；子项 <2 会被自动删除。

**撤销 PoC**：`adb shell settings delete secure launcher3.layout.provider` 后 `pm clear <launcher>` 重建即可。

---

## 十三、P3 产出摘要（2026-10-01）

**`notes/08-P3-layout-planner.md`**：Provider 从「硬编码清单」升级为**真·规划器**（`poc/.../LayoutProvider.java` v3）。

**流水线**：枚举 → 分类 → 成组 → 排布 → dock

| 步 | 做法 |
|---|---|
| ① 枚举 | `queryIntentActivities(MAIN/LAUNCHER)`；排除自己 + 排除 HOME 组件；**按包去重** |
| ② 分类 | `ApplicationInfo.category`（API 26+）优先 → 关键词兜底 → 「其他」 |
| ③ 成组 | 同类 ≥2 → 文件夹；=1 → 桌面单图标 |
| ④ 排布 | 按 URI 的 `gridWidth/gridHeight` 行优先；**屏幕 0 从 y=1 起** |
| ⑤ dock | 优先级候选自动挑 `hotseatSize` 个 |

**实测**（Lawnchair 4×6）：`共 18 个应用 → 效率3/工具3/影音3/其他3 + 社交1 + 出行1`，
桌面 4 个文件夹 + 2 个单图标，dock 4 个；**22 行落库、冲突 0**；截图 `poc/artifacts/lawnchair-p3-result.png`。

**三个新踩的坑（`notes/08` §4）**：

1. **`targetSdk 30+` 必须写 `<queries>`**，否则 `queryIntentActivities` 只返回自己 → 枚举不到应用。
2. **同一应用可能有多个 LAUNCHER 入口**（如 Google 的 SearchActivity + VoiceSearchActivity）→ 必须**按包去重**，优先 `getLaunchIntentForPackage`。
3. **排除"启动器自身"要按组件不能按包名**：AOSP 的 Settings 含 `FallbackHome`（声明了 HOME），按包名排除会把整个 Settings 误杀。

---

## 十四、P4 产出摘要 + 后续方向（2026-10-01）

**`notes/09-P4-widgets-and-multipage.md`**：规划器升级为 **v4**（真·占位网格）。

### 三项实测

| 项 | 结果 |
|---|---|
| **`appwidget`** | ✅ 落库 `itemType=4`、span 2×2 正确；**图标自动绕开它的格子**（23 行、0 冲突）；截图 `poc/artifacts/lawnchair-p4-final.png` |
| **`searchwidget`** | ❌ **硬伤**：Lawnchair 的 `SearchWidgetParser.verifyAndInsert` 读未初始化的 `RESTORED` → **必然 NPE** → **整份布局解析中断** → launcher **丢弃全部布局、回退内置默认布局**。**外部布局里不要用 `searchwidget`**（已修正 `notes/06` §2） |
| **多页** | ✅ 28 个图标正确铺到屏幕 0（20 个，行 1–5）+ 屏幕 1（8 个，行 0–1）；0 冲突 0 越界 |

### v4 的两个设计点

1. **占位网格**（`Grid` 类）：`boolean[gw][gh]` + 动态开屏 + 支持 spanX/spanY。
   v3 的"行优先递增"假设每项都是 1×1，一放小组件就会踩踏。
2. **调试配置** `<filesDir>/poc-config.txt`：`widgets=1` / `searchwidget=1` / `spread=1` / `repeat=N`
   —— **不改代码就能 A/B 实测**（root 直写 + `chmod 644`）。

### 后续方向（按价值排序）

1. **产品化核心问题：路径⑤ 是"重建"不是"增量排列"**。
   `createEmptyDB` 会清空用户已有布局。真正可用的产品需要：
   **`exportModelDbAsXml` 导出 → 与规划结果合并 → 再导入**（导出 API 就在 `LayoutImportExportHelper`）。
2. **分类精细化**：当前「其他」桶偏杂；可按 `notes/02` 的链路补 内置映射表 → 厂商前缀 → LLM 兜底。
3. **真机 / 厂商 ROM 验证**：本机真机是 RMX5010（realme），**未测**；小米/华为是否保留该通道未知。
4. **Nova 等非 Launcher3 系**：路径⑤ 无效，只能走 ①（UI 拖拽）/③（解析备份）兜底。
5. **`searchwidget` NPE 在 AOSP / Pixel Launcher 上是否同样存在**：未验证（Pixel 的类被 R8 混淆过）。

---

## 十五、P5 产出摘要 + 产品形态建议（2026-10-01）

**`notes/10-P5-export-import-and-merge.md`**：路径⑤ 从"整份重建"升级为**增量合并**。

### 🔎 新发现：launcher 侧的 export / import 接口

`LauncherProvider.call()`（`references/lawnchair/src/com/android/launcher3/LauncherProvider.java`）暴露了：

| 方法 | 契约 |
|---|---|
| `EXPORT_LAYOUT_XML` | authority = **`<launcher包名>.settings`**；返回 `Bundle.getString("KEY_LAYOUT")` = 当前布局 XML |
| `IMPORT_LAYOUT_XML` | `arg` 直接传 XML 字符串；返回 `KEY_RESULT` |

权限 = `<launcher包名>.permission.READ_SETTINGS` / `WRITE_SETTINGS`，**均为 `signatureOrSystem`**。

### 🔒 权限边界（实测，含**一次自我纠错**）

| 能力 | 普通 app | shell(2000) / **Shizuku** | **root** |
|---|---|---|---|
| **写**布局（导入） | ✅ 经 Secure Settings 的 `launcher3.layout.provider` | ✅ | ✅ |
| **读**布局（导出） | ❌ `SecurityException: Permission Denial …` | ❌ **同样被拒** | ✅ `content call … EXPORT_LAYOUT_XML` |

> ⚠️ **订正**：最初测出"adb 能导出"时 adbd 处于 `adb root`（uid 0），我误记成"shell 也可以"。
> `adb unroot` 回到 uid 2000 复测 → **同样被拒**。
> 原因：`signatureOrSystem` 对 **uid 0 / SYSTEM_UID(1000)** 无条件放行，**shell(2000) 不在其列**。
> → **Shizuku 跑的就是 uid 2000，因此也救不了**（原文档的"推荐 Shizuku"是错的）。

### ✅ 增量合并已实现并实测

Provider 若发现 `<filesDir>/current-layout.xml`（= launcher 导出的当前布局）就切到**合并模式**：
**保留用户已有的文件夹分组与 dock 顺序**，只做「补新应用 / 清失效项 / 重排位置」。

实测（构造"Calendar 新装 + 一个已卸载的失效包"）：`【合并模式】保留文件夹 4 个, 新增 1 个应用, 清理失效 1 项`，
22 行落库 0 冲突；Calendar 被**自动补回用户原有的「效率」文件夹**，失效包被清理。

### 💡 产品形态建议（**已订正**）

| 形态 | 免 root | 能读布局 | 能增量 | 备注 |
|---|---|---|---|---|
| **纯免 root app** | ✅ | ❌ | ❌ | 只能整份重建（即我们已有的能力） |
| **app + Shizuku** | ✅ | ❌ | ❌ | **Shizuku = uid 2000，与 shell 同权限，被同一个权限拦住** |
| **app + root** | ❌ | ✅ | ✅ | `call(EXPORT_LAYOUT_XML)` 或直接读 `launcher.db`（路径②） |
| **app + 自我记录式增量** | ✅ | ❌（不需要） | ⚠️ 部分 | provider 记住自己上次生成的布局当基线；**感知不到用户手工改动** |

> **核心结论**：**"读当前布局"是 root 专属能力**。免 root（含 Shizuku）只能"整份重建"，
> 或退而求其次做「自我记录式增量」（保证重跑不丢**我们生成**的分组，但盖掉用户手工改动）。

**下一步建议**：
1. **别在 Shizuku 上投入**（已验证此路不通）。
2. ✅ **「自我记录式增量」已实现并实测通过**（免 root）：provider 每次输出后另存
   `<filesDir>/last-generated.xml`，下次以它当合并基线。实测：删掉基线里的 Calendar → 重跑 →
   判定为"新应用"并**自动补回用户原有的「效率」文件夹**；4 个文件夹 + dock 全保留。
   **代价**：感知不到用户的手工改动（会被下次规划覆盖）。
3. 若要"真保住用户手工改动"：只能以 **root** 为前提；否则产品文案上应明确"重排会覆盖手工布局"。

---

## 十六、P6 产出摘要：UI（2026-10-01）

**`notes/11-P6-ui.md`**：项目从「哑 provider」变成**带界面的 app**。

### 技术选型：WebView + 本地 HTML（零依赖）

手搓构建链（`aapt2 + d8 + apksigner`）下引入 AndroidX 代价极高，所以界面用
`WebView` 加载 `assets/ui.html`。好处：**零依赖**、`assets/` 本来就被打包、
**桌面网格预览用 CSS Grid 几行搞定**。APK：16.8 KB → **33.3 KB**。

### 架构（关键设计）

```
MainActivity(WebView) ── window.Android.* ──> ContentResolver.call(自己, "PLAN_JSON")
                                                        │
                                            复用 buildPlan() + render()（与真实导入同一份代码）
```
**UI 不自己算布局**，而是回头调同一个 app 里的 provider → **预览与实际导入永不不一致**。

新增 provider 方法：`PLAN_JSON`（规划快照）、`APPLY`（借 `su` 写 Secure Settings）。
另外 `openFile()` 会把 launcher 传来的网格**记进 SharedPreferences**，供 UI 预览使用。

### 界面 5 区

1. **概览**：launcher / 网格 / dock 数 / 应用总数 / 桌面槽位 / 占用屏幕 + 合并模式与基线
2. **桌面预览**：按屏绘制 CSS Grid（文件夹=堆叠小方块、图标、小组件；屏幕 0 第 0 行画成**斜纹「系统占用」**）
3. **应用清单**：应用名 + 包名 / 分类 chip / 去向 chip
4. **应用到桌面**：adb 命令（一键复制）+「尝试直接应用（需 root）」+ **生效条件与风险提示**
5. **布局 XML**：折叠查看/复制

### 实测

- 界面渲染正常（WebView 124），数据来自 `PLAN_JSON`（`gridKnown:true`、`mergeMode:true`）
- `APPLY` 在**非 root 设备上安全失败**（`Cannot run program "su": Permission denied`），**且不误改设置**
- 截图：`poc/artifacts/ui-5-final-top.png`、`ui-3-apps.png`、`ui-4-apply.png`

### 第二轮增强（2026-10-02）：四项全部落地

| 项 | 状态 | 说明 |
|---|---|---|
| **分类可编辑 / 可排除** | ✅ | 覆盖存 SharedPreferences（`ov:<pkg>` = `"<分类>|0或1"`）；provider 新增 `SET_OVERRIDE` / `CLEAR_OVERRIDES`；界面每行有分类下拉 + 排除开关 |
| **一键应用** | ✅ | 新增 `APPLY_FULL`：`su -c` 依次「写 Secure Settings → `pm clear <launcher>` → 回桌面」；**无 root 时提前返回、不执行后续命令**（实测安全失败，设置与数据均未被改动） |
| **真实应用图标** | ✅ | provider 把图标转成 `data:image/png;base64` 交给页面；文件夹预览与 Dock 复用 |
| **深色模式** | ✅ | 由 Java 读 `uiMode` 判定（WebView 的 `prefers-color-scheme` 在手搓 APK 下不可靠）→ JS 加 `.dark` 类切 CSS 变量 |

实测：点界面上的排除开关 → provider 记录 `[SET_OVERRIDE] com.google.android.documentsui -> bucket=工具, excluded=true` →
界面重绘且**「工具」文件夹从 3 个图标变 2 个**；`cmd uimode night yes` 后整站正确转深色。
新增截图：`ui-v2-top.png` / `ui-v2-apps.png` / `ui-v2-excluded.png` / `ui-v2-dark.png`。

### UI 侧剩余（可选）

- 图标是运行时生成（首屏稍慢），可改按需加载
- 分类下拉候选是固定词表 + 现有桶，不支持自定义新分类名
- **未在真机验证**（本机真机未接）
- 若要「完全一键且免 root」：接 **Shizuku** 可自动化「写设置 + 触发重建」这一步
  （Shizuku = shell 身份，**有** `WRITE_SECURE_SETTINGS`），但它**读不到**布局（见 §十五）

---

## 十七、P7：免 root 通道排查 + 三级应用策略（2026-10-02）

**`notes/12-免root通道调研.md`**：把「普通用户能不能用」这件事查到底。

### 四条通道逐一排查

| 通道 | 结论 |
|---|---|
| Play Auto Installs partner 布局 | ❌ **不通**：`Partner.findSystemApk()` 用 **`MATCH_SYSTEM_ONLY`**（必须系统分区应用）+ Lawnchair 编译期写死 `ENABLE_AUTO_INSTALLS_LAYOUT = false` |
| `LauncherProvider.call()` 导出/导入 | ❌ **root 专属**（`signatureOrSystem`，见 §十五） |
| 直接读写 `launcher.db` | ❌ 沙箱保护，shell / Shizuku 都读不到 |
| **Secure Settings** | ✅ **唯一可行**，但需 `WRITE_SECURE_SETTINGS` |

### 关键实测：**shell 能做完整流程**

```
uid=2000(shell)
$ adb shell pm clear app.lawnchair.nightly
Success          ← 2 秒
```
→ 写设置 ✅ + `pm clear` ✅ + `am start HOME` ✅ ⇒ **Shizuku 能实现真正的免 root 一键**。

### 已落地：三级应用策略

`applySecureSetting()`：① 直接写（需 `pm grant` 或 root）→ ② 借 `su` → ③ 返回 adb 命令提示。
`APPLY_FULL` 在写成功后继续 `pm clear <launcher>` + 回桌面。

**实测**：`adb shell pm grant com.example.layoutprovider android.permission.WRITE_SECURE_SETTINGS`
在 userdebug 上成功 → 之后「尝试直接应用」返回 **`OK（直接写入）`** 且设置确实写入。
（⚠️ `pm grant` 只在 userdebug/eng 有效；生产设备仍需 root 或 Shizuku。）

UI 已改为两条命令（仅写设置 / 一键全流程，launcher 包名动态填充）+ 三级策略说明。
截图：`poc/artifacts/ui-v3-apply.png`。

### ⚠️ 踩坑：模拟器「假失败」

排查中 `pm clear` 一直超时（111s → `Broken pipe`），一度误判为权限问题。
真因是**模拟器连续跑约 15 小时后 `system_server` 已崩**（`pm list packages` → `Can't find service: package`）。
**重启后同一条命令 2 秒成功。**
→ **教训：在模拟器上排查命令/权限失败前，先确认系统服务是活的。**

### 下一步（未实施）

**Shizuku 接入**：具体方案（AAR 处理 / manifest 合并 / 代码 / 测试难点）写在 `notes/12` §5。

---

## 十八、P8：Shizuku 接入 —— 免 root 一键打通（2026-10-02）

**`notes/13-P8-shizuku.md`**：**项目最重要的产品化突破** —— 授权一次 Shizuku 后，
app 内**一键**完成「写 Secure Settings → 重置桌面 → 回桌面」，**不需要 root、不需要 adb**。

### 集成要点（不用 Gradle）

- 5 个依赖全部从 AAR 抽 `classes.jar`（这些 AAR **无资源**）：`api` / `provider` / `aidl` / `shared` + `androidx.annotation`
- manifest 三处：`<uses-permission API_V23>` + `<meta-data V3_SUPPORT>` + **`<provider rikka.shizuku.ShizukuProvider>`**
- 执行命令走 AIDL：`IShizukuService.Stub.asInterface(new ShizukuBinderWrapper(Shizuku.getBinder())).newProcess(...)`

### 实测（全链路日志）

```
D ShizukuProvider: binder received
I LayoutProviderUI: Shizuku 授权结果 code=1001 result=0
I LayoutProviderUI: [Shizuku] sh -c settings put secure launcher3.layout.provider … && pm clear app.lawnchair.nightly && am start … HOME
I LayoutProviderUI: [Shizuku] 退出码=0
I LayoutProviderPoC: 规划结果: 共 19 个应用; 保留文件夹 4 个, 新增 1 个应用; … dock=4
```
`overlap` = 0。桌面截图 `poc/artifacts/ui-v8-launcher-after-shizuku.png`。

### 四个坑（`notes/13` §4）

1. `androidx.annotation` 在 **Google Maven**，不在 Maven Central
2. 给 Windows 版 `javac` 传 classpath 列表 **必须用 `:` 分隔**（MSYS 只转 `:` 列表；`;` 会被当成单个路径）
3. `Shizuku.newProcess` 在 13.x 是 **private** → 改走 AIDL
4. **必须声明 `rikka.shizuku.ShizukuProvider`**，否则 binder 永远收不到
5. 模拟器上 Shizuku **不能用 root 启动**（AOSP 的 `su` 是 `rwsr-x--- root:shell`，app 无权执行）→ 必须用
   `adb shell <APK 里的 libshizuku.so 路径>` 启动，之后 `ps` 里会出现 `shell ... shizuku_server`

### 现在的四种应用方式

| 方式 | 免 root | 需 adb | 一键 |
|---|---|---|---|
| ① 直接写（`pm grant`） | ✅ | 一次性 | ✅（仅 userdebug/eng） |
| ② 借 `su` | ❌ | ❌ | ✅（需 root） |
| ③ **Shizuku** | ✅ | 一次性（启动服务） | ✅ **推荐** |
| ④ adb 命令 | ✅ | 每次 | ❌ |

### 遗留

- Shizuku 设备重启后需重新启动服务（Shizuku 自身限制）
- **读布局仍不可行**（Shizuku = shell，读不到 `signatureOrSystem` 的 provider）→ 增量仍靠自我记录基线
- 未在真机验证

---

## 十九、P9：手动分组（勾选/搜索 → 一个文件夹）（2026-10-02）

**`notes/14-P9-manual-grouping.md`**：用户手动挑一批应用 → 桌面生成**一个文件夹**。

### 设计

| 层 | 做法 |
|---|---|
| **数据** | SharedPreferences：key `cf:<id>`，value `"<分组名>\t<pkg1,pkg2,…>"`；provider 新增 `SET_CUSTOM_FOLDER` / `DELETE_CUSTOM_FOLDER` |
| **规划** | 自定义分组**优先于一切自动逻辑**（全量 & 合并两种模式都生效）；成员从自动归类中排除；**< 2 个的分组跳过**（Launcher3 不允许单项文件夹） |
| **界面** | 新增第 4 区：名称输入 + **搜索框** + 可点击勾选的应用列表 + 「保存为文件夹」+ 已有分组（编辑/删除） |

**搜索勾选的关键**：选择状态存在 JS 的 `CF_SEL`，**与过滤条件解耦** ——
可以「搜 A 勾几个 → 换词搜 B 再勾几个 → 一起保存」。

### 实测

```
[SET_CUSTOM_FOLDER] 我的分组 <- photos, youtube.music, youtube
规划结果: 共 20 个应用; … 桌面 文件夹4 + 单图标2 = 6 个槽位, 占 1 屏, 手动分组 1 个; dock=4
```
`overlap` = 0。桌面截图 `poc/artifacts/ui-v12-launcher-cf.png`：**「我的分组」文件夹真实出现在桌面第一位**，
原来的「影音」文件夹因成员被全部收走而自动消失。

### 边界

- 一个应用只能属于一个手动分组（先到先得）
- 自定义分组总排在最前（顺序 = 创建顺序），暂不支持指定位置
- 不支持嵌套（Launcher3 本身也不支持）

### 9.1 快捷全选（同日补）

界面加了**分类 chips**（`全选 20 个` / `其他 5` / `效率 4` / `工具 4` / `社交 3` / `影音 3` / `出行 1`）
与「**全选搜索结果**」按钮：点一下即可选中某个分类的全部应用，不必逐个勾。
选择状态仍与搜索过滤解耦。截图 `poc/artifacts/ui-v13-chips.png`、`ui-v14-chips-selected.png`。

---

## 二十、功能扩展路线图（`notes/15`，2026-10-02）

**核心判断**：当前产品形态的本质是「**一次性整理器**」——所有能力都发生在"用户主动打开并点应用"的那一刻。
三个根本短板：**决策依据单薄**（不知道谁重要）、**完全手动**（装新应用不会更新）、**不可逆**（不敢按）。

### 三根支柱

| 支柱 | 要解决 | 主要功能 |
|---|---|---|
| **决策质量** | 放得对不对 | **使用频率驱动**（Dock/首屏/文件夹内排序）、首屏策略可配、**Dock 可配置**、文件夹命名与排序 |
| **自动化** | 要不要手动 | **新装应用归位**（提示+一键，而非静默）、多套方案 Profile、定时整理 |
| **可逆性** | 敢不敢按 | **变更 Diff 预览**、快照与回滚、配置导入导出 |

### 优先级建议

| 优先级 | 功能 | 理由 | 成本 |
|---|---|---|---|
| **P0** | **使用频率驱动** | 唯一的**质变**项：从"分类"到"习惯"；**已实测免 root 可行** | 中 |
| **P0** | **变更 Diff 预览** | 成本最低、收益最直接 | 低 |
| **P1** | **Dock 可配置** | 复用现成勾选 UI | 低 |
| **P1** | **快照与回滚** | 与 Diff 配套成闭环 | 低 |
| **P1** | **多套方案 Profile** | 从工具到产品 | 中 |
| **P2** | 新装应用归位（A 档：提示+一键） | 自动化最小可用形态 | 中 |
| **P2** | 配置导入/导出 | 换机 / 分享 | 低 |
| **P3** | 首屏策略 / 文件夹命名 / 网格向导 / 厂商 ROM 验证 / Nova | 锦上添花或需真机 | 低-高 |

**建议的第一步组合：`使用频率驱动` + `Diff 预览` + `Dock 可配置`。**

### 明确不做

静默自动重排（用户失去控制感 + 频繁 `pm clear` 有风险）、云端同步/账号、主题美化、无障碍模拟拖拽、AI 分类
（分类准确度不是瓶颈，**排序质量**才是）。

> **关键实测（支撑 P0）**：`appops set <pkg> GET_USAGE_STATS allow` 生效
> —— 之前对我们 app 失败只是因为 manifest 没声明 `PACKAGE_USAGE_STATS`。
> 用户侧走「设置 → 特殊应用权限 → 使用情况访问」，**免 root 可得**。
> ⚠️ 但模拟器/新机无使用数据 → **必须做好降级**（无数据时回退到当前逻辑）。

---

## 二十一、P10：功能扩展实施计划 + 重构（第 0–1 步 ✅）

**`notes/16-P10-实施计划与重构.md`**（完整计划）；路线图见 `notes/15`。

### 已确认的三个决策

| 决策 | 结论 |
|---|---|
| 重构深度 | **彻底拆** |
| Dock 默认行为 | **保持现状**（硬编码优先级），新增 `usage`/`manual` 模式供主动切换 |
| 真机验证 | **暂时跳过** |

### 第 0 步：回归基线 ✅

不靠"重跑看看没坏"，而是**抓取重构前后的生成 XML 做逐字节比对**。
工具 `poc/tools/capture-golden.sh`（走只读的 `PLAN_JSON`，可反复抓取）。

| 模式 | 前 | 后 | MD5 |
|---|---|---|---|
| full | 2929 B | 2929 B | **一致** |
| merge | 2929 B | 2929 B | **一致** |

### 第 1 步：重构 ✅

```
LayoutProvider.java  ← 只做 ContentProvider 契约（1248 行 → 约 430 行）
LayoutPlanner.java   ← 纯规划：① 枚举 → ② 分类 → ③ 成组 → ④ 占格 → ⑤ 渲染 XML
AppConfig.java       ← 全部配置收口（调试开关 / 覆盖 / 手动分组 / 网格）
```
依赖单向：`LayoutProvider → LayoutPlanner → AppConfig`。
**存储格式暂未改动**（仍 `ov:` / `cf:` / 平铺 key），JSON 化 + Profile 留到第 7 步。

**验证双保险**：
1. golden XML 逐字节比对（full / merge）→ **MD5 完全相同**
2. **`openFile` 端到端**（golden 比对走的是 `PLAN_JSON`，**没覆盖**这条路径）→
   `收到布局请求` → `网格 4x6` → 合并模式规划 → **overlap = 0** → 落库 24 行、
   文件夹 `我的分组 / 效率 / 工具 / 其他` ✅

### 后续步骤

| 步 | 内容 | 状态 |
|---|---|---|
| 2 | `UsageRank`（频率读取 + 缓存 + 三层降级） | ✅ |
| 3 | **频率驱动排布** + 权限引导 | ✅ |
| 4 | **变更 Diff 预览** | ✅ |
| 5 | **Dock 可配置**（抽通用 picker） | ✅ |
| 6 | **快照与回滚** | ✅ |
| 7 | **多套方案 Profile**（配置 JSON 化 + 迁移） | ✅ |
| 8 | **新装应用归位**（receiver + 提示 + 一键） | ✅ |
| 9 | **配置导入导出** | ✅ |
| 10 | P3 小项：首屏策略 / 文件夹命名 / 网格向导 | ✅ |

---

## 二十二、P10 第 2–3 步：使用频率驱动（2026-10-02）

**`notes/17-P10-使用频率驱动.md`** —— 让排布依据从"属于哪个分类"升级为"**你实际上最常用什么**"。

### 第 2 步：`UsageRank`

- 打分：`近 7 天时长 × 1.0 + 近 8–30 天时长 × 0.4`
- **三层降级**（任何一层不满足都安全退回）：未授权 → `未授权`；已授权无记录 → `无数据`；
  有记录 → `可用`。`buildPlan` 拿到后再判一次 `available()`，**双保险**
- 缓存：内存 + `usage-cache.txt`，TTL 30 分钟（查询 200–800ms）
- **只在真正用到时才查**（默认配置一次查询都不做）
- 权限检查用 **appop**（`OPSTR_GET_USAGE_STATS`）而非 `checkPermission`；manifest 加
  `PACKAGE_USAGE_STATS`；用户侧走「设置 → 特殊应用权限 → 使用情况访问」，**免 root**

### 第 3 步：三处策略

| 开关 | 默认 | 作用点 |
|---|---|---|
| `dockMode` | `auto` | Dock 候选顺序 |
| `folderSort` | `name` | 文件夹内成员顺序 |
| `screenStrategy` | `balanced` | 槽位顺序（常用优先进首屏） |

**默认值下这些函数是 no-op** → 默认行为逐字节不变。

### 实测（用 `monkey` 反复启动造真实记录：Chrome×12 / Photos×6 / Settings×3 → 245 个应用 status=可用）

| 对比项 | 默认 | 按频率 |
|---|---|---|
| Dock | Phone/Messages/Chrome/Camera | **SpiritPal/Maps/Shizuku/Chrome** |
| 首屏槽位 | 我的分组/其他/效率/工具/Contacts/Maps | 我的分组/**工具/效率/社交/其他** |
| 「工具」文件夹内 | Camera/Clock/Files/Settings | **Settings/Camera/Clock/Files** |

**默认模式回归**：full / merge 与重构前**仍逐字节一致**（`f9214027…` / `44e59d51…`）。

> ⚠️ **合并模式下 Dock 变化不明显**是设计使然 —— 合并会优先保留用户已有的 dock，只有空位才补齐。

### ⚠️ 踩坑：回归工具有个隐蔽缺陷

`capture-golden.sh` 早期版本靠「把 `last-generated.xml` 挪走」强制全量模式 —— **这需要 root**。
它之前能工作只是因为 **adbd 恰好还是 root**（拉数据库时留下的）；我在另一步 `adb unroot` 后，
工具**静默失效**（`cd` 失败 → `&&` 短路 → 全量模式没生效）。

**修法**：① provider 加 `PLAN_JSON --extra forceFull:b:true`（免 root、确定性）；
② 脚本末尾**断言**实际 `mergeMode` 与预期一致，不一致就非零退出。

> **教训**：验证工具本身也需要断言。看起来在工作、实则什么都没测的脚手架最危险。

### 数据质量

`usageTop` **只列启动器应用** —— 否则会把开发期间长时间在前台的自家 app / launcher 也列出来
（实测模拟器 top1 是 `com.example.layoutprovider` 64623s），对用户是噪音。

### 遗留

- 合并模式下 Dock 不受频率影响（设计使然）；是否提供"即使合并也重排 dock"的选项待定
- 缓存 TTL 30 分钟 → 使用习惯变化最长 30 分钟后才反映
- 未在真机验证

---

## 二十三、P10 第 4–5 步：变更预览 + Dock 可配置（2026-10-02）

**`notes/18-P10-变更预览与Dock配置.md`**

### 第 4 步：变更预览（`LayoutDiff`）

**关键设计：只比容器，不比坐标。** 一旦新增一个应用，后面所有图标的 `(x,y)` 都会平移 ——
按坐标比对会得出"几乎所有东西都移动了"这种无用结论。位置统一描述为
`dock` / `desktop` / `folder:<名>`，只报**容器变化**。

四类：**新增 / 移除 / 移动 / 保留**。UI 明确标注基线是"本应用上次生成的布局"
（不是 launcher 真实布局，免 root 读不到）。

### 第 5 步：Dock 可配置

`dockMode` 增加 `manual`：用户指定（有序，最多 `hotseatSize`）。
存储 `AppConfig.manualDock`，新增 `call()` `SET_MANUAL_DOCK`，
UI 是一排**按点选顺序编号**的应用 chips（`1. Chrome` …），超限会拦下提示。

### ⚠️ 截图暴露的 UX 矛盾（已修）

第一版做完截图发现：**设了手动 Dock，预览里 Dock 还是旧的 4 个**。
原因：合并模式**优先保留基线 dock**，旧 dock 占满 4 位后 `dockCandidates` 没机会生效。
但用户既然**显式指定**，就不该被旧值覆盖 —— 否则用户会以为功能坏了。

**修法**：只有 `dockMode == "auto"` 时才保留基线 dock。

> **教训**：这个 bug 是**看截图**发现的，不是看日志 —— 日志里 `dockMode=manual` 与
> `SET_MANUAL_DOCK` 都是成功的，只有把「设置」和「预览结果」放同一屏对照，矛盾才显形。
> **所以每步都截图不只是留证，它本身就是一种验证手段。**

### 实测

| 项 | 结果 |
|---|---|
| Dock 手动 | 设 `Chrome/Camera/Photos/YouTube` → `dock 实际 = ['Chrome','Camera','Photos','YouTube']`（顺序一致） |
| 变更预览 | `移动 3 / 保留 17`；示例 `Messages：Dock → 「社交」`、`Phone：Dock → 「社交」`、`Contacts：桌面 → 「社交」` |
| 合并模式下手动 Dock | `mergeMode=True` 下仍生效 ✅（修复后） |
| **默认模式回归** | full / merge **仍逐字节一致** ✅ |

### 遗留

- Diff 不报坐标级移动（设计使然）
- **应用前的确认弹窗还没接**（现在只在卡片里展示）—— 真正落地时点「应用」应弹一次确认
- 未在真机验证；第 6–10 步待做

---

## 二十四、P10 第 6–7 步：快照回滚 + 多套方案（2026-10-03）

**`notes/19-P10-快照回滚与多套方案.md`**

### 第 6 步：快照与回滚（`Snapshots`）

- 每次 `openFile` 成功后另存 `<filesDir>/snapshots/<ts>.xml`，**最多 5 份**；
  **内容相同则跳过**（避免连点两次产生重复）
- `RESTORE_SNAPSHOT` = 写回 `last-generated.xml`，下次生成即以它为基线
- ⚠️ 两条必须说明的限制（已写进 UI）：
  1. 快照**只含本应用生成过的布局**，不含手工拖动（免 root 读不到 launcher 真实布局）
  2. **回滚的是「布局」不是「配置」** —— 之后改过 Dock/分类设置的话，会按**当前设置**重新生成

**实测**：第 1 次导入 → 1 份快照；XML 相同的第 2 次导入 → **仍 1 份**（去重生效）；
换成完全不同的 Dock → **2 份**；回滚到旧快照 → `current` 标记转移、基线切换 ✅

### 第 7 步：多套方案 Profile

**关键取舍：放弃「key 命名空间化 + 迁移」，改用「方案 = 配置快照，切换 = 写回平铺 key」。**
后者完全不动现有存储布局，**零迁移风险**（前者要重写整个配置存储层，是 10 步里风险最高的一处）。

- `AppConfig.toJson()` / `applyJson()`，序列化用 Android 内置的 `org.json`
- 方案含：Dock 设置 / 排序策略 / 分类覆盖 / 手动分组。**不含**：网格（设备属性）、调试开关、快照
- 新增 `call()`：`PROFILE_LIST` / `PROFILE_SAVE` / `PROFILE_LOAD` / `PROFILE_DELETE`

**实测**：存「工作」→ 改成 usage/常用优先/清空 Dock → 存「极简」→ 切回「工作」→
`dockMode/folderSort/screenStrategy/manualDock` **完整还原** ✅

### ⚠️ 关于回归信号的一个重要澄清

本轮 `merge` 与 `golden-before` 出现差异，逐行核对后确认**不是回归**，而是
**我测试时改动了基线本身**（回滚过快照）。

> **`merge` 的输出依赖当前 `last-generated.xml`，是有状态的；`full`（`forceFull=true`）与状态无关。**
> 所以**日常回归应以 `full` 为准**。要让 merge 也可复现，得先用 `RESTORE_SNAPSHOT` 固定基线。

本轮 `full` 仍**逐字节一致** ✅。

### 遗留

- 方案切换是**写入式**的（改配置后需再保存才更新方案）；不做"自动同步"以免混淆语义
- 方案不支持重命名（可另存再删）
- **第 9 步（配置导入导出）现在几乎免费** —— `toJson()`/`applyJson()` 已就位，只差文件读写与分享
- 未在真机验证；第 8–10 步待做

---

## 二十五、P10 第 8–10 步完成 → **P10 全部结束**（2026-10-03）

**`notes/20-P10-待归位与导入导出.md`**（含 P10 整体收尾）

### 第 8 步：待归位（`PendingApps`）

**一处设计改动：不加 `PACKAGE_ADDED` 广播接收器。** 按既定原则不做静默重排、也不发通知 ——
用户不打开 app 时接收器什么也做不了。既然提示只在 app 内，**按需比对**就够了，
还少一个"广播没收到就静默失效"的故障面。

- `openFile` 成功后把当时的应用集合记为 `knownApps`（那份布局已含全部应用 = 已归位）
- UI 打开时对比当前集合 → `added` / `removed` → 顶部横幅 +「去应用（归位）」
- 首次运行直接记为已知，**避免"全部应用都是新装的"误报**

**实测**（卸载→导入→重装 Shizuku）：卸载 → `移除=[shizuku]` ✅；重装同包名 → 空（**正确**，布局里本来就有）；
卸载+导入+重装 → `新增=['Shizuku']` ✅。

### 第 9 步：配置导入导出

**取舍：用剪贴板，不用文件。** 分享文件要 `FileProvider`（需 `res/xml/`），而本项目手搓构建、目前无资源目录；
配置 JSON 只有 ~300 字节。→ 导出=复制到剪贴板，导入=从剪贴板读。**零依赖、零权限。**

实测：导出 318 字节 JSON；改一份再导入 → `RESULT=OK`，配置正确变更 ✅。
（`applyJson` 已在第 7 步的方案切换中验证过，导入导出复用的就是它。）

### 第 10 步：P3 小项

| 项 | 实测 |
|---|---|
| **网格向导**（4 组常用网格 chips） | 设 5×6/dock5 → `gridKnown=True` ✅ |
| **分类重命名**（`bucketNames`） | 「效率」→「工作」→ 文件夹标题变「工作」✅ |
| 首屏策略 | 已在第 3 步完成 |

分类重命名只作用于**文件夹标题**；应用清单的下拉仍用原名，避免"改名"与"改分类"混淆。

### 回归

**full 模式仍逐字节一致** —— 10 步功能全部加完，默认配置下输出零变化。

### P10 收尾

| 步 | 内容 | 状态 |
|---|---|---|
| 0 | 回归基线（golden + 模式断言） | ✅ |
| 1 | 彻底重构（`LayoutPlanner`/`AppConfig`） | ✅ |
| 2–3 | 使用频率驱动 | ✅ |
| 4–5 | 变更预览 + Dock 可配置 | ✅ |
| 6–7 | 快照回滚 + 多套方案 | ✅ |
| 8 | 待归位检测 | ✅ |
| 9 | 配置导入导出 | ✅ |
| 10 | 网格向导 + 分类重命名 | ✅ |

**代码：1 个 1248 行的文件 → 9 个职责单一的文件。APK：16.8 KB（P2）→ 78.4 KB（P10）。**

### 整个 P10 层面的遗留

- **未在真机验证** —— 真机上 Shizuku 走无线调试；**厂商 ROM 是否保留该通道完全未知，这是最大的产品风险**
- **不支持 Nova 等非 Launcher3 启动器**（要无障碍，成本极高）
- 方案切换是"写入式"；快照只含布局不含配置；Diff 不报坐标级移动 —— 均为有意取舍，已分别记录
- 应用图标运行时生成，首屏稍慢，可改按需加载

---

## 二十六、开源发布 + 构建脚本可移植化（2026-10-08）

### 发布

项目此前**从未 git 化**（无 `.git`，无任何提交），GitHub 上也无对应仓库。现已建公开仓库并完成首次提交：

- 仓库：https://github.com/ReSerendipity/Android-IconAutoArrange （public，默认分支 `main`）
- 首次提交 `f1614ea`：150 文件 / 19.66 MB / 9,536 行新增
- 校验：本地 `HEAD` == `origin/main`，**tree 哈希一致**（`e7a05a1a…`）→ 远程与本地逐字节相同
- 许可：**Apache-2.0**（新增 `LICENSE`，含官方全文；README 补「许可与第三方组件」表）
- 新增 `.gitignore`：排除 `references/`（上游克隆）、`poc/apks/`（第三方 APK）、
  `poc/out/`、`poc/debug.keystore`（build.sh 可重新生成）、`poc/artifacts/pj-*.txt` 等临时转储
- 新增 `.gitattributes`：`*.sh text eol=lf` —— **本机全局 `core.autocrlf=true`**，
  不加会让脚本被检出成 CRLF，在 Git Bash 下执行失败
- 未纳入：2698 文件 / 67.93 MB（references 2652 + apks 2 + out 21 + artifacts 转储 23）

### 构建脚本可移植化

原 `build.sh` / `capture-golden.sh` 硬编码了 `C:\Users\Doro\...` 的本机路径，
**公开仓库里别人无法构建**。已改造为自动探测：

| 项 | 探测顺序 | 覆盖方式 |
|---|---|---|
| SDK | `ANDROID_SDK_ROOT` → `ANDROID_HOME` → 各平台常见默认位置 | 环境变量 |
| build-tools | 首选 35.0.0 → 否则取最新可用版本 | `BT_VERSION` |
| platform | 首选 android-35 → 否则取最新 | `TARGET_API` |
| python | `python3` → `python` → `py` | `PYTHON` |
| adb | `ADB` → SDK `platform-tools/adb[.exe]` → PATH | `ADB` |
| 设备 | 留空 = 自动选唯一设备；多设备必须显式指定 | `DEV="-s <serial>"` |

build-tools 工具后缀也做了跨平台处理（Linux/macOS 无后缀，Windows 为 `.exe` / `.bat`）。

**验证**（改造前后 `classes.dex` 与 APK 大小**完全一致** → 纯可移植性改造，零行为变化）：

| 用例 | 结果 |
|---|---|
| 不设任何环境变量 | ✅ 自动探测到 SDK / 35.0.0 / android-35 / python3，构建成功 |
| `classes.dex` md5 | ✅ `b1816c64befcbf53b9d28672970e160d`（与改造前一致） |
| `BT_VERSION=99.0.0`（不存在） | ✅ 降级到最新 36.1.0 |
| `TARGET_API=99`（不存在） | ✅ 降级到 android-36.1 并打印告警 |
| SDK 路径不存在 | ✅ 明确报错并提示设置 `ANDROID_SDK_ROOT` |
| `capture-golden.sh` 成功路径 | ✅ full / merge 模式断言均 OK |
| 多设备且 `DEV` 留空 | ✅ 列出设备并要求显式指定 |

> 顺带修掉一个自己写出来的缺陷：初版预检在**多设备**时报「没有可用设备」，
> 信息不准确 —— 已改为区分「0 台」与「多台」两种情况。

### 真机情况（新变化）

复核时发现**真机已接入**：`realme RMX5010`，Android 16（SDK 36）。
这是 §二十五 里「未在真机验证」那项遗留的**唯一阻塞点**，现在具备验证条件 ——
但尚未执行（真机未装本 app 与 Lawnchair；且会改动真实桌面，需先确认）。

---

## 十、复核记录（2026-10-01 00:01）

- **模拟器镜像**：`system-images;android-35;google_apis;x86_64`（rev 9）**已下载完成**，可直接用于 P2 建 AVD。目录：`C:\Users\Doro\AppData\Local\Android\Sdk\system-images\android-35\google_apis\x86_64\`。
- **已装镜像清单**：`android-26`、`android-35`。
- **已有 AVD**：仅 `GalgameApi26`（API 26，**验证不了路径⑤**）→ P2 需新建 API 35 AVD。
- **冗余目录体积**：两份副本各 363MB，合计 **约 726MB**（见「八、已知坑」第 1 条）。
- **清理结果（2026-10-01 00:31）**：经用户确认，两份副本已**走回收站**删除完毕；主项目 `references/` 4 仓库 `.git` 复核完好，项目体积现为 **363MB**。桌面只剩主项目 `Android-IconAutoArrange`。
  - 技术要点：`DeleteDirectory` 整树回收会因 `.git` 内**只读 pack 文件**抛 `UnauthorizedAccessException` 并中断；改用「清 `Attributes` → 自底向上逐文件 `DeleteFile` + 逐目录 `DeleteDirectory` → 循环重跑」才清干净（回收是异步的）。
