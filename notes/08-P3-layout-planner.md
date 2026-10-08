# 08 · P3 实测：布局规划器（自动选 App + 分类 + 网格排布）✅

> 执行日期：2026-10-01
> 结论：**成功**。Provider 从「硬编码图标清单」（v2）升级为**真·规划器**（v3）：
> 枚举设备上全部启动器应用 → 按官方分类聚成文件夹 → 按当前网格自动排布 → 生成 `<workspace>` XML。
> 实测（Lawnchair 4×6）：18 个应用 → 22 行落库、**冲突 0**、截图见 `poc/artifacts/lawnchair-p3-result.png`。
> 产物：`poc/src/com/example/layoutprovider/LayoutProvider.java`（v3）、`poc/artifacts/launcher-lawnchair-p3-final.db`

---

## 1. 规划器流水线（5 步）

```
① 枚举   PackageManager.queryIntentActivities(MAIN/LAUNCHER)
          → 排除自己 + 排除"启动器自身"（HOME 组件）
          → 按包去重，每包只留一个入口（优先 getLaunchIntentForPackage 指定的那个）
② 分类   ApplicationInfo.category（API 26+，Play 官方分类）优先
          → 兜底：包名 / 应用名的关键词规则
③ 成组   同类 ≥2 → 建文件夹；只 1 个 → 桌面单图标
④ 排布   按 URI 的 gridWidth/gridHeight 行优先填充；屏幕 0 第 0 行留空（smartspace 占用）
⑤ dock   按优先级候选挑 hotseatSize 个，写 container="hotseat" rank="N"
```

**输入**（launcher 实际发来的）：
`content://com.example.layoutprovider/launcher_layout?version=1&gridWidth=4&gridHeight=6&hotseatSize=4`

**输出**：`<workspace>` 根的布局 XML（格式见 `notes/06` §4）。

---

## 2. 分类策略（`bucketOf`）

| 优先级 | 信号 | 映射 |
|---|---|---|
| 1 | `ApplicationInfo.category` | `GAME→游戏`、`AUDIO/VIDEO/IMAGE→影音`、`SOCIAL→社交`、`NEWS→资讯`、`MAPS→出行`、`PRODUCTIVITY→效率`、`ACCESSIBILITY→工具` |
| 2 | 关键词（包名+应用名小写） | `dialer/phone/contacts`、`message/mms/mail`→社交；`maps/navig`→出行；`youtube/music/photo/video/gallery`→影音；`chrome/browser/docs`→效率；`camera/clock/calendar/settings/calculat/file`→工具 |
| 3 | 都没有 | **其他** |

> 实测这台模拟器：`Gmail/Calendar/Drive` 被系统标成 `PRODUCTIVITY` → 进「效率」；
> `Photos/YouTube/YT Music` → 「影音」；`Settings/Clock/Files` → 「工具」。
> **说明 `category` 这条官方信号确实可用**（笔记 00 §5.3 的判断被验证）。

---

## 3. 实测结果（Lawnchair 4×6）

`规划结果: 共 18 个应用; 分类桶={效率=3, 工具=3, 社交=1, 出行=1, 影音=3, 其他=3}; 桌面槽位=6（文件夹 4 个）; dock=4`

落库 22 行：

| 位置 | 内容 |
|---|---|
| 屏幕0 (0,1) | 📁 **效率** = Calendar / Drive / Gmail |
| 屏幕0 (1,1) | 📁 **工具** = Clock / Files / Settings |
| 屏幕0 (2,1) | 📁 **影音** = Photos / YT Music / YouTube |
| 屏幕0 (3,1) | 📁 **其他** = Safety / TMoble / Voice Search |
| 屏幕0 (0,2)(1,2) | Contacts、Maps（**单成员分类 → 单图标**，没做成文件夹） |
| dock rank0–3 | Phone / Messages / Chrome / Camera |

- `Item position overlap` 错误：**0**
- 文件夹子项 `container = 文件夹的 _id`（1 / 5 / 9 / 13），语义与笔记 07 §5.4 一致。

---

## 4. 三个踩坑（都踩了才修好）

### 4.1 ⚠️ `targetSdk 30+` 的包可见性：不写 `<queries>` 就「看不到别的应用」

第一次跑 v3，`queryIntentActivities` 只返回极少数结果（等于只有自己）。
**必须在 manifest 声明**：

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
    </intent>
</queries>
```

第二个 `intent` 是给"识别启动器自身"用的（见 4.3）。

### 4.2 ⚠️ 同一应用可能有**多个启动入口** → 会出现重复图标

实测 `com.google.android.googlequicksearchbox` 同时有 `SearchActivity` 和 `VoiceSearchActivity`
两个 `LAUNCHER` 入口，不去重就会生成**两个图标**。

**修法**：按 `packageName` 分组，每组只留一个——优先用 `PackageManager.getLaunchIntentForPackage(pkg)`
返回的组件（系统认定的"默认入口"），否则取第一个。

### 4.3 ⚠️ 排除"启动器自身"要按**组件**，不能按包名

想避免把 launcher 塞进文件夹，第一版按**包名**排除所有声明了 HOME 的应用 ——
结果 **`com.android.settings` 整个消失**（AOSP 的 Settings 包里含 `FallbackHome`，
它声明了 `CATEGORY_HOME`，于是整个包被误杀）。

**修法**：按 `packageName + "/" + className` 精确排除 HOME **组件**。
- Lawnchair：HOME 组件 = `app.lawnchair.LawnchairLauncher`，恰好也是它的 LAUNCHER 入口 → 被排除 ✅
- Settings：HOME 组件是 `FallbackHome`，LAUNCHER 入口是 `Settings` → **不受影响** ✅

---

## 5. v2 → v3 对比

| | v2（P2 PoC） | v3（P3 规划器） |
|---|---|---|
| 图标来源 | 硬编码 3 个数组 | **枚举设备全部启动器应用** |
| 分类 | 无 | `ApplicationInfo.category` + 关键词 |
| 文件夹 | 固定 1 个「工作」 | **按分类动态成组**（≥2 才成文件夹） |
| 网格 | 读参数、行优先 | 读参数、行优先、**跳过屏幕 0 第 0 行** |
| dock | 硬编码 4 个 | **按优先级候选自动挑** |
| 去重 / 排除 | 无 | 按包去重、按组件排除 launcher 自身 |
| 代码量 | ~180 行 | ~330 行 |

---

## 6. 复现

```bash
cd poc && bash build.sh
adb -s emulator-5554 install -r poc-layoutprovider.apk
adb -s emulator-5554 shell "settings put secure launcher3.layout.provider com.example.layoutprovider"
adb -s emulator-5554 shell "pm clear app.lawnchair.nightly"
adb -s emulator-5554 shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
sleep 25
adb -s emulator-5554 logcat -d -s LayoutProviderPoC | grep -E '规划结果|分类'
```

**撤销**：`adb shell settings delete secure launcher3.layout.provider` + `pm clear <launcher>`。

---

## 7. 遗留 / 下一步（P4）

- **多页（screen≥1）尚未实测**：这台模拟器 18 个应用正好一屏放完，跨页分支（`MAX_SCREENS=3`）没被触发。
- **`appwidget` / `searchwidget` 仍未用**：需要精确的 provider 组件名；格式见笔记 06 §2。
- **分类仍偏粗**：「其他」桶里混了 Safety / TMoble(运营商工具) / Voice Search。
  可按笔记 02 的思路补：**用户决策 → 内置映射表 → 厂商前缀（`com.google.*`）→ 关键词 → LLM（可选，失败回退）**。
- **厂商 ROM 未验证**（小米/华为是否保留该通道）。
- **"排列"目前是"重建"**：路径⑤ 会 `createEmptyDB` 清空重建。若要"保留用户已有布局、只补排新增应用"，
  需要先导出（`exportModelDbAsXml`）再合并 —— 属产品化议题。
