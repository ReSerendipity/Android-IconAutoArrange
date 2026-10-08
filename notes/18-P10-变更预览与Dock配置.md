# 18 · P10 第 4–5 步：变更预览 + Dock 可配置 ✅

> 日期：2026-10-02
> 产物：`LayoutDiff.java`（新增）、`AppConfig.manualDock`、UI 的「变更预览」卡片与 Dock 选择器

---

## 1. 第 4 步：变更预览（`LayoutDiff`）

### 1.1 关键设计：**只比容器，不比坐标**

一旦新增一个应用，后面所有图标的 `(x,y)` 都会平移 —— 按坐标比对会得出
"几乎所有东西都移动了"这种**无用结论**。用户真正关心的是：

> 「这个应用换文件夹了 / 被排到桌面了 / 进 Dock 了」

所以位置描述统一为三种：`dock` / `desktop` / `folder:<文件夹名>`，比对的是**容器是否变化**。

### 1.2 四类差异

| 类别 | 判定 |
|---|---|
| **新增** | 本次有、基线没有 |
| **移除** | 基线有、本次没有（通常是应用被卸载） |
| **移动** | 两边都有，但**容器变了**（显示成 `名称：旧位置 → 新位置`） |
| **保留** | 两边都有且容器一致（即使格子坐标变了也算保留） |

### 1.3 基线说明（必须写进 UI）

基线是 `last-generated.xml` —— **本应用上次生成的布局**，不是 launcher 的真实当前布局
（免 root 读不到，见 `notes/10`）。所以它反映的是「**我们的规划变了什么**」，
看不到用户手工拖动的痕迹。UI 里明确写了这句话，否则用户会误以为它能感知手工改动。

---

## 2. 第 5 步：Dock 可配置

`dockMode` 增加第三个取值：

| 取值 | 含义 |
|---|---|
| `auto`（默认） | 硬编码优先级（拨号/短信/浏览器/相机）—— 保持原行为 |
| `usage` | 按使用频率 Top N |
| **`manual`** | **用户指定**（有序，最多 `hotseatSize` 个） |

- 存储：`AppConfig.manualDock`（prefs key `manualDock`，逗号分隔）
- 新增 `call()`：`SET_MANUAL_DOCK`
- UI：Dock 下拉选「手动指定」后，展开一排**应用 chips** —— 点选即加入，**按点选顺序编号**（`1. Chrome`），
  超过 `hotseatSize` 会拦下并提示

---

## 3. ⚠️ 截图暴露的一个 UX 矛盾（已修）

第一版做完后截图发现：**我设了手动 Dock，但预览里 Dock 还是旧的 4 个**（Phone/Messages/Chrome/Camera）。

原因：合并模式会**优先保留用户已有的 dock**（`notes/17` 里记录的设计），
而 `dockCandidates` 只在"空位"时生效 —— 旧 dock 已经占满 4 个位，手动设置根本没机会生效。

**但这是错的**：用户既然**显式指定**了 Dock，就不该被旧值覆盖，否则用户会认为功能坏了。

**修法**：只有 `dockMode == "auto"` 时才保留基线 dock：

```java
if ("auto".equals(cfg.dockMode)) {
    for (String[] d : cur.dock) { …保留… }
}
for (String p : dockCandidates(cfg, usage, apps)) { … }
```

`usage` / `manual` 都是用户的显式选择，理应生效。

**实测修复后**：`mergeMode = True | dockMode = manual` → `dock = ['Chrome', 'Camera', 'Photos', 'YouTube']` ✅

> **教训**：这个 bug 是**看截图**发现的，不是看日志发现的 —— 日志里 `dockMode=manual` 和
> `SET_MANUAL_DOCK` 都是成功的，只有把「设置」和「预览结果」放在同一屏上对照，矛盾才显形。
> 所以每步都截图是有价值的，不只是留证。

---

## 4. 实测

### 4.1 Dock 手动指定

设 `Chrome / Camera / Photos / YouTube`：

```
manualDock = ['com.android.chrome','com.android.camera2','com.google.android.apps.photos','com.google.android.youtube']
dock 实际  = ['Chrome', 'Camera', 'Photos', 'YouTube']
```

顺序完全一致。

### 4.2 变更预览

同一操作触发的 diff：

```
hasBaseline = True
新增 0 / 移除 0 / 移动 3 / 保留 17
移动示例: Messages：Dock → 「社交」
          Contacts：桌面 → 「社交」
          Phone：Dock → 「社交」
```

**这正是想要的结果**：把 Dock 换掉后，原来的 dock 成员（Phone / Messages）被挤回「社交」文件夹，
Contacts 从桌面进了「社交」—— 报的是**有意义的容器变化**，而不是"所有图标坐标都变了"。

### 4.3 默认模式回归

| 模式 | 与重构前基线 |
|---|---|
| full | ✅ 逐字节一致 |
| merge | ✅ 逐字节一致 |

---

## 5. 遗留

- Diff **不报坐标级移动**（设计使然）。如果将来想看"这个图标从第 2 格挪到第 3 格"，需要另加一种视图。
- Diff 只在 `PLAN_JSON` 里产出，**应用前的"确认弹窗"还没接**（现在是展示在卡片里）。
  真正落地时应该在点「应用」时弹一次确认。
- 未在真机验证。
- 第 6–10 步待做（见 `notes/16` §4）。
