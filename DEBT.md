# DEBT.md — 已知技术债登记

本文件登记**已经确认、但尚未处理**的缺陷。每条给出：实测证据（可在本机复算）、影响、建议动作。
新增条目时必须带证据来源；只写"感觉应该改"的不登记。判定基准 commit：`a880f4407af79ac65927cfc294431cb2d2259abe`
（`main` tip，2026-10-12 核对）。

| 编号 | 债项 | 严重度 | 状态 |
|---|---|---|---|
| D-01 | 全仓零自动化测试 | P0 | 未处理 |
| D-02 | 无任何 CI 门禁 | P0 | 未处理 |
| D-03 | shellcheck 6 条告警未修 | P1 | 未处理 |
| D-04 | 发布 APK 未混淆/无 mapping | P1 | 未处理（需先决定是否有意保留） |
| D-05 | `UsageRank` 反向引用 `LayoutPlanner`，依赖图不单向 | P1 | 未处理 |
| D-06 | HANDOFF 中三处数字与代码不符 | P0 | 已处理（见文末） |
| D-07 | shizuku 二进制字节校验未执行（沙箱取不到字节） | P1 | 未处理（需有网/SDK 环境执行） |

---

## D-01 全仓零自动化测试（P0）

**证据**：`git/trees?recursive=1` 共 158 个 blob，路径中含 `test` 或 `spec`（忽略大小写）的文件 **0 个**；
`poc/tools/capture-golden.sh` 只做"抓取 + `mergeMode` 断言"，不比对内容，失败时 `sys.exit(1)`。

**影响**：布局算法（`LayoutPlanner.java`，35,639 B，含 `render()` 与 `esc()` 等纯函数）的任何改动都没有
回归网。当前唯一反馈来自真机走查。

**建议动作**：优先给纯函数建测试面 —— `LayoutPlanner.render(Plan,int,int)` 与 `esc(String)` 无 Android
运行时依赖，可用 `javac` + 一个 `main` 断言器在无设备环境下跑；`poc/test/` 目录与
`poc/tools/run-tests.sh` 尚不存在，需要新建。涉及 `Shizuku` / `ContentProvider` 的部分留待有
Robolectric 或真机流水线时再补，不要为凑覆盖率写假断言。

## D-02 无任何 CI 门禁（P0）

**证据**：`.github/` 下只有 `PULL_REQUEST_TEMPLATE.md`、`ISSUE_TEMPLATE/bug_report.yml`、
`ISSUE_TEMPLATE/feature_request.yml` 三个文件；**没有 `.github/workflows/` 目录**（本仓 Actions 页面上
只有 Dependabot 的 dynamic 运行，不是本仓定义的流水线）。

**影响**：D-01 的缺失没有任何东西能发现。判"有没有门禁"要看 `contents/.github/workflows`，
不能看 Actions 计数——后者会把 Dependabot 的 dynamic 运行算进去。

**建议动作**：有了 D-01 的测试面之后再建 `ci.yml`，否则门禁只会绿在自己身上（一条空 workflow
比没有更糟，因为它会让人以为有保护）。

## D-03 shellcheck 6 条告警未修（P1）

**证据**：`poc/build.sh` 与 `poc/tools/capture-golden.sh`，逐条定位如下（行号为基准 commit 上的位置）：

| 位置 | 规则 | 现状 | 修法 |
|---|---|---|---|
| `build.sh:51` | SC2010 | `ls -1 \| grep -E …` 解析 `ls` 输出取版本目录 | 改 `find -maxdepth 1 -mindepth 1 -type d -name "${prefix}[0-9]*"` |
| `build.sh:109` | SC2046 | `$(find "$HERE/src" -name '*.java')` 未加引号，路径含空格会裂 | 先 `mapfile -t SRC_FILES < <(find …)`，再 `"${SRC_FILES[@]}"` |
| `build.sh:121` | SC2046 | 同上，`*.class` | 同上，`mapfile -t CLASS_FILES` |
| `capture-golden.sh:62` | SC2086 | `$DEV` 有意分词（可能为空） | 保留行为，加 `# shellcheck disable=SC2086` 并注明原因 |
| `capture-golden.sh:84` | SC2086 | 同上 | 同上 |
| `capture-golden.sh:86` | SC2086 | 同上 | 同上 |

**影响**：`build.sh` 在有空格的路径下（Windows 本机常见 `Program Files`）会构造错误的 `d8` 命令行。
**这不是假想风险**：`$HERE` 来自脚本自身路径。

**建议动作**：前三条是真修，后三条是"有意为之 + 显式抑制"。修完以 `shellcheck` 0 告警为验收，
不要改成"降级为 warning 级别"来凑绿。

## D-04 发布 APK 未混淆/无 mapping（P1）

**证据**：`build.sh` 中 `d8` 出现 5 次，`r8` / `proguard` / `minify` 出现 **0 次**；`dex` 段之后直接
打包，没有 mapping 归档步骤。

**影响**：`poc/poc-layoutprovider.apk`（78,353 B）是可直接反编译的 Java 类名与方法名。对一个 PoC
这可能正是想要的（便于讲解），但**必须是决定的结果而不是遗漏**。

**建议动作**：二选一并写进 README ——（A）`d8` 后加 R8 步骤并归档 `mapping.txt` 到 release 资产；
（B）在 `build.sh` 顶部与 README 显式声明"PoC 有意不混淆，生产 fork 请勿复用"。当前状态是两者都不是。

## D-05 `UsageRank` 反向引用 `LayoutPlanner`（P1）

**证据**：`poc/src/com/example/layoutprovider/UsageRank.java:40`

```java
private static final String TAG = LayoutPlanner.TAG;
```

同时 `LayoutPlanner` 正向调用 `UsageRank`：`LayoutPlanner.java:441` 有
`UsageRank usage = needUsage ? UsageRank.load(ctx) : null;`，另有 4 处把 `UsageRank` 用作形参/局部类型
（`:652`、`:678`、`:710`、`:719`）。两条边相加，这一层是**环**，而 `HANDOFF.md` 的架构图原先写作"依赖单向"。

**影响**：任何"把 `LayoutPlanner` 拆薄"的改动都会连带 `UsageRank`，D-01 没有测试时这种连带看不见。

**建议动作**：把 `TAG` 提到 `AppConfig`（或一个极小的 `LogTag` 常量类），`UsageRank` 只依赖它；
然后 `HANDOFF.md:591` 的"单向"才成立。改完请把 HANDOFF 的依赖行同步。

---

## D-06 文档漂移（P0，已处理）

`HANDOFF.md` 有三处与代码不符，已在 `edfb2bfd976f0dd1d83aba5b5f3674fdae97c626`（2026-10-12）改正：

| 位置 | 原写法 | 实测 |
|---|---|---|
| `:587` | `LayoutProvider.java`「1248 行 → 约 430 行」 | 676 行 |
| `:591` | 依赖单向 `LayoutProvider → LayoutPlanner → AppConfig` | 另有 `LayoutPlanner → UsageRank` 与 `UsageRank.java:40` 的反向引用（即 D-05） |
| `:823` | 「9 个职责单一的文件」「APK 78.4 KB」 | 8 个 `.java`；APK 78,353 B（= 76.5 KiB，原写法的 78.4 是按十进制 KB） |

**这一条留在这里而不是删掉**，是为了给后面的文档改动留一个提醒：本仓的文档数字是**会漂移的**，
`HANDOFF.md` 是唯一的架构说明，改代码时顺手改它，或者把这些数字改成可执行的测量。

---

## D-07 shizuku 二进制字节校验未执行（P1）

**背景**：`poc/shizuku/` 下 11 个二进制（aidl-13.1.5.aar、aidl-classes.jar、annotation-1.3.0.jar、
api-13.1.5.aar、api-13.1.5.pom、api-classes.jar、provider-13.1.5.aar、provider-13.1.5.pom、
provider-classes.jar、shared-13.1.5.aar、shared-classes.jar）的字节校验（sha256）需在**有出网或 SDK 渠道**的环境执行。
沙箱内 codeload tar 60s 截断、raw.githubusercontent 出口超时、jsDelivr 403、api.github.com 限流 ⇒ 取不到字节、算不出 sha256。

**待办（可交接）**：在能下载这 11 个文件的环境执行下述命令，产出 `poc/shizuku/SHA256SUMS`：

```sh
cd poc/shizuku
sha256sum aidl-13.1.5.aar aidl-classes.jar annotation-1.3.0.jar api-13.1.5.aar api-13.1.5.pom \
  api-classes.jar provider-13.1.5.aar provider-13.1.5.pom provider-classes.jar \
  shared-13.1.5.aar shared-classes.jar > SHA256SUMS
sha256sum -c SHA256SUMS   # 期望 11 行全 OK
```

**期望输出格式**（`SHA256SUMS` 每行 `<sha256>  <文件名>`，共 11 行）：

```
<sha256>  aidl-13.1.5.aar
<sha256>  aidl-classes.jar
… 共 11 行 …
```

**落地后**：把 `sha256sum -c SHA256SUMS` 校验接进 `poc/build.sh` 的 shizuku 段（`SZK_JARS` 赋值前），
命令与期望见工单 ITEM 6；回滚点 = 删 `SHA256SUMS` 并 `git checkout <sha> -- poc/build.sh`。
