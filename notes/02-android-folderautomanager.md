# 02 · android-folderautomanager 源码精读笔记

> 仓库：hekizoglu/android-folderautomanager（应用名 AppOrganizer）
> 语言：Kotlin（Jetpack Compose + Room + Hilt + DataStore）
> 规模：主源码数十个 Kotlin 文件、约 2 万行；**1449 个单元测试** + Robolectric 视觉测试 + Maestro 端到端
> 精读日期：2026-09-30
> 一句话：**它不是"整理系统桌面"的工具，而是一个"自建 launcher"**——用户设为默认桌面后，它用一套 **8 级优先级分类器**把全部应用自动归类，并直接渲染成分文件夹的桌面网格。对应我们讨论的**路径④：自建 launcher**。

---

## 0. 最重要的结论：它走的是「路径④」

之前我们把可行方案归为四条路径，这个项目**完整实现了第 4 条**：

- 它**不拖拽系统桌面上的图标**，也不读写系统 launcher 的数据库，也不用无障碍。
- 它**自己就是桌面**（`LauncherActivity` + `HomeV2Screen`）。布局完全由自己渲染 → **想怎么排就怎么排**。
- 代价：用户必须把它设为默认 launcher，改变使用习惯。

**这解释了为什么"自动整理"在这条路上反而最简单**——绕开了所有"操作系统桌面"的权限与兼容难题。

## 1. 形态与流程

```
设备启动 → LauncherActivity
  ├─ 首次：Onboarding（欢迎/权限/设为默认/主题）
  └─ 之后：HomeV2
       ├─ Hero Dashboard（时钟/日期/任务）
       ├─ Widget 页
       └─ 文件夹网格 ← 应用按分类自动成组
```

文件夹生成链路：

1. `PackageManagerHelper` 扫描设备已安装应用
2. `AppClassifier` 对每个应用分类
3. 结果写入 **Room 数据库**
4. `LauncherViewModel` 把数据库应用转成文件夹
5. 每个文件夹渲染为 `FolderTile`

## 2. 分类器：8 级优先级链（全项目最有价值的部分）

`AppClassifier.classifyAppDecision()` 是**唯一决策点**，按顺序短路返回：

| 优先级 | 决策来源 | 说明 | 置信度 |
|---|---|---|---|
| 1 | `userDecision` | 用户手动锁定/覆盖（最高优先，永不被自动覆盖） | USER_DECISION |
| 2 | `remoteCatalogDecision` | 远程目录服务（包名 → 类别） | REMOTE_CATALOG_EXACT |
| 3 | `bundledCatalogDecision` | **内置目录** `assets/app_categories.json`（124KB，约 3702 条包名映射） | BUNDLED_CATALOG_EXACT |
| 4 | `androidCategoryDecision` | **`ApplicationInfo.category`（Play Store 官方分类）** | ANDROID_CATEGORY |
| 5 | `manufacturerDecision` | 厂商前缀/名称规则（google/samsung/xiaomi/huawei/meta/spotify/amazon/apple） | MANUFACTURER_RULE |
| 6 | `keywordDecision` | 关键词库（应用名 / 包名 / appFileName） | APP_NAME_KEYWORD / PACKAGE_NAME_KEYWORD |
| 7 | `legacyLlmDecision` | LLM 缓存（仅 `LOCAL_WITH_LLM_FALLBACK` 模式） | LLM_LEGACY |
| 8 | `fallbackDecision` | 兜底「其他」 | FALLBACK_OTHER |

**分类模式**（`AppPrefs.ClassificationMode`）：
`MANUAL_REVIEW_ONLY` / `LOCAL_ONLY` / `LOCAL_WITH_MANUFACTURER` / `LOCAL_WITH_LLM_FALLBACK`

## 3. 关键发现：`ApplicationInfo.category`（Play Store 官方分类）

这是**我之前忽略的免费信号**，也是这个项目最值得抄的一点：

```kotlin
context.packageManager.getApplicationInfo(packageName, 0).category
```

- **API 26+（Android 8.0）** 起，应用可在 manifest 声明官方 Play Store 分类；
- **离线、免费、官方、无需网络、无需 LLM**；
- 映射：`CATEGORY_GAME → games`、`CATEGORY_SOCIAL → social`、`CATEGORY_PRODUCTIVITY → productivity`、`CATEGORY_MAPS → maps` 等。

> **启示**：自研分类时，这应该是**首选信号**，而不是一上来就调 LLM。

## 4. 关键词库与置信度设计

`KeywordDatabase`（512 行）：按 `Category` 分组的 keyword 列表（social/productivity/games/shopping/…），迭代记录可见（Loop 14/77/84…）。

**两个细节值得注意**：

1. **compact 匹配的假阳性陷阱**（注释里的真实回归）：
   - compact = 去掉所有非字母数字字符后再匹配。
   - 但**只对本身含分隔符的 keyword 启用**（如 `"yandex taxi" → "yandextaxi"`）。
   - 否则 `com.app.123456` 的 compact 形式 `comapp123456` 里会命中 `map`，把所有 `com.app.*` 误判为旅行类。这是被单元测试抓到的 bug。
2. **冲突信号处理**：`androidCategoryDecision` 与 `keywordDecision` 结果不一致时 → 置信度 −20、`requiresReview = true`。
3. **厂商分类的单例降级**：`classifyApps()` 中，厂商类别若只覆盖 1 个应用 → 归入「其他」，避免出现只有一个 App 的「Google」文件夹。

## 5. LLM fallback 的工程化用法（`CategoryLLMFallback`）

用的是 **DeepSeek API**（`api.deepseek.com/v1/chat/completions`，`model=deepseek-chat`），做法很成熟：

| 设计点 | 实现 |
|---|---|
| 批量 | `chunked(15)`，一次请求最多 15 个包名 |
| Prompt | 要求返回 `packageName=CAT_XXX`，明确「No explanations, no markdown」 |
| 超时 | `withTimeout(10_000L)`；`connectTimeout/readTimeout = 8000` |
| 失败降级 | 异常 → 全部 `CAT_OTHER`，且**不写缓存**（避免把临时错误固化） |
| 双层缓存 | 内存 `ConcurrentHashMap` + `AppPrefs` 持久化（重启不重复请求） |
| 输出归一 | `normalizeCategoryId()` 把 `CAT_*` 映射到 Room 的类别 id |

> **LLM 在这里是最低优先级 + 强缓存 + 失败降级**——再次印证「LLM 是可选增强，不是必需」。

## 6. 数据与模型

- 内置数据：`assets/app_categories.json`（124KB，约 3702 条）、`assets/app_database.json`（17KB）
- Room：`AppDatabase` 已有 **15 个 schema 版本**（`schemas/10.json` ~ `16.json`）
- `AppInfo`：`packageName`(PK), `appName`, `categoryId`, `usageCount`, `lastUsedTimestamp`, `notificationCount`, `notificationImportance`, `isSystemApp`, `isCategoryLocked`, `classificationSource`
- `Category`：`categoryId`, `categoryName`, `colorHex`, `iconEmoji`, `isSystemCategory`, `displayOrder`；常量与 **Google Play 官方分类一一对应**（games/social/productivity/shopping/…）

## 7. 工程质量（值得学习的部分）

- **1449 个单元测试**（domain 逻辑、repository 契约、分类器、migration）
- **Robolectric 视觉测试**：遍历 semantics 树验证每个节点不越界（溢出检测器），扫窄屏 + 大字号组合
- **Maestro 端到端流程**（`.maestro/`）
- 完善的文档体系：`MANAGEMENT/`（TASKS/ROADMAP/HISTORY/LEARNINGS/DECISIONS）+ `docs/`（architecture/performance/qa/release）+ `CLAUDE.md`（Agent 工作流规则）
- 源码注释为**土耳其语**（作者为土耳其开发者），但代码结构与命名清晰

## 8. 对我们的启示

1. **路径④（自建 launcher）是"自动整理"最彻底的方案**，因为它不需要跟系统桌面搏斗。本项目就是完整参考。
2. **`ApplicationInfo.category` 应作为分类首选信号**——免费、离线、官方。
3. **分类优先级链**可直接借鉴：用户决策 → 内置映射表 → 官方分类 → 厂商规则 → 关键词 → LLM → 兜底。
4. **LLM 的正确用法**：批量 + 强缓存 + 超时 + 失败降级，且永远不是唯一路径。
5. **厂商前缀分类**（`com.google.*` → Google 文件夹）是廉价高效的启发式，但要注意"单应用文件夹"降级。

## 9. 局限

- 用户必须**换 launcher**（改变使用习惯），无法"整理你现有的桌面"。
- 分类依赖应用名/包名/官方 category 等信号，**包名无意义的 App 仍会误判**。
- 是"自己的桌面自己排"，**不解决"系统桌面图标乱序"这个问题本身**。
