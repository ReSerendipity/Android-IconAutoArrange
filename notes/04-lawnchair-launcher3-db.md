# 04 · lawnchair / Launcher3 精读笔记（桌面布局数据库）

> 仓库：LawnchairLauncher/lawnchair（分支 `16-dev`，Apache-2.0，AOSP Launcher3 的 fork）
> 语言：Kotlin / Java
> 精读日期：2026-09-30
> 一句话：**桌面布局不在 `app.lawnchair.data.AppDatabase`（那是 Lawnchair 自己的偏好库），而在继承自 AOSP Launcher3 的 `launcher.db`——核心是 `favorites` 表**。这里拿到了该表的**权威定义**。

---

## 0. 关键澄清：两个数据库，别搞混

| 数据库 | 位置 | 内容 |
|---|---|---|
| `app.lawnchair.data.AppDatabase` | `schemas/` 下仅 3 个版本 | Room 库，存 **IconOverride** 等**偏好**，**不含布局** |
| **`launcher.db`** | Launcher3 的 `DatabaseHelper` | 存**桌面布局**，核心表 **`favorites`**；`SCHEMA_VERSION = 32` |

> 我第一次查 `schemas/` 目录时没找到 `favorites`，正是因为搞错了库。布局库在 **`src/com/android/launcher3/`** 下的 AOSP 代码里。

**关键源文件**：
- `src/com/android/launcher3/LauncherSettings.java` —— favorites 表列定义 + container 常量
- `src/com/android/launcher3/model/DatabaseHelper.java` —— 建表/迁移（SCHEMA_VERSION = 32）
- `src/com/android/launcher3/LauncherProvider.java` —— ContentProvider 入口
- `src/com/android/launcher3/provider/LauncherDbUtils.kt` —— 表迁移工具（`CREATE TABLE AS SELECT` → `DROP` → `RENAME`）

## 1. `favorites` 表权威定义（19 列，按建表顺序）

| # | 列名 | 类型 | 语义 |
|---|---|---|---|
| 1 | `_id` | INTEGER PRIMARY KEY | 主键 |
| 2 | `title` | TEXT | 显示名 |
| 3 | `intent` | TEXT | 点击启动的 Intent（分号键值串） |
| 4 | `container` | INTEGER | 所属容器（见 §2） |
| 5 | `screen` | INTEGER | 桌面页（container=DESKTOP 时有效） |
| 6 | `cellX` | INTEGER | 列 |
| 7 | `cellY` | INTEGER | 行 |
| 8 | `spanX` | INTEGER | 占几列 |
| 9 | `spanY` | INTEGER | 占几行 |
| 10 | `itemType` | INTEGER | 项类型（见 §3） |
| 11 | `appWidgetId` | INTEGER NOT NULL DEFAULT -1 | 小部件 id |
| 12 | `icon` | BLOB | 图标 |
| 13 | `appWidgetProvider` | TEXT | 小部件 provider |
| 14 | `modified` | INTEGER NOT NULL DEFAULT 0 | 修改时间 |
| 15 | `restored` | INTEGER NOT NULL DEFAULT 0 | 是否恢复而来 |
| 16 | `profileId` | INTEGER DEFAULT <profileId> | 用户档案 |
| 17 | `rank` | INTEGER NOT NULL DEFAULT 0 | 在自动排列视图（文件夹/dock）内的位置 |
| 18 | `options` | INTEGER NOT NULL DEFAULT 0 | 通用标志位 |
| 19 | `appWidgetSource` | INTEGER NOT NULL DEFAULT -1 | 小部件来源容器 |

建表方式（`LauncherSettings.Favorites`）：

```java
db.execSQL("CREATE TABLE " + (optional ? " IF NOT EXISTS " : "") + tableName + " ("
        + getJoinedColumnsToTypes(myProfileId) + ");");
// columnsToTypes 是 LinkedHashMap，保证列顺序
```

## 2. `container` 常量全表（关键！）

| 常量 | 值 | 含义 |
|---|---|---|
| **CONTAINER_DESKTOP** | **-100** | 在桌面（正常图标） |
| **CONTAINER_HOTSEAT** | **-101** | 在 dock |
| CONTAINER_ALL_APPS_PREDICTION | -102 | 抽屉预测 |
| CONTAINER_HOTSEAT_PREDICTION | -103 | dock 预测 |
| CONTAINER_ALL_APPS | -104 | 应用抽屉 |
| CONTAINER_WIDGETS_TRAY | -105 | 小部件抽屉 |
| CONTAINER_SHORTCUTS | -107 | 快捷方式 |
| CONTAINER_SETTINGS | -108 | 设置 |
| CONTAINER_TASKSWITCHER | -109 | 任务切换器 |
| CONTAINER_PRIVATESPACE | -110 | 私密空间 |
| CONTAINER_WIDGETS_PREDICTION | -111 | — |
| CONTAINER_BOTTOM_WIDGETS_TRAY | -112 | — |
| CONTAINER_PIN_WIDGETS | -113 | — |
| CONTAINER_WALLPAPERS | -114 | — |
| CONTAINER_UNKNOWN | -1 | 未知 |

> 判断"某个图标是不是在桌面主屏上"：`container == -100`；判断"在文件夹里"：`container > 0`（值是文件夹项的 `_id`）。

## 3. `itemType` 枚举

与 Nova 基本一致：`0 = Application`、`1 = Shortcut`、`2 = Folder`、`4 = AppWidget`、`6 = DeepShortcut`（Nova 的 Link 也为 6）。

## 4. 重大发现：Launcher3 内置了官方「布局提供者」机制

在 `LauncherSettings.Settings` 里发现：

```java
public static final String LAYOUT_PROVIDER_KEY = "launcher3.layout.provider";
public static final String LAYOUT_DIGEST_LABEL = "launcher-layout";
public static final String BLOB_KEY_PREFIX = "blob://";
public static String createBlobProviderKey(byte[] digest) { ... }
```

**含义**：Launcher3 自身实现了**布局导入/导出的 ContentProvider 通道**（通过 `Settings` 表里的 `launcher3.layout.provider` 键 + `blob://` 句柄）。

> **这可能是路径③的"官方入口"**——比解析私有备份文件更正规，值得下一步深挖（`LauncherProvider.java` / `LauncherSettings.Settings`）。

## 5. 三仓库交叉验证：`favorites` schema 完全同构

| 字段 | Nova（novalaunchereditor） | Launcher3（lawnchair） | 快照（android-app-organizer） |
|---|---|---|---|
| 主键 | `_id` | `_id` | `id` |
| 标题 | `title` | `title` | `title` |
| 意图 | `intent` | `intent` | `intent` |
| 容器 | `container` | `container` | `container` |
| 屏 | `screen`(+`screenRank`) | `screen` | `screen` |
| 格 | `cellX`/`cellY` | `cellX`/`cellY` | `cellX`/`cellY` |
| 跨度 | `spanX`/`spanY` | `spanX`/`spanY` | `spanX`/`spanY` |
| 类型 | `itemType` | `itemType` | `itemType` |
| 排序 | `rank`/`zOrder` | `rank` | `rank` |
| 容器常量 | -100 / -101 | **-100 / -101（完全一致）** | -100 默认 |

**结论**：**Nova / Lawnchair / 系统 Launcher3 共享同一套 AOSP `favorites` schema**。学会一套字段知识，可覆盖绝大多数主流 launcher。

## 6. 对我们的启示

1. **路径② 的落点明确了**：`launcher.db` 的 `favorites` 表，改 `container / screen / cellX / cellY` 即可重排。
2. **容器语义是重排的关键**：把图标移进文件夹 = 把 `container` 改成文件夹的 `_id` + 设 `rank`；移回桌面 = `container = -100` + 指定 `screen/cellX/cellY`。
3. **schema 会演进**（Launcher3 已到 v32），**必须做版本探测**（参考 novalaunchereditor 的 `hasTable`/`hasColumn`）。
4. **优先考虑官方"布局提供者"通道**，而不是硬改数据库——风险更低。
5. 修改后需要让 launcher 重新加载（Launcher3 有 `LAYOUT_DIGEST` 变更检测机制），否则不会立即生效。

## 7. 局限

- 本次只稀疏检出了 `src/ lawnchair/ shared/ schemas/ compatLib`，未含 `quickstep/` 等；如需看手势/QuickStep 需补检。
- `launcher.db` 位于应用私有目录，**读取需要 root 或 Shizuku**（这正是路径②的门槛）。
- 不同厂商定制 launcher（小米/华为）可能修改 schema，需单独适配。
