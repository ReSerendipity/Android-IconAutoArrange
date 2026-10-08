# 10 · P5：导出接口 + 增量（合并）排列 ✅

> 执行日期：2026-10-01
> 结论：**路径⑤ 从"整份重建"升级为"增量合并"**，并查清了免 root 的**真实边界**。
> - 🔎 **新发现**：launcher 自己也是一个 ContentProvider，通过 `call()` 暴露 **`EXPORT_LAYOUT_XML` / `IMPORT_LAYOUT_XML`** 两个方法
> - 🔒 **权限边界（实测，含一次自我纠错）**：**只有 root 能调**；普通 app ❌、**shell(uid 2000) / Shizuku ❌**（`signatureOrSystem`）
> - ✅ **增量合并已实现并验证**：喂入 launcher 导出的当前布局 → **保留用户已有文件夹分组与 dock 顺序**，只补新应用、清失效项 → 实测 `保留文件夹 4 个, 新增 1 个, 清理失效 1 项`，22 行 0 冲突
> 产物：`poc/.../LayoutProvider.java`（v5）、`poc/artifacts/export-current.xml`、`launcher-p5-merge.db`

---

## 1. 新发现：launcher 侧的 export / import 接口

`references/lawnchair/src/com/android/launcher3/LauncherProvider.java`：

```java
private static final String METHOD_EXPORT_LAYOUT_XML = "EXPORT_LAYOUT_XML";
private static final String METHOD_IMPORT_LAYOUT_XML = "IMPORT_LAYOUT_XML";
private static final String KEY_RESULT = "KEY_RESULT";
private static final String KEY_LAYOUT = "KEY_LAYOUT";

@Override
public Bundle call(String method, String arg, Bundle extras) {
    switch (method) {
        case METHOD_EXPORT_LAYOUT_XML:
            // 需要 readPermission
            CompletableFuture<String> f = LayoutImportExportHelper.INSTANCE.exportModelDbAsXmlFuture(getContext());
            b.putString(KEY_LAYOUT, f.get());
            b.putString(KEY_RESULT, SUCCESS);
            return b;
        case METHOD_IMPORT_LAYOUT_XML:
            // 需要 writePermission；arg 就是布局 XML 字符串
            LayoutImportExportHelper.INSTANCE.importModelFromXml(getContext(), arg);
            b.putString(KEY_RESULT, SUCCESS);
            return b;
    }
}
```

**契约汇总**：

| 项 | 值 |
|---|---|
| authority | **`<launcher包名>.settings`**（manifest 里写的是 `android:authorities="${applicationId}.settings"`） |
| 导出 | `contentResolver.call(Uri.parse("content://<pkg>.settings"), "EXPORT_LAYOUT_XML", null, null)` → `Bundle.getString("KEY_LAYOUT")` |
| 导入 | 同上，method = `"IMPORT_LAYOUT_XML"`，**第 2 个参数 `arg` 直接传 XML 字符串** |
| 返回 | `KEY_RESULT` = `"success"` / `"failure"` |
| 权限 | provider 声明 `android:readPermission="<pkg>.permission.READ_SETTINGS"`、`android:writePermission="<pkg>.permission.WRITE_SETTINGS"`，两者都是 **`signatureOrSystem`** |
| 类注释 | *"Only applications installed on the system partition or those possessing the platform's signature can access this provider."* |

> ⚠️ 这**补充了 `notes/05`**：那里只写了"由 Secure Settings 的 `launcher3.layout.provider` 触发"，
> 实际上 launcher 还对外开了这套 `call()` API（供系统/签名应用用）。

---

## 2. 权限边界实测（决定性实验）

### 2.1 ⚠️ 订正：**只有 root 能过，shell 身份不行**

> **本节结论经过一次自我纠错。** 最初测出"adb 可以导出"时，adbd 处于 `adb root` 状态（uid 0），
> 我把结论误记成"shell 身份也可以"。**用 `adb unroot` 回到 uid 2000 复测后，同样被拒。**
> 严格 A/B 对照：

| 身份 | 命令 | 结果 |
|---|---|---|
| **uid 0（root）** | `adb root` 后 `content call … EXPORT_LAYOUT_XML` | ✅ `Result: Bundle[{KEY_LAYOUT=<workspace>…}]` |
| **uid 2000（shell）** | `adb unroot` 后同一条命令 | ❌ `SecurityException: Permission Denial: opening provider com.android.launcher3.LauncherProvider from (null) (pid=…, uid=2000) requires app.lawnchair.nightly.permission.READ_SETTINGS` |

**原因**：`signatureOrSystem` 只对「同签名应用」或「系统分区应用」放行；而
`ActivityManagerService.checkComponentPermission()` 对 **uid 0 / SYSTEM_UID(1000)** 是无条件放行的，
**shell(2000) 不在其列**。所以这是「root 专属」，不是「adb 专属」。

> **直接推论：Shizuku 也救不了。** Shizuku 的服务进程就是跑在 **uid 2000（shell）** 上的
> （非 root 设备走 adb/无线调试启动时即如此），拿到的身份与 `adb unroot` 的 shell 完全相同 → **同样会被拒**。
> （只有设备已 root、Shizuku 以 root 启动时才行，但那时你本来就有 root。）

### 2.2 普通第三方 app 身份 → ❌ 被拒

给 PoC 加了一个自检入口（`content call --uri content://com.example.layoutprovider --method SELFTEST_EXPORT`，
由 provider **以自身普通 app 身份**去调 launcher 的 provider），结果：

```
RESULT=ERR: java.lang.SecurityException: Permission Denial: opening provider
com.android.launcher3.LauncherProvider from ProcessRecord{… com.example.layoutprovider/u0a209}
(pid=…, uid=10209) requires app.lawnchair.nightly.permission.READ_SETTINGS
or app.lawnchair.nightly.permission.WRITE_SETTINGS
```

（顺带发现：`app.lawnchair.nightly.permission.READ_SETTINGS` **并未出现在 `pm list permissions`** 里
—— Lawnchair 的 app 模块用 `tools:node="remove"` 移除了权限声明，但 provider 仍引用它；
系统对"被引用但未定义"的权限按签名级处理，因此只有 root / 系统身份能过。）

### 2.3 边界结论

| 能力 | 普通 app | shell / **Shizuku** | **root** |
|---|---|---|---|
| **写**布局（导入） | ✅ 经 Secure Settings 的 `launcher3.layout.provider` | ✅ | ✅ 也可直接 `call(IMPORT_LAYOUT_XML)` |
| **读**布局（导出） | ❌ | ❌ **同样被拒** | ✅ `call(EXPORT_LAYOUT_XML)` |

**即：读当前布局是 root 专属能力**（同签名 / 系统分区应用也行，但那是另一回事）。
免 root 的产品**只能"整份重建"**；要做到"增量"就必须先能读到当前布局，而那条路对普通 app **和 Shizuku 都是关着的**。

> 另查：Lawnchair 只注册了 Android 的 `LauncherBackupAgent`（云备份），**没有用户可见的"导出布局文件"入口**
> → 也不存在"让用户手工导出后喂给 app"的免 root 绕道。

---

## 3. 导出格式的两个观察

1. **应用被导出成 `<autoinstall>`**（不是 `<appicon>`）—— 印证了 `LauncherLayoutBuilder.putApp()` 的实现，
   也印证了笔记 06 §2 的表格（导出器走 `TAG_AUTO_INSTALL`）。
2. 属性顺序不固定（导出器用 `HashMap` 存 attrs），且**不带命名空间前缀**。
   我们的解析器按"无命名空间优先 + res-auto 兜底"读取，兼容。

---

## 4. 增量（合并）模式的实现与实测

### 4.1 设计

Provider 若发现 `<filesDir>/current-layout.xml` 存在，就切到**合并模式**：

```
① 解析 current-layout.xml → 已有文件夹(名+成员) / dock 顺序 / 单图标
② dock   ：按用户原顺序保留（过滤已卸载）→ 空位用优先级候选补齐
③ 文件夹 ：逐个过滤失效成员
            ≥2 → 原样保留（标题不变）
            =1 → 降级为桌面单图标（Launcher3 本来就不允许 1 项文件夹）
④ 新应用 ：已安装但未出现在任何位置的 → 按分类
            同名分类文件夹存在 → 【追加进去】（保留用户的组织方式）
            否则 → 攒新桶（≥2 建新文件夹，=1 单图标）
⑤ 重排   ：全部槽位过一遍占位网格（跳过屏幕 0 第 0 行）
```

### 4.2 实测

**输入构造**（在导出的 XML 上做两处改动，模拟真实变化）：
- 删掉 `效率` 文件夹里的 `com.google.android.calendar` 一行 → 模拟"Calendar 新装/被移出"
- 在 `工具` 文件夹里插入 `com.fake.gone` → 模拟"已卸载但布局里还留着的失效项"

**Provider 输出**：

```
规划结果: 共 18 个应用; 【合并模式】保留文件夹 4 个, 新增 1 个应用, 清理失效 1 项;
          桌面 文件夹4 + 单图标2 + 部件0 = 6 个槽位, 占 1 屏; dock=4
```

**落库核对**（22 行、0 冲突）：

| 文件夹 | 成员 | 说明 |
|---|---|---|
| 效率 (id=1) | Drive / Gmail / **Calendar** | **Calendar 被自动补回**，且是追加进用户原有的「效率」文件夹 |
| 工具 (id=5) | Clock / Files / Settings | **`com.fake.gone` 已被清理** |
| 影音 (id=9) | Photos / YT Music / YouTube | 原样保留 |
| 其他 (id=13) | Safety / TMoble / Voice Search | 原样保留 |
| 桌面单图标 | Contacts / Maps | 原样保留 |
| dock | Phone / Messages / Chrome / Camera | **用户原顺序保留** |

**这就是"增量"的意义**：重跑工具**不会毁掉用户手工建立的分组**，只做"补新、清失效、重排位置"。

### 4.3 产品化路径（**已订正**）

| 形态 | 免 root？ | 能读当前布局？ | 能增量？ | 说明 |
|---|---|---|---|---|
| **纯免 root app** | ✅ | ❌ | ❌ | 只能整份重建（= 我们已有的能力） |
| **app + Shizuku** | ✅ | ❌ | ❌ | **Shizuku 跑在 uid 2000，与 shell 同权限 → 同样被拒** |
| **app + root** | ❌ | ✅ | ✅ | `call(EXPORT_LAYOUT_XML)`，或直接读 `launcher.db`（路径②） |
| **app + 自我记录式增量** | ✅ | ❌（不需要） | ⚠️ 部分 | 见下 |

> ⚠️ **订正说明**：本文档早期版本推荐「app + Shizuku」为最可行形态，**那是错的**——
> Shizuku 的服务进程就是 uid 2000(shell)，与 `adb unroot` 的 shell 完全同权限，实测被拒（§2.1）。
> 结论：**读布局是 root 专属能力，Shizuku 救不了。**

#### 免 root 的折中：「自我记录式增量」（**已实现并实测 ✅**）

读不到 launcher 的真实布局，但 **provider 可以记住自己上次生成的布局**：
每次输出后另存一份到 `<filesDir>/last-generated.xml`，下次以它作为合并基线。

| 基线来源 | 优先级 | 需要 root |
|---|---|---|
| `current-layout.xml`（launcher 真实布局） | 高 | ✅ 需要 |
| `last-generated.xml`（自我记录） | 低（前者不存在时用） | ❌ **不需要** |

| | 基于 launcher 真实布局 | 基于自我记录（免 root） |
|---|---|---|
| 保留**我们生成**的分组 | ✅ | ✅ |
| 感知用户**手工拖动 / 新建文件夹** | ✅ | ❌（会被覆盖） |
| 需要 root | ✅ 需要 | ❌ 不需要 |

**实测（第三轮，无 root）**：

```
规划结果: 共 18 个应用; 【合并模式 · 基线 last-generated.xml(自我记录)】
          保留文件夹 4 个, 新增 1 个应用, 清理失效 0 项; 桌面 文件夹4 + 单图标2 = 6 个槽位; dock=4
```

构造：把 `last-generated.xml` 里的 Calendar 删掉 → 重跑 → provider 判定 Calendar 为"新应用" →
按分类**补回用户原有的「效率」文件夹**；4 个文件夹与 dock 顺序全部保留，22 行 0 冲突。

> 代价说清楚：这条路径**保证"重跑不丢我们自己的分组"，但感知不到用户的手工改动**
> （用户手工新建的文件夹会被下一次规划覆盖）。要真保住手工改动，**只有 root**。

**实现要点**（`LayoutProvider.java`）：`openFile()` 生成 XML 后顺手写一份
`<filesDir>/last-generated.xml`；`CurrentLayout.load()` 先找 `current-layout.xml`，
没有再退到 `last-generated.xml`，并把实际用的基线写进日志（`合并模式 · 基线 …`）。

---

## 5. 复现

```bash
# 1) 导出当前布局 —— ⚠️ 必须 root（uid 2000 会被 SecurityException 拒）
adb -s emulator-5554 root
adb -s emulator-5554 shell "content call --uri content://app.lawnchair.nightly.settings \
     --method EXPORT_LAYOUT_XML" > export-raw.txt
# 2) 提取 XML（去掉 "Result: Bundle[{KEY_LAYOUT=" 前缀与 ", KEY_RESULT=…}]" 后缀）→ current-layout.xml
# 3) 喂给 provider
export MSYS_NO_PATHCONV=1
adb -s emulator-5554 push current-layout.xml /data/data/com.example.layoutprovider/files/current-layout.xml
adb -s emulator-5554 shell "chmod 644 /data/data/com.example.layoutprovider/files/current-layout.xml"
# 4) 触发导入
adb -s emulator-5554 shell "pm clear app.lawnchair.nightly"
adb -s emulator-5554 shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
adb -s emulator-5554 logcat -d -s LayoutProviderPoC | grep 规划结果
```

**退出合并模式**：删掉 `current-layout.xml` 即回到全量模式。

---

## 6. 遗留

- **"读布局"是 root 专属**：Shizuku（shell）实测被拒；Lawnchair 也没有用户可见的"导出布局"入口。
  想免 root 做增量，只能退到「自我记录式增量」（§4.3）。
- `IMPORT_LAYOUT_XML` 走 `call()` 的**权限同样受限**，所以免 root 下导入仍走 Secure Settings。
- 合并目前只认"文件夹 / dock / 单图标"三类；**用户手工放的 appwidget 会被忽略**（会被重新规划掉）。
- 未验证：真机 / 厂商 ROM 是否同样如此（本机真机未接）。
