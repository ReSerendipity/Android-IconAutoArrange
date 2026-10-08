# AndroidIconAutoArrange

安卓端「图标自动排列 / 批量移动」实现路径研究项目。

> 创建：2026-09-30
> 性质：学习 / 参考实现收集，非可直接发布的成品

---

## 背景

跨平台图标管理工具调研（见本机 WorkBuddy 工作区 `跨平台图标管理开源工具调研汇总.md`）的结论：

- **Windows 桌面端已有成熟开源项目**（DeskBox、DesktopFramesPlus 等），不再重复造轮子。
- **安卓端「自动排列 / 批量移动图标」的独立开源工具几乎空白**，现成能力主要靠启动器内置。
- 因此本项目**聚焦安卓端**，收集可参考的实现，验证可行的技术路径。

## 关键认知

安卓**没有公开 API** 让第三方重排桌面图标。图标位置存储在各启动器（Launcher）各自的私有数据库中。因此「自动排列」只能通过以下四条路径之一实现：

| # | 路径 | 授权要求 | 可靠性 | 速度 | 备注 |
|---|---|---|---|---|---|
| ① | AccessibilityService + 模拟手势 / 拖拽 | 仅开启无障碍（无 root） | 中-低（脆弱） | 慢 | 降级方案，文件夹处易失败 |
| ② | Shizuku / root 直读写 `launcher.db` | Shizuku 或 root | 高 | 瞬时 | **产品主方案**（Launcher3 的 `favorites` 表） |
| ③ | 解析启动器备份文件（JSON/XML） | 无 | 高 | 瞬时 | 依赖具体启动器格式 |
| ④ | 自建启动器 | 设为默认桌面 | 最高 | 自定 | 工作量极大 |

**LLM 的定位**：仅作可选的「语义分组」规划层（如「工作类放一起」），核心排列算法（字母 / 类别 / 网格蛇形填充）应为确定性逻辑，不依赖 LLM。

## 目录结构

```
AndroidIconAutoArrange/
├── README.md
└── references/
    ├── android-app-organizer/      # LLM 辅助布局规划 + 自动化整理（Kotlin, MIT）
    ├── android-folderautomanager/  # 自动文件夹管理雏形（Kotlin）
    ├── novalaunchereditor/         # Nova Launcher 备份文件查看/编辑（TypeScript）
    └── lawnchair/                  # 开源启动器，布局 / schema 权威参考（Kotlin，稀疏检出核心目录）
```

## 各参考项目看点

### android-app-organizer（device-kunkun）
`An Android app organization experiment using Python automation, an on-device helper, and LLM-assisted layout planning.`
- **最有参考价值**：走的是「LLM 规划布局 + on-device helper 自动化执行」的路线。
- 重点看：执行层到底用无障碍、Shizuku 还是别的；LLM 与确定性算法如何分工。

### android-folderautomanager（hekizoglu）
`AppOrganizer - Android app organizer with auto folder management`
- 直接对标「自动归类到文件夹」的雏形实现。

### novalaunchereditor（SamLeatherdale）
`Allows for viewing and editing of Nova Launcher backup files.`
- 参考「解析启动器备份格式」路径③：Nova 备份文件的结构与坐标字段。

### lawnchair（LawnchairLauncher）
开源启动器标杆，用于理解**路径②的数据库层**。
- **重点看 `schemas/`**：Room 数据库 schema，含桌面布局表（`favorites`）的字段定义（`itemType / container / screen / cellX / cellY / spanX / spanY` 等）。
- `src/`、`lawnchair/`、`shared/`：布局加载与 LauncherModel 逻辑。
- 注：本仓库全量约 929MB，已用 `--filter=blob:none --depth=1 --sparse` 只检出上述核心目录。

## 精读笔记（`notes/`，已完成）

| # | 笔记文件 | 核心结论 |
|---|---|---|
| 01 | `notes/01-android-app-organizer.md` | PC 端 Python + UIAutomator2(adb)；「快照规划 + UI 自动化执行」混合；长按 1.45s 进编辑模式 |
| 02 | `notes/02-android-folderautomanager.md` | **自建 launcher（路径④）**；8 级分类优先级链；`ApplicationInfo.category` 免费信号；DeepSeek fallback |
| 03 | `notes/03-novalaunchereditor.md` | `.novabackup` = zip(SQLite+XML)；favorites 字段语义；探测式 schema 兼容 |
| 04 | `notes/04-lawnchair-launcher3-db.md` | Launcher3 `favorites` 表 19 列权威定义；container 常量全表；官方「布局提供者」机制 |
| 05 | `notes/05-launcher3-layout-provider.md` | **第五条路径**：官方布局导入/导出通道（XML + BlobStore/ContentProvider + `launcher3.layout.provider`，adb 即可） |
| 06 | `notes/06-launcher3-layout-xml-format.md` | **P1 产出**：`<workspace>` / `<favorites>` 两套 XML 格式全表 + 完整样例 + raw-XML 陷阱 |
| 07 | `notes/07-PoC-layout-provider.md` | **P2 产出**：路径⑤ 端到端实测成功（Pixel Launcher + Lawnchair）+ 三条实测教训 |
| 08 | `notes/08-P3-layout-planner.md` | **P3 产出**：自动枚举 + 官方分类成组 + 网格排布（18 应用 → 22 行落库，0 冲突） |
| 09 | `notes/09-P4-widgets-and-multipage.md` | **P4 产出**：占位网格 / appwidget ✅ / 多页 ✅ / `searchwidget` 必然 NPE ❌ |
| 10 | `notes/10-P5-export-import-and-merge.md` | **P5 产出**：launcher 的 `EXPORT/IMPORT_LAYOUT_XML` 接口 + 权限边界实测 + 增量合并 |
| 11 | `notes/11-P6-ui.md` | **P6 产出**：WebView 界面（应用清单 / 桌面预览 / 应用入口），零依赖实现 |
| 12 | `notes/12-免root通道调研.md` | **P7 产出**：四条免 root 通道排查结论 + 三级应用策略（含 Shizuku 接入方案） |
| 13 | `notes/13-P8-shizuku.md` | **P8 产出**：Shizuku 接入 —— **免 root 一键应用打通**（含 5 个坑） |
| 14 | `notes/14-P9-manual-grouping.md` | **P9 产出**：**手动分组** —— 勾选/搜索应用 → 生成一个桌面文件夹 |
| 15 | `notes/15-功能扩展路线图.md` | **下一步规划**：三根支柱（决策质量 / 自动化 / 可逆性）+ 优先级建议 |
| 16 | `notes/16-P10-实施计划与重构.md` | **P10 计划**：7 项功能的实施计划 + 第 0–1 步（重构）完成记录 |
| 17 | `notes/17-P10-使用频率驱动.md` | **P10 第 2–3 步**：使用频率（三层降级）+ 三处排布策略 |
| 18 | `notes/18-P10-变更预览与Dock配置.md` | **P10 第 4–5 步**：变更 Diff 预览 + Dock 手动指定 |
| 19 | `notes/19-P10-快照回滚与多套方案.md` | **P10 第 6–7 步**：快照回滚 + 多套方案 Profile |
| 20 | `notes/20-P10-待归位与导入导出.md` | **P10 第 8–10 步**：待归位 + 配置导入导出 + P3 小项（含 P10 收尾） |

## 构建与运行

**前置**：JDK（`javac` / `keytool` 在 PATH）、Android SDK（含 build-tools 与 platform）、Python 3。

```bash
cd poc
bash build.sh          # 产出 poc-layoutprovider.apk
```

脚本**不依赖 Gradle / AGP**，只用 SDK 自带的 `aapt2 + d8 + zipalign + apksigner`。
SDK 路径与版本自动探测，可用环境变量覆盖：

| 变量 | 作用 | 默认 |
|---|---|---|
| `ANDROID_SDK_ROOT` / `ANDROID_HOME` | SDK 根目录 | 自动探测常见位置 |
| `TARGET_API` | 目标 API（同时决定首选 platform） | `35` |
| `BT_VERSION` | 首选 build-tools 版本 | `35.0.0` |
| `PYTHON` | python 解释器 | `python3` → `python` → `py` |

安装并用 adb 触发一次布局导入（以 Lawnchair 为例）：

```bash
adb install -r poc/poc-layoutprovider.apk
adb shell settings put secure launcher3.layout.provider com.example.layoutprovider
adb shell pm clear app.lawnchair.nightly
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

也可以直接打开 app，在界面里点「应用」——授权 Shizuku 后**免 root 一键**完成上述全部步骤。

> ⚠️ 前提：设备上的启动器属于 **Launcher3 系**（Pixel Launcher / Lawnchair）。
> **Nova 等第三方启动器不支持**。

回归基线抓取（`full` / `merge` 两种模式，末尾带模式断言）：

```bash
bash poc/tools/capture-golden.sh <输出目录>
# 多设备时显式指定：DEV="-s <serial>" bash poc/tools/capture-golden.sh <输出目录>
```

## 待办

- [ ] 验证 Shizuku 读写 `launcher.db` 的可行性（改字段 → 重启 launcher → 生效）
- [x] PoC：通过官方布局通道整份导入布局（ContentProvider + Secure Settings）→ 见 `notes/07`、`poc/`
- [x] **P3**：布局规划算法（按分类自动选 App + 网格排布）→ 见 `notes/08`、`poc/.../LayoutProvider.java`
- [x] **P4**：占位网格 + `appwidget` / 多页实测 → 见 `notes/09`（并发现 `searchwidget` 必然 NPE）
- [x] 产品化：导出 → 合并 → 再导入（**增量合并已实现并实测**，见 `notes/10`）
- [x] 验证"读布局"的权限边界：**root 专属**（普通 app ❌ / shell·Shizuku ❌）
- [x] **自我记录式增量**（免 root 折中：provider 用上次输出当合并基线）——已实现并实测
- [x] **P6：UI**（WebView：应用清单 + 桌面预览 + 应用入口）——见 `notes/11`、`poc/assets/ui.html`
- [x] UI 增强：**分类可编辑 / 应用可排除 / 真实图标 / 深色模式 / 一键应用（root）**
- [x] **免 root 通道排查**：四条通道逐一验证（详见 `notes/12`）；落地三级应用策略
- [x] **P8：接入 Shizuku** —— **免 root 一键应用打通**（见 `notes/13`、`poc/src/.../MainActivity.java`）
- [x] **P9：手动分组** —— 勾选/搜索应用 → 生成一个桌面文件夹（见 `notes/14`）
- [x] **P10 全部 10 步完成**：重构 + 使用频率 + 变更预览 + Dock 可配 + 快照回滚 + 多套方案 + 待归位 + 导入导出 + P3 小项
- [ ] **未在真机验证** —— 真机 Shizuku 走无线调试；**厂商 ROM 是否保留该通道未知，是最大产品风险**
- [ ] 真机验证（本机真机未接；真机上 Shizuku 走无线调试，流程与模拟器不同）
- [x] 深挖 Launcher3 官方「布局提供者」通道（`launcher3.layout.provider` + `blob://`）→ 见 `notes/05`、`notes/06`
- [ ] 评估是否需要补充克隆 KISS / Kvaesitso（其他开源启动器参考）

## 许可

本项目以 **Apache License 2.0** 发布，见 [`LICENSE`](LICENSE)。

### 第三方组件

| 组件 | 用途 | 许可 | 是否随仓库分发 |
|---|---|---|---|
| **Shizuku SDK**（`dev.rikka.shizuku`：`api` / `provider` / `aidl` / `shared`） | 免 root 借 shell 身份执行命令 | Apache-2.0 | ✅ 是（`poc/shizuku/`，构建必需） |
| **Lawnchair**（Launcher3 fork） | 布局通道 / XML 格式 / 数据库 schema 权威参考 | Apache-2.0 | ❌ 否（`references/` 已排除） |
| **AOSP Launcher3** | 上游实现来源 | Apache-2.0 | ❌ 否 |
| **android-app-organizer** | 布局规划思路参考 | MIT | ❌ 否（`references/` 已排除） |
| **android-folderautomanager** | 自动归类思路参考 | 未声明 | ❌ 否 |
| **novalaunchereditor** | 备份文件格式参考 | **未声明**（保留所有权利） | ❌ 否 —— **仅参考思路，未复制任何代码** |

> `references/` 与 `poc/apks/` 因体积与上游版权原因**不纳入本仓库**，
> 需要时请自行克隆 / 下载（见上文「目录结构」与各笔记中的来源标注）。
