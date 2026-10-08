# 09 · P4 实测：占位网格 / appwidget / searchwidget / 多页

> 执行日期：2026-10-01
> 结论：**3 项验证 2 通过 1 发现硬伤**。
> - ✅ **`appwidget`**：落库正确（`itemType=4`、span 2×2），图标自动绕开其占格
> - ❌ **`searchwidget`**：在 Lawnchair 上**必然抛 NPE**，导致**整份布局解析中断**、launcher 回退内置默认布局 —— **笔记 06 §2 需修正**
> - ✅ **多页**：28 个图标正确铺到屏幕 0（20 个）+ 屏幕 1（8 个），0 冲突 0 越界
> 产物：`poc/.../LayoutProvider.java`（v4）、`poc/artifacts/launcher-p4-*.db`、`lawnchair-p4-final.png`

---

## 1. v4 改了什么

| 改动 | 说明 |
|---|---|
| **真·占位网格** | 从「行优先递增」换成 `Grid`（`boolean[gw][gh]`，可动态开屏）。支持 **spanX/spanY**，能跳开已被占的格子 |
| **appwidget** | 从候选表里挑第一个已安装的 provider，按 span 占位；图标自动绕开 |
| **searchwidget** | 实现并实测 → **发现必然崩**（见 §3），默认关闭 |
| **多页** | 网格自动开新屏，上限 `MAX_SCREENS=3` |
| **调试配置** | `<filesDir>/poc-config.txt`（`widgets=1` / `searchwidget=1` / `spread=1` / `repeat=N`），**不改代码就能 A/B 实测** |

**为什么需要占位网格**：v3 的"行优先递增"假设每个图标都是 1×1。一旦要放 2×2 的小组件，
就得知道哪些格子被占了 —— 这正是 v4 的 `Grid.place(sx, sy)` 解决的。

---

## 2. ✅ `appwidget` 实测通过

配置 `widgets=1`，生成：

```xml
<appwidget container="desktop" screen="0" x="0" y="1"
           packageName="com.google.android.deskclock"
           className="com.android.alarmclock.DigitalAppWidgetProvider"
           spanX="2" spanY="2" />
```

落库结果（`itemType=4`）：

| _id | itemType | container | screen | cellX | cellY | spanX | spanY | appWidgetProvider |
|---|---|---|---|---|---|---|---|---|
| 1 | **4 (APPWIDGET)** | -100 | 0 | 0 | 1 | **2** | **2** | com.google.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider |

**图标自动绕开**：效率(2,1)、工具(3,1)、影音(2,2)、其他(3,2)、Contacts(0,3)、Maps(1,3)
—— 小组件占的 (0,1)(1,1)(0,2)(1,2) 全被跳过。**23 行落库、0 冲突**。

截图 `poc/artifacts/lawnchair-p4-final.png`：左上角是 DeskClock 小组件（显示 "Tap to finish setup"，
因为走的是 **pending widget** 路径：源码注释明确说"不显示配置界面，配置项要通过 `<extra>` 传"），
四个文件夹紧挨着它右侧排布。

> ⚠️ 注意：**`<extra key=... value=.../>` 是 appwidget 唯一允许的子节点**（放别的东西会抛异常），
> 且该 widget 若不支持读 widget options，就只能停在"待配置"状态。

---

## 3. ❌ `searchwidget` 是**硬伤**：必然 NPE，且会毁掉整份布局

配置 `searchwidget=1` 后，日志：

```
E AutoInstalls: Error parsing layout:
E AutoInstalls: java.lang.NullPointerException: Attempt to invoke virtual method
                'int java.lang.Integer.intValue()' on a null object reference
E AutoInstalls:   at com.android.launcher3.AutoInstallsLayout$SearchWidgetParser.verifyAndInsert(…:25)
E AutoInstalls:   at com.android.launcher3.AutoInstallsLayout$PendingWidgetParser.parseAndAdd(…:72)
E AutoInstalls:   at com.android.launcher3.AutoInstallsLayout.parseAndAddNode(…:124)
E AutoInstalls:   at com.android.launcher3.AutoInstallsLayout.parseLayout(…:35)
E AutoInstalls:   at com.android.launcher3.model.DatabaseHelper.loadFavorites(…:1)
E AutoInstalls:   at com.android.launcher3.model.ModelDbController.loadDefaultFavoritesIfNecessary(…:142)
```

**根因（源码对照 `AutoInstallsLayout.java`）**：

```java
// SearchWidgetParser.verifyAndInsert —— 先读 RESTORED
protected int verifyAndInsert(ComponentName cn, Bundle extras) {
    mValues.put(Favorites.OPTIONS, LauncherAppWidgetInfo.OPTION_SEARCH_WIDGET);
    int flags = mValues.getAsInteger(Favorites.RESTORED)   // ← 此刻还是 null → NPE
            | WorkspaceItemInfo.FLAG_RESTORE_STARTED;
    ...
    return super.verifyAndInsert(cn, extras);
}

// PendingWidgetParser.verifyAndInsert —— RESTORED 是在这里才写进去的（在 super 调用之后）
protected int verifyAndInsert(ComponentName cn, Bundle extras) {
    mValues.put(Favorites.RESTORED, LauncherAppWidgetInfo.FLAG_ID_NOT_VALID | …);
    ...
}
```

`parseAndAddNode()` 每个节点前会 `mValues.clear()`，所以轮到 `searchwidget` 时 `RESTORED` 必然是 null。

**后果链（比"这个标签不能用"严重得多）**：

```
searchwidget → NPE → loadLayout() 捕获后 return -1（一条都没插入）
            → ModelDbController: loadFavorites(...) <= 0 && usingExternallyProvidedLayout
            → createEmptyDB() + 用【内置默认布局】重建
```

即：**只要 XML 里出现 `searchwidget`，你精心规划的整份布局会被全部丢弃，桌面变成 launcher 自带的默认样子。**
（实测日志里能看到 `DefaultLayoutParser: Unable to bind app widget id … AnalogAppWidgetProvider`
—— 那是内置默认布局在跑。）

**对策**：**外部布局里不要用 `searchwidget`**。要搜索框就让 launcher 自己的（Pixel/Lawnchair 都有），
或用 `appwidget` 放一个第三方搜索小组件（如 `com.android.chrome/org.chromium.chrome.browser.searchwidget.SearchWidgetProvider`）。

> 这条**修正了 `notes/06` §2**：那里把 `searchwidget` 列为可用标签，实测是不可用的。

---

## 4. ✅ 多页实测通过

配置 `spread=1 repeat=2`（不建文件夹 + 槽位翻倍）→ 28 个图标：

```
规划结果: 共 18 个应用; 桌面 文件夹0 + 单图标28 + 部件0 = 28 个槽位, 占 2 屏; dock=4
```

落库分布：

| container | screen | 项数 |
|---|---|---|
| -100（桌面） | **0** | **20**（行 1–5 × 4 列，第 0 行留空） |
| -100（桌面） | **1** | **8**（行 0–1 × 4 列） |
| -101（dock） | 0–3 | 各 1 |

- `Item position overlap`：**0**；`out of screen bounds`：**0**。
- 注意**屏幕 1 从 y=0 开始**（顶行只在屏幕 0 保留给 smartspace）——符合设计。

---

## 5. 复现

```bash
cd poc && bash build.sh
adb -s emulator-5554 install -r poc-layoutprovider.apk

# 写调试配置（root 直写；chmod 让 app 可读）
adb -s emulator-5554 shell "printf 'widgets=1\n' > /data/data/com.example.layoutprovider/files/poc-config.txt && \
                            chmod 644 /data/data/com.example.layoutprovider/files/poc-config.txt"

adb -s emulator-5554 shell "settings put secure launcher3.layout.provider com.example.layoutprovider"
adb -s emulator-5554 shell "pm clear app.lawnchair.nightly"
adb -s emulator-5554 shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
sleep 25
adb -s emulator-5554 logcat -d -s LayoutProviderPoC | grep -E '配置\[|规划结果'
```

**清配置回默认**：`adb shell rm -f /data/data/com.example.layoutprovider/files/poc-config.txt`

---

## 6. 遗留 / 下一步

- **`searchwidget` 的 NPE 是 Lawnchair 快照（commit `126b225`）里的**；AOSP / Pixel Launcher 上是否同样存在**未验证**
  （Pixel Launcher 的类被 R8 混淆过，但同样的调用顺序大概率一致）。
- **厂商 ROM 未验证**（小米/华为是否保留该通道）。
- **"重建"而非"增量排列"**：路径⑤ 走 `createEmptyDB`，会丢掉用户原有布局。
  产品化需要「先 `exportModelDbAsXml` 导出 → 合并 → 再导入」。
- 分类仍偏粗（「其他」桶混了 Safety / TMoble / Voice Search）；可接 LLM 兜底（失败回退）。
