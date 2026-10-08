# 12 · 「免 root 通道」全面排查 + 三级应用策略

> 执行日期：2026-10-02
> 目的：回答「**普通用户（不 root、不跑 adb）能不能用起来**」这个问题，逐条排查所有可能通道。
> 结论：**唯一能覆盖「生产设备 + 免 root」的是 Shizuku**；同时落地了三级应用策略。

---

## 1. 逐条排查结果

| # | 通道 | 结论 | 依据 |
|---|---|---|---|
| ① | **Play Auto Installs partner 布局** | ❌ **不通** | `Partner.findSystemApk()` 用 `queryBroadcastReceivers(intent, **MATCH_SYSTEM_ONLY**)` → **必须是系统分区应用**；且 Lawnchair 的 `build.gradle` 里写死 `ENABLE_AUTO_INSTALLS_LAYOUT = false`，`AutoInstallsLayout.get()` 直接返回 null |
| ② | **`LauncherProvider.call()` 的 `EXPORT/IMPORT_LAYOUT_XML`** | ❌ **root 专属** | 权限 `signatureOrSystem`；实测 **普通 app ❌ / shell(2000) ❌ / root ✅**（详见 `notes/10` §2） |
| ③ | **直接读写 `launcher.db`** | ❌ | `/data/data/<launcher>/` 受沙箱保护，**shell 与 Shizuku 都读不到**（SELinux + 目录权限） |
| ④ | **Secure Settings 的 `launcher3.layout.provider`** | ✅ **唯一可行** | 但需要 `WRITE_SECURE_SETTINGS` —— 见下表 |

> ① 这条**补上了 `notes/05` 留下的疑问**：那条"Play Auto Installs"分支看起来能免 root，
> 实际上被 `MATCH_SYSTEM_ONLY` + Lawnchair 的编译期开关**双重封死**。

---

## 2. 能力矩阵（写 Secure Settings + 触发重建）

| 动作 | 普通 app | shell(2000) / **Shizuku** | **root** | `pm grant` 后（userdebug） |
|---|---|---|---|---|
| 写 `launcher3.layout.provider` | ❌ | ✅ | ✅ | ✅ |
| `pm clear <launcher>`（触发重建） | ❌ | ✅ | ✅ | ❌（仍需 shell/root） |
| `am start … HOME` | ❌ | ✅ | ✅ | ❌ |
| 读当前布局（导出） | ❌ | ❌ | ✅ | ❌ |

**关键实测**（模拟器重启后的健康系统上）：

```
uid=2000(shell)
$ adb shell pm clear app.lawnchair.nightly
Success          ← 2 秒完成
```

→ **shell 能做完「写设置 + 重置桌面 + 回桌面」全流程** ⇒ **Shizuku 能实现真正的免 root 一键**。

---

## 3. 已落地：三级应用策略

`LayoutProvider.applySecureSetting()`：

```
① 直接写   Settings.Secure.putString(...)     ← 需要 WRITE_SECURE_SETTINGS
                                                （protectionLevel 含 development，
                                                 在 userdebug/eng 上可 `adb shell pm grant` 一次性授予）
② 借 root  su -c settings put ...
③ 都失败   返回提示，用户改用界面上的 adb 命令
```

`APPLY_FULL` 在此基础上再执行 `pm clear <launcher>` + `am start … HOME`（这两步需 root 或 shell）。

**实测**：

| 场景 | 结果 |
|---|---|
| 未授权 + 无 root | ❌ 返回「无法执行 su…（设备未 root，请改用下面的 adb 命令）」 |
| `adb shell pm grant com.example.layoutprovider android.permission.WRITE_SECURE_SETTINGS` | ✅ 静默成功（userdebug） |
| 授权后再点「尝试直接应用」 | ✅ **`OK（直接写入）`**，且 `settings get secure launcher3.layout.provider` 确认已写入 |

> ⚠️ `pm grant` 这条路**只在 userdebug/eng 构建有效**（`development` 保护级别）。
> 生产设备（user 构建）依然需要 root 或 Shizuku。

---

## 4. UI 同步更新

「应用到桌面」区改为两条命令 + 三级策略说明：

- **① 仅写设置**：`adb shell settings put secure launcher3.layout.provider com.example.layoutprovider`
- **② 一键全流程**：`adb shell "settings put … && pm clear <launcher> && am start -a … HOME"`
  （`<launcher>` 由页面从 `PLAN_JSON` 的 `launcher` 字段动态填充）

截图：`poc/artifacts/ui-v3-apply.png`

---

## 5. Shizuku 接入方案（下一步，未实施）

**为什么是它**：Shizuku 的服务进程跑在 **uid 2000**，而实测 shell 能完成全部三步。

需要的东西：

1. 依赖：`dev.rikka.shizuku:api`（API 类）+ `dev.rikka.shizuku:provider`（权限申请用的 provider + 资源）
2. 手搓构建链下的处理：
   - 从 AAR 里取 `classes.jar` → 与我们的源码一起 `d8` 进 dex
   - `shizuku-provider` 的 `<provider>` / `<activity>` 声明合并进我们的 manifest
   - 它的 `res/` 用 `aapt2 compile` 后与我们一起 `aapt2 link`
3. 代码：
   ```java
   Shizuku.addRequestPermissionResultListener(...);
   if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
       Shizuku.requestPermission(REQ);
   }
   // 拿到权限后：
   Shizuku.newProcess(new String[]{"sh","-c",
       "settings put secure launcher3.layout.provider com.example.layoutprovider"
       + " && pm clear " + launcherPkg
       + " && am start -a android.intent.action.MAIN -c android.intent.category.HOME"}, null, null);
   ```
4. 测试难点：Shizuku 的非 root 启动需要**无线调试配对**（模拟器上可行但繁琐）。

---

## 6. 踩坑记录：模拟器「假失败」

排查过程中 `pm clear` 一直超时（111s 后 `Broken pipe`），我一度以为**是权限不足**。

**真因**：模拟器连续跑了约 15 小时且被反复 `pm clear`，**`system_server` 已崩**：

```
$ adb shell "pm list packages | wc -l"
cmd: Can't find service: package      ← PMS 已经没了
```

**重启模拟器后，同一条 `pm clear` 2 秒就 Success。**

> **教训**：在模拟器上排查"权限/命令失败"之前，**先确认系统服务是活的**
> （`pm list packages`、`cmd package resolve-activity` 能正常返回）。
> 长时间运行的模拟器会退化，把系统故障误判成权限问题是很容易犯的错。
