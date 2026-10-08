# 01 · android-app-organizer 源码精读笔记

> 仓库：device-kunkun/android-app-organizer（MIT License）
> 规模：Python 核心约 1600 行 + Kotlin Helper 约 800 行
> 精读日期：2026-09-30
> 一句话：**PC 端 Python 通过 UIAutomator2（adb）采集桌面图标坐标并分类，按确定性网格算法生成布局计划，再以低级触摸手势（长按进编辑模式 + 曲线拖拽 + 抖动悬停）模拟拖拽完成整理；全程 dry_run 保护、支持回滚。**

---

## 0. 形态定位（最关键的一点）

它**不是手机上的 App**，而是 **PC 端 Python 脚本 + adb 连接手机**：

- 执行依赖 `uiautomator2`（`u2.connect(serial)`），需要 USB 调试 / adb。
- 因此**面向开发者自用**，无法直接分发给普通用户（普通用户不会开 adb）。
- 仓库另附 `android-helper/`（设备端 App），但只做 **vivo Launcher Provider 的只读探测**，发布副本不申请 Launcher 写权限。

## 1. 流水线架构

`main.py` 四个子命令：

| 命令 | 作用 |
|---|---|
| `run` | scan → classify → plan → execute |
| `plan` | scan → classify → plan（不执行） |
| `apply` | 执行已保存的 plan.json |
| `rollback` | 按 executed.json 反向拖拽回滚 |

```
采集 collector ─┐
                ├─→ 分类 classifier → 规划 planner → 执行 executor → 记录 storage
快照 snapshot  ─┘                                        └→ 回滚 executor.rollback
```

## 2. 逐层详解

### 2.1 采集 collector.py

- 用 `d.xpath('//*[@text]').all()` dump 当前页所有带文本节点。
- **过滤规则（值得抄）**：
  - `className` 必须是 `android.widget.TextView` 或 **`android.widget.BubbleTextView`**（launcher 图标用的类）。
  - 尺寸过滤：宽 ∈ [12%, 34%] 窗口宽，高 ∈ [8%, 24%] 窗口高。
  - 排除状态栏（top < 8% 窗口高）与 dock（bottom > 92%）。
  - `packageName` 必须等于配置的 `launcher_package`。
- 翻页：`d.swipe(x1,y1,x2,y2,duration)`，用**页面签名**（app 名排序元组）判断是否重复，防死循环。
- 文件夹探测：点击名字含关键词（工具/社交/游戏…）的图标 → 用是否存在文本「添加」判断文件夹已打开 → 采集内部项。

### 2.2 快照 snapshot.py（数据库通道）

解析 JSON 快照，字段与 **Launcher3 `favorites` 表**一一对应：

`id, title, intent, itemType, container, screen, cellX, cellY, spanX, spanY, rank, modified`

另有 `screens` 表：`id, screen_order`。

- `merge_snapshot_into_apps`：按 `title` 把数据库布局信息合并到 UI 采集到的图标上（拿到精确 cell 坐标）。
- `build_virtual_apps`：仅用快照构造（无屏幕坐标），用于 `plan` 阶段离线规划。
- 配置项 `layout.use_snapshot_for_planning` + `snapshot_file` 控制是否走这条路。

> **这是"混合方案"**：快照负责**规划精度**（真实 cell 坐标、跨屏），UIAutomator 负责**执行**（真实拖拽）。

### 2.3 分类 classifier.py

三级策略，层层兜底：

1. **硬编码规则表** `RULES`（60+ 中文 app 名 → 类别）+ `SHOPPING_TOKENS`（京东/淘宝/拼多多…→ 购物）。
2. **正则兜底**：`game|hero|fps|rpg` → 游戏；否则「其他」。
3. **LLM（仅 `openai_compat`）**：
   - `_classify_batch_openai`：一次性把所有 app 名 + 固定类别列表发给模型，要求返回 **strict JSON** `{"app":"category"}`。
   - `_classify_one`：逐条兜底。
   - `plan_layout`：让 LLM 直接分配 `target_screen / cellX / cellY`，约束「同类别相邻、单元格唯一、优先左上、保持紧凑稳定」。

provider 三选一：`rule` / `qwen_local`（本地 Transformers Qwen2.5-3B）/ `openai_compat`（任意 OpenAI 兼容 API）。

**所有 LLM 调用失败都回退到规则**；`trace_logs` 记录每轮 prompt/response 便于调试。

### 2.4 规划 planner.py（确定性核心）

- `_build_cells`：按 `grid_rows × grid_cols` + `margin_x/y` + `slot_spacing_x/y` 生成网格坐标。
- `plan()`：
  1. 按 `category` 分组；
  2. 每个类别占一页（`page_cursor` 递增）；
  3. 组内按 (screen, cell_y, cell_x, name) 排序；
  4. 行优先填充 `cell_x = i % cols`、`cell_y = i // cols`；
  5. `_cell_center` 换算屏幕像素坐标。
- 若 `llm_layout_plan` 存在，则**覆盖**默认坐标。
- `_logical_to_screen_id` 处理逻辑页 ↔ 真实 screen id 映射。

> **核心排列算法完全是确定性的，几十行**。LLM 只是可选的"更聪明的排布"。

### 2.5 执行 executor.py ← 全项目最有价值的部分

**手势一：网格移动 `_touch_drag_release`**

```
touch.down(源)
touch.sleep(长按 1.45s)      # ← 进入 launcher 编辑模式（关键前提）
touch.sleep(settle 0.25s)
touch.move(mid1)             # 22% 处
touch.move(mid2)             # 56% 处
touch.move(mid3)             # 84% 处
touch.move(目标)
touch.up(目标)
```

- 分 4 段插值移动（不是一步到位），每段 sleep ≥ 0.08s。
- **长按进编辑模式**是 Android launcher 拖拽图标的必要前提。

**手势二：建/加文件夹 `_touch_hold_over_icon`**

在上一手势基础上增加：

- **曲线路径**（`curve` 偏移 10–24px），避免直线被识别为翻页滑动；
- 到达目标后在目标周围做 **jitter 抖动悬停**（半径 6px、3 步、总 hover 2.0s），**触发文件夹"悬停展开"**，再 `up`。

**验证与回滚**

- `_verify_folder_created`：创建后等 0.9s，检测源/锚点图标的**位移是否超过阈值 36px** 来判断文件夹是否真的建成功。
- `rollback`：把 executed 记录反序、`drag(target → source)` 拖回原位。
- `dry_run` 贯穿所有手势函数（直接 return）。

### 2.6 编排 main.py

`run` 阶段产物（`runs/<run_id>/`）：
`apps.json`、`collector_stats.json`、`classified.json`、`category_stats.json`、`llm_trace.json`、`plan.json`、`executed.json`

## 3. 手势时序参数经验值（可直接借鉴）

| 参数 | 值 | 含义 |
|---|---|---|
| drag_duration | 0.7 | 网格拖拽总时长 |
| source_long_press_before_drag | 0.95 | 源图标长按 |
| edit_mode_hold_before_drag | 1.45 | 进编辑模式的长按时长 |
| edit_mode_settle_after_long_press | 0.25 | 长按后静置 |
| folder_create_drag_duration | 1.9 | 建文件夹拖拽 |
| folder_add_drag_duration | 1.6 | 加入文件夹拖拽 |
| folder_hover_before_release | 2.0 | 目标上悬停 |
| folder_hover_jitter_radius | 6 | 悬停抖动半径（px） |
| folder_hover_jitter_steps | 3 | 抖动步数 |
| folder_verify_wait | 0.9 | 创建后验证等待 |
| folder_verify_shift_threshold | 36 | 判定位移阈值（px） |
| swipe_for_next_page | [900,1200,180,1200,0.22] | 翻页手势 |

## 4. 对技术选型的印证

| 此前的判断 | 该项目的做法 | 结论 |
|---|---|---|
| ①无障碍模拟拖拽可行但脆弱 | 实际用 **UIAutomator2（adb）**，非无障碍 | 路径成立，但需 adb；手势参数极多、需逐机型调 |
| ②Shizuku/root 直读写 launcher.db | 用**快照**（favorites 表字段）做规划，不直接写 | 印证字段结构；但**只读**用于规划，写仍靠 UI 拖拽 |
| LLM 非必需，仅规划增强 | 规则表 + 正则兜底，LLM 仅 openai_compat 时启用，失败回退 | **完全印证** |
| 自动排列核心是确定性算法 | planner 就是网格分组填充 | **完全印证** |

**最大启示**：这个项目走的是「**数据库快照（规划）+ UI 自动化（执行）**」的混合路线。它没有用 Shizuku 直写数据库，而是"读得快、写得慢"——说明**直接写 launcher.db 的风险（schema 差异、被 launcher 覆盖）让它选择了保守方案**。

## 5. 可直接复用的点

- `BubbleTextView` 这个 className 过滤条件（launcher 图标识别）。
- 页面签名去重防死循环。
- 长按进编辑模式 + 分段曲线拖拽 + 抖动悬停 的完整手势配方与参数。
- 位移阈值验证文件夹创建成功。
- 规则兜底 + LLM 增强 + trace 日志的分类架构。
- dry_run / run_id 产物 / 回滚 的工程化安全设计。

## 6. 局限（自己实现时要改进）

- 依赖 adb，普通用户门槛高（需 PC + USB 调试）。
- 时序参数硬编码，跨机型 / 跨 launcher 需重调。
- 图标识别靠**文本**（`d(text=name)`），重名 / 无文本图标会失败。
- 未直接写数据库，速度受限于真实拖拽动画（几十个图标需数分钟）。
- 分类规则表是中文 app 硬编码，覆盖面有限。
