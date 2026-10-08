# 05 · Launcher3 官方「布局导入/导出」机制（重大发现）

> 来源：Lawnchair 源码（Apache-2.0）
> `src/com/android/launcher3/util/LayoutImportExportHelper.kt`
> `src/com/android/launcher3/model/LayoutParserFactory.kt`
> `src/com/android/launcher3/LauncherSettings.java`
> `src/com/android/launcher3/AutoInstallsLayout.java`
> `src/com/android/launcher3/util/LauncherLayoutBuilder.kt`
> `src/com/android/launcher3/model/ModelDbController.java`
> 精读日期：2026-09-30
> 一句话：**AOSP Launcher3 内置了一套完整的「桌面布局导入/导出」通道**——布局以 **XML** 表示，通过 **BlobStore** 或 **ContentProvider** 传递，由 **Secure Settings** 的 `launcher3.layout.provider` 键触发。

---

## 0. 为什么这是重大发现

之前我们只有四条路径（无障碍拖拽 / 直写数据库 / 解析备份 / 自建 launcher），都需要"逐个搬图标"或"用户换桌面"。

**这条通道是第五条**：**一次性导入整个布局**，而且**不需要 root**（用 adb 写一次 Secure Settings 即可触发）。它把"自动排列"从"逐个拖拽"变成"整份布局替换"。

## 1. 布局表示：XML（不是数据库）

导出时由 `LauncherLayoutBuilder` 生成 XML。`AutoInstallsLayout.java` 定义了全部 tag：

| Tag | 含义 |
|---|---|
| `workspace` | 根节点 |
| `appicon` | 应用图标 |
| `folder` | 文件夹（可嵌套 `appicon`） |
| `appwidget` | 小部件 |
| `shortcut` | 深链接快捷方式 |
| `autoinstall` | 自动安装（`TAG_AUTO_INSTALL`） |
| `searchwidget` | 搜索小部件 |
| `include` / `extra` | 引入 / 附加 |

## 2. 导出流程（`exportModelDbAsXml`）

```
遍历 dataModel.itemsIdMap
  ├─ container == CONTAINER_DESKTOP  → builder.atWorkspace(cellX, cellY, screenId)
  ├─ container == CONTAINER_HOTSEAT  → builder.atHotseat(screenId)
  └─ 其他 container → 跳过
按 itemType 分派：
  ├─ ITEM_TYPE_APPLICATION → putApp(packageName, className, userType)
  ├─ ITEM_TYPE_DEEP_SHORTCUT → putShortcut(packageName, id, userType)
  ├─ ITEM_TYPE_FOLDER → putFolder(title) + 递归子项
  └─ ITEM_TYPE_APPWIDGET → putWidget(providerPkg, providerClass, spanX, spanY, userType)
→ 产出 XML 字符串
```

**注意**：只导出 `DESKTOP` 和 `HOTSEAT` 容器——**抽屉/文件夹内部的项不导出**（文件夹会递归）。

## 3. 导入流程（`importModelFromXml`）

```kotlin
val digest = SHA-256(data)
val handle = BlobHandle.createWithSha256(digest, LAYOUT_DIGEST_LABEL, 0, LAYOUT_DIGEST_TAG)
blobManager.openSession(blobManager.createSession(handle)).use { session ->
    session.openWrite(0, -1).write(data)
    session.allowPublicAccess()
    session.commit {
        Secure.putString(resolver, LAYOUT_PROVIDER_KEY, createBlobProviderKey(digest))  // ← 触发
        modelDbController.createEmptyDB()   // ← 清空数据库
        model.forceReload()                 // ← 强制重载
        Secure.putString(resolver, LAYOUT_PROVIDER_KEY, null)  // ← 清理
    }
}
```

**三个关键动作**：写 blob → **清空数据库** → **强制重载**。

## 4. 读取流程（`LayoutParserFactory.createWorkspaceLoaderFromAppRestriction`）

Launcher 启动时会检查 Secure Settings：

```kotlin
val systemLayoutProvider = Secure.getString(contentResolver, Settings.LAYOUT_PROVIDER_KEY)
if (TextUtils.isEmpty(systemLayoutProvider)) return null
```

然后走**两条通道**：

| 通道 | 触发条件 | 做法 |
|---|---|---|
| **Blob 通道** | 值以 `blob://` 开头 | `BlobStoreManager.openBlob(handle)` 读 XML |
| **ContentProvider 通道** | 其他值（当作 authority） | `resolveContentProvider(authority)` → `getLayoutUri` → `openInputStream` |

两条路最终都交给 `AutoInstallsLayout` 解析 XML 并建库。

## 5. ContentProvider 契约（第三方可实现的入口）

`ModelDbController.getLayoutUri()` 揭示了完整契约：

```java
new Uri.Builder().scheme("content").authority(authority).path("launcher_layout")
    .appendQueryParameter("version", "1")
    .appendQueryParameter("gridWidth",  numColumns)
    .appendQueryParameter("gridHeight", numRows)
    .appendQueryParameter("hotseatSize", numDatabaseHotseatIcons)
```

即第三方 App 只需实现：

```
content://<你的authority>/launcher_layout?version=1&gridWidth=4&gridHeight=6&hotseatSize=5
```

返回**布局 XML 文本**即可。**网格尺寸会作为参数传给你**——你据此生成匹配当前设备网格的布局。

### 5.1 补充（P5 实测新增）：launcher 侧还开了 export/import 的 `call()` API

上面讲的是「launcher 来读**你的** provider」。反过来，**launcher 自己也对外提供**了布局读写接口
（`LauncherProvider.call()`，见 `references/lawnchair/src/com/android/launcher3/LauncherProvider.java`）：

| 方法 | 参数 | 返回 |
|---|---|---|
| `EXPORT_LAYOUT_XML` | — | `Bundle.getString("KEY_LAYOUT")` = 当前布局 XML |
| `IMPORT_LAYOUT_XML` | `arg` = 布局 XML 字符串 | `KEY_RESULT` |

- authority = **`<launcher包名>.settings`**
- 权限 = `<launcher包名>.permission.READ_SETTINGS` / `WRITE_SETTINGS`，均为 **`signatureOrSystem`**
- **实测边界（含一次自我纠错）**：
  - ✅ **只有 root（uid 0）能调** —— `adb root` 后 `content call … EXPORT_LAYOUT_XML` 直接返回 XML
  - ❌ **普通 app（uid 10xxx）被拒**：`SecurityException: Permission Denial … requires …READ_SETTINGS`
  - ❌ **shell（uid 2000）同样被拒**（`adb unroot` 后复测确认）→ **Shizuku 也不行**（它跑的就是 uid 2000）

→ 这条决定了「能不能做增量排列」：**读当前布局是 root 专属能力**，
免 root（含 Shizuku）只能整份重建。详见 **`notes/10-P5-export-import-and-merge.md`**。

## 6. 权限门槛（关键，决定可行边界）

| 环节 | 需要 | 说明 |
|---|---|---|
| 写 `LAYOUT_PROVIDER_KEY`（Secure Settings） | **`WRITE_SECURE_SETTINGS`** | 系统级权限，普通 App **拿不到**；需 adb `pm grant` 或 root 或系统签名 |
| `BlobStoreManager.createSession` + `allowPublicAccess` | BlobStore 相关权限 | 同样非普通 App 可得 |
| **ContentProvider 通道** | 无额外权限 | App 自己实现即可 |

**结论**：**ContentProvider 通道是"半边开放"的**——
- **提供方**（返回 XML）：普通 App 就能做；
- **触发方**（写 Secure Settings）：需要 **adb 或 root**。

**但 adb 就够了**（不需要 root）：

```bash
adb shell settings put secure launcher3.layout.provider com.your.app
# 然后让 launcher 重启/重载，它会去读你的 ContentProvider
```

## 7. 适用范围

| Launcher | 是否支持 |
|---|---|
| AOSP Launcher3 / Pixel Launcher | ✅ 原生支持 |
| **Lawnchair** | ✅（本机制就在其源码里） |
| 其他 Launcher3 fork | ✅ 大概率支持 |
| **Nova Launcher** | ❌ 独立实现，不支持该机制 |
| 厂商定制（小米/华为） | ⚠️ 未知，需实测 |

## 8. 与四条路径的关系

| 路径 | 操作粒度 | 权限门槛 | 速度 |
|---|---|---|---|
| ① UI 模拟拖拽 | 单个图标 | 无（adb/无障碍） | 慢 |
| ② 直写 `launcher.db` | 整库 | root / Shizuku | 瞬时 |
| ③ 解析备份文件 | 只读 | 无 | — |
| ④ 自建 launcher | 整体 | 换桌面 | — |
| **⑤ 官方布局提供者** | **整份布局** | **adb 或 root（写 Secure Settings）** | **瞬时** |

**⑤ 的独特价值**：**一次导入整份布局**，且用的是官方解析路径（`AutoInstallsLayout`），**比直写数据库更安全**（不怕 schema 演进到 v32）。

## 9. 风险与局限

- **导入会清空并重建数据库**（`createEmptyDB` + `forceReload`）——**失败可能导致布局丢失**，务必先备份。
- 只在**支持该机制的 launcher** 上有效（Launcher3 系；**Nova 无效**）。
- XML 必须符合 `AutoInstallsLayout` 的解析规则（tag/属性严格）。
- 抽屉（`CONTAINER_ALL_APPS`）内的排序**不在此机制范围内**——它只管桌面 + dock。
- 触发需 `WRITE_SECURE_SETTINGS`（adb/root），普通用户门槛仍在。

## 10. 下一步验证（待办）

1. 读 `LauncherLayoutBuilder.kt`，写出**完整的 XML 格式样例**（字段与属性）。
2. 读 `AutoInstallsLayout.java` 的解析逻辑，确认必填属性与容错。
3. 做一个**最小 PoC**：一个返回固定 XML 的 ContentProvider + `adb shell settings put secure`，验证能否在 Lawnchair 上重建布局。
4. 确认 Lawnchair/设备实际是否监听该 key（部分定制 ROM 可能裁剪）。
