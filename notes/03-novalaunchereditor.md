# 03 · novalaunchereditor 源码精读笔记

> 仓库：SamLeatherdale/novalaunchereditor
> 语言：TypeScript + React + Vite（浏览器端，用 **sql.js** 跑 WASM 版 SQLite）
> 规模：约 900 行（核心是 5 个类/组件）
> ⚠️ **许可证：仓库未声明 License**（默认保留所有权利；参考学习没问题，直接复制代码需谨慎）
> 精读日期：2026-09-30
> 一句话：**解析 Nova Launcher 的 `.novabackup` 备份文件，把桌面布局还原成可视化页面**。对应我们讨论的**路径③：解析 launcher 备份**。

---

## 0. 备份文件格式（关键情报）

README 一句话点破：

> `.novabackup` 文件**就是一个改了名的 zip**，内含**一个 SQLite 数据库**（存应用及其桌面位置）**和一个 XML 文件**（存布局相关用户偏好）。

**对我们的意义**：路径③ 的入口不是"读系统数据库"，而是"解 zip → 读 SQLite"——**不需要 root、不需要 Shizuku**，只要能拿到备份文件即可。

## 1. 数据库表结构（Nova）

| 表 | 作用 | 关键列 |
|---|---|---|
| `favorites` | 桌面项（应用/文件夹/小部件/快捷方式） | `_id, title, intent, container, screen, cellX, cellY, spanX, spanY, itemType, rank, zOrder` |
| `allapps` | 全部已安装应用 | `_id, componentName, title, customIconSource, icon` |
| `workspaceScreens` | 屏幕顺序 | `_id, screenRank` |
| `customIcons` | 自定义图标图片 | `_id, icon`(PNG blob) |

## 2. `favorites` 表字段（Nova 版，带语义）

| 字段 | 语义 |
|---|---|
| `_id` | 主键 |
| `title` | 图标下方显示名 |
| `intent` | 点击启动的 Intent（字符串，见 §4） |
| `container` | 所属文件夹：**-100 = 不在容器；-101 = 在 hotseat(dock)** |
| `screen` | 所在桌面页；**0 表示该项在文件夹内** |
| `screenRank` | 屏幕顺序；**-1 表示不在可见屏上** |
| `hotseatRank` | 在 dock 中的位置 |
| `cellX` / `cellY` | 列 / 行 |
| `spanX` / `spanY` | 占几列 / 几行（小部件用） |
| `zOrder` | 堆叠顺序 |
| `itemType` | 类型枚举（见 §3） |
| `appWidgetId` / `appWidgetProvider` | 小部件 |
| `icon` | 图标 PNG blob |
| `customIconSource` | 自定义图标 URI，如 `icontheme_content_table://...customIcons/4` |
| `flags` / `modified` | 标记 / 修改时间 |

## 3. 关键常量与枚举（可直接抄）

```ts
CONTAINER_NONE    = -100   // 不在任何文件夹
CONTAINER_HOTSEAT = -101   // 在 dock

enum FavoriteItemType {
  App = 0, LauncherAction = 1, Folder = 2, Widget = 4, Link = 6
}
```

## 4. Intent 字符串解析

`favorites.intent` 是**分号分隔的键值串**：

```
action=xxx;category=yyy;launchFlags=zzz;component=包名/活动名;profile=ppp
```

解析方式：按 `;` 切分 → 每段按 `=` 切成 key/value。`component` 再按 `/` 拆出 `packageName` 和 `activityName`。

> 这是拿到「某个桌面项对应哪个 App」的**唯一途径**（没有单独的 package 列）。

## 5. schema 兼容性检测（跨 Nova 版本适配）

`LauncherDatabase` 不假设 schema 固定，而是**运行时探测**：

```ts
hasTable(name)   // SELECT 1 FROM sqlite_master WHERE type='table' AND name=?
hasColumn(table, column)  // PRAGMA table_info(table)
```

用途：
- `favorites.rank` 列**有** → `screenRank = screen`；**没有但有 `workspaceScreens` 表** → `LEFT JOIN workspaceScreens` 取 `screenRank`；
- `allapps.customIconSource` / `allapps.icon` 列可能不存在 → 用 `NULL as xxx` 占位。

> 这个"探测式兼容"是跨版本 launcher 数据库解析的**必备技巧**。

## 6. 最大发现：与 Launcher3 的 favorites 表同构

把这个表和上一篇 `android-app-organizer` 的快照字段对照：

| 字段 | Nova(novalaunchereditor) | Launcher3 系(android-app-organizer 快照) |
|---|---|---|
| 主键 | `_id` | `id` |
| 标题 | `title` | `title` |
| 意图 | `intent` | `intent` |
| 容器 | `container` | `container` |
| 屏 | `screen` / `screenRank` | `screen` |
| 格 | `cellX` / `cellY` | `cellX` / `cellY` |
| 跨度 | `spanX` / `spanY` | `spanX` / `spanY` |
| 类型 | `itemType` | `itemType` |
| 排序 | `rank` / `zOrder` | `rank` |

**结论**：Nova / Lawnchair / 系统 Launcher3 **共享同一套 AOSP `favorites` schema**。这意味着——
> **路径② 和 路径③ 可以复用同一套字段知识**。学会一个，其他 launcher 只需处理少量扩展列差异。

## 7. 对我们的启示

1. **路径③ 门槛最低**：不需要 root/Shizuku/无障碍，只要有备份文件（zip + SQLite）。
2. **`favorites` 字段语义**已经清楚（尤其 `container` 的 -100/-101、`screen=0` 表示文件夹内、`itemType` 枚举）。
3. **`intent` 串是识别应用的唯一入口**，必须实现解析。
4. **探测式 schema 兼容**（`hasTable`/`hasColumn`）是跨版本解析的标准做法。
5. **可组合**：备份解析负责"读"，若要"写"回去，仍需要路径②（写数据库）或路径①（UI 拖拽）。

## 8. 局限

- 只读为主（查看/编辑备份文件），**不直接作用于设备**。
- 仅适配 Nova 的备份格式；其他 launcher 备份格式需另写解析。
- 仓库**无 License 声明**，代码复用有法律风险。
- 是浏览器端工具（sql.js + React），不是移动端方案。
