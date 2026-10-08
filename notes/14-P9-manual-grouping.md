# 14 · P9：手动分组（勾选/搜索 → 一个文件夹）✅

> 执行日期：2026-10-02
> 需求：「扫描应用列表 → 获取应用名称 → 在页面里勾选或搜索勾选 → 自动把这些应用放进桌面上的同一个文件夹」
> 结论：**已实现并实测通过**，桌面上的「我的分组」文件夹已真实生成。
> 产物：`LayoutProvider.java`（v6）、`assets/ui.html`（新增第 4 区）、`poc/artifacts/ui-v9~v12-*.png`

---

## 1. 设计

### 1.1 数据层

自定义分组存在 SharedPreferences：

| key | value |
|---|---|
| `cf:<id>` | `<分组名>\t<pkg1,pkg2,...>` |

- `loadCustomFolders(ctx)` 读出全部自定义分组
- provider 新增两个方法：`SET_CUSTOM_FOLDER`（新增/覆盖）、`DELETE_CUSTOM_FOLDER`

### 1.2 规划层（关键：优先级）

在 `buildPlan()` 里，自定义分组**先于一切自动逻辑**处理：

```
① 用户自定义分组  ← 最高优先级
      ↓ 成员从后续所有自动归类中排除（customPkgs 集合）
② 自动分类（效率/工具/影音/其他…）
③ 增量合并时，用户已有的文件夹 / dock 顺序
```

- **两种模式都生效**（全量重建 & 增量合并）
- **成员 < 2 个的分组会被跳过**（Launcher3 不允许只有 1 项的文件夹），并在日志里计数 `手动分组不足 2 项跳过 N`
- 被手动分组收走的应用，其原分类文件夹若因此只剩 1 项，会自动降级成桌面单图标（复用已有逻辑）

### 1.3 界面（新增第 4 区「手动分组」）

| 元素 | 作用 |
|---|---|
| 文件夹名称输入框 | 默认「我的分组」 |
| **搜索框** | 按应用名或包名实时过滤列表 |
| **应用列表（可点击整行勾选）** | 真实图标 + 名称 + 包名 + 勾选框 |
| 「已选 N 个」提示 | 不足 2 个时提示「至少 2 个才能成为文件夹」 |
| 「保存为文件夹」 | 把勾选结果存成一个分组 |
| 「清空勾选」 | 清空当前选择 |
| 已有分组列表 | 每个显示「名称 + 数量 + 编辑 + 删除」 |

**搜索勾选的关键设计**：选择状态存在 JS 的 `CF_SEL` 对象里，**与过滤条件解耦** ——
所以可以「搜 A 勾几个 → 换搜索词搜 B 再勾几个 → 一起保存」。

---

## 2. 实测

### 2.1 操作

在界面上勾选 **Photos / YT Music / YouTube** → 点「保存为文件夹」：

```
I LayoutProviderPoC: [SET_CUSTOM_FOLDER] 我的分组 <- com.google.android.apps.photos,
                     com.google.android.apps.youtube.music, com.google.android.youtube
```

`PLAN_JSON` 校验：

```
"customFolders":1
"name":"我的分组","count":3
"dest":"folder:我的分组"   × 3
```

### 2.2 桌面预览

`ui-v11-preview-cf.png`：**「我的分组」文件夹出现在屏幕 0 的第一个位置**，
内含三个应用图标；原来的「影音」文件夹因为成员被全部收走而**自动消失**（符合预期）。

### 2.3 应用到真桌面

```
规划结果: 共 20 个应用; 【合并模式 · 基线 last-generated.xml(自我记录)】
          保留文件夹 3 个, 新增 1 个应用, 清理失效 3 项;
          桌面 文件夹4 + 单图标2 + 部件0 = 6 个槽位, 占 1 屏, 手动分组 1 个; dock=4;
```

`Item position overlap` = **0**。

`ui-v12-launcher-cf.png`：**桌面上真实出现「我的分组」文件夹**（含 Photos/YT Music/YouTube），
后面依次是 效率 / 工具 / 其他，下面是 Contacts / Maps，底部是 Dock。

---

## 3. 复现

```bash
cd poc && bash build.sh
adb -s emulator-5554 install -r poc-layoutprovider.apk
adb -s emulator-5554 shell am start -n com.example.layoutprovider/.MainActivity
# 界面上滚到「4 手动分组」→ 勾选 → 命名 → 保存为文件夹
```

不走界面、直接测数据层：

```bash
adb shell "content call --uri content://com.example.layoutprovider \
  --method SET_CUSTOM_FOLDER --extra name:s:我的分组 \
  --extra pkgs:s:com.google.android.apps.photos,com.google.android.youtube"
adb shell "content call --uri content://com.example.layoutprovider --method PLAN_JSON"
```

---

## 4. 边界与遗留

- **一个应用只能属于一个手动分组**（先到先得；重复勾选会被第一个分组拿走）
- **不支持把文件夹拖到指定位置**：自定义分组目前总是排在最前面（顺序 = 创建顺序）
- **不支持嵌套**（Launcher3 本身也不支持文件夹套文件夹）
- 分组名支持中文（`titleText` 直接写 UTF-8，已在 `notes/06` 验证过）
- 未做「按分类一键全选」等批量辅助（搜索框可以变通做到）
