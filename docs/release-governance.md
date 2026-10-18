# 发布 / 回滚 规范（Android-IconAutoArrange 本仓本地化）

> **来源与权威**：公共条款见 `ReSerendipity/.github` 的 `REPOSITORY_GUIDELINES.md` §10（发布 / 分发统一规范）。
> 本文件是 `RELEASE_GOVERNANCE_TEMPLATE.md` 在本仓的本地化落地，只记录**本仓特有**内容，**不重写、不抵触** §10 的公共条款。
> **现状声明**：本仓当前为 PoC 工具仓，**尚无发布链**（`.github/workflows/` 仅有 `ci.yml`），下文凡涉及发布链的条目为**待引入**状态的如实记录，不虚构不存在的 workflow 或产物。

---

## 0. 远端优先（根原则，不可改写）

发布链的构建、打包、扫描、上传、推送**全部在 GitHub Actions 远端完成**；本地只做三件事：改版本位、落 CHANGELOG、`git tag` + `git push`。本地不执行打包 / 镜像构建 / 发布上传。

- 本仓现状：**尚无发布链**，因此「远端优先」目前只体现在 **CI 构建校验**上——`.github/workflows/ci.yml` 的 `build` job 在远端执行 `bash poc/build.sh`，验证 APK 可构建，但**不产出、不上传任何 Release 资产**。
- 本仓本地**不产出发布交付物**：`poc/poc-layoutprovider.apk` 为 PoC 演示产物，`.gitignore` 已对 `poc/out/`、`poc/*.idsig`、`poc/debug.keystore` 等构建/签名材料做忽略；它不构成对外发布资产。

## 1. 版本号规范

- 遵循 **SemVer** `MAJOR.MINOR.PATCH`；预发布用 `-alpha.N` / `-beta.N` / `-rc.N`。
- **唯一权威版本位**：**本仓无静态版本声明文件**——无 `build.gradle(.kts)`、无 `package.json`、无 `pyproject.toml`。版本由 `poc/build.sh` 在**构建时注入**：
  - `VERSION_NAME` = `git describe --tags --always --dirty`（本机无 `.git` 时回退 `dev-<UTC 日期>`）；
  - `VERSION_CODE` = `git log -1 --format=%ct`（末次提交 Unix 时间戳；无 `.git` 时回退当前时间）；
  - 二者均可由环境变量 `VERSION_NAME` / `VERSION_CODE` 显式覆盖。
- 当前取值：本仓**尚无任何 tag 与 Release**，故 `git describe --always` 得到**短 SHA**（本次核查为 `68f8615`），`VERSION_CODE` 为末次提交时间戳（对应 `2026-10-12T22:20:16Z`）。
- 必须同步的其它版本位：**无**。`poc/AndroidManifest.xml` **不声明** `android:versionCode` / `android:versionName`，由 `aapt2 link --version-code/--version-name` 注入，故无多点一致性问题。
- 版本位漂移由 `<校验入口>` 钉住：**本仓当前无任何版本一致性校验**（无 `tests/test_version_consistency.*`）——**待定（负责人 + 触发条件：本仓引入 tag / 发布链时一并建立）**。
- 自动化方式：**手动 tag（人工）**。本仓**不使用 release-please**；且因尚无发布链，连「手动 tag」这一步也尚未启用。

## 2. CHANGELOG 管理

- 遵循 **Keep a Changelog**：`Added / Changed / Fixed / Deprecated / Removed / Security`。
- 每次合并 `main` 在 `[Unreleased]` 追加；type 映射 `feat→Added`、`fix→Fixed`、`refactor→Changed`。
- 发版时 `[Unreleased]` → `[VERSION] - YYYY-MM-DD`。
- **本仓现状：已建立 `CHANGELOG.md`**（仓根 `CHANGELOG.md`，Keep a Changelog 格式，当前仅有 `[Unreleased]`）。本仓同时以 `DEBT.md` / `HANDOFF.md` / `notes/` 记录演进，但**均非 Keep a Changelog 格式**，不替代 CHANGELOG。
- 引入本规范要求的 `CHANGELOG.md` 的方式与时间：**待定（负责人 + 触发条件：本仓首次对外发版时）**。本单**不创建 CHANGELOG**。

## 3. 发布流程（远端）

1. 确认 `[Unreleased]` 条目完整
2. 同步版本位（见 §1 清单）
3. 本地打 tag 并推送：`git tag v<X.Y.Z>` → `git push origin v<X.Y.Z>`
4. 远端凭 tag 触发 `<release.yml>` 构建产物
5. 远端建 **draft Release** 并挂资产 → 人工审核后 `published`
6. 盯到终态：`gh run list` 确认绿；红了当场修或 revert 止损

- **本仓现状：本仓当前无发布链。** 上表第 4 步的 `<release.yml>` **在本仓不存在**；`.github/workflows/` 只有 `ci.yml`（`shellcheck` + `build` 两个 job，`on: [push, pull_request]`，**不监听 tag**、**不建 Release**）。因此**现有 APK 构建不在发布链内**，仅是提交/PR 时的构建校验。
- 引入发布链的路径（**待定，负责人 + 触发条件**）：若日后需对外分发 APK，须新增 tag 驱动（`push: tags: v*`）的远端 workflow，并按 §10.1 三条根原则落地。

## 4. 分发产物与完整性

| 产物 | 构建位置 | 标签 / 命名 | 完整性手段 |
|---|---|---|---|
| APK（`poc/poc-layoutprovider.apk`） | **本地 / 远端 CI 构建校验**（尚无发布链，**不上传 Release**） | 无 tag（本仓无 tag / Release） | 当前**无** `SHA256SUMS.txt`（无 Release 资产） |

- 本仓的可分发产物形态：**APK**（由 `poc/build.sh` 用 SDK 自带 `aapt2` + `d8` + `zipalign` + `apksigner` 手搓，**不依赖 Gradle/AGP**），以 `poc/debug.keystore` 做 **debug 签名**。
- 容器镜像：**不适用**（本仓无 Dockerfile / 无镜像链）。
- `latest` 作发布基线：**不适用**（本仓无镜像、无 tag，遵守 §10.3「不以 floating `latest` 作为发布基线」）。
- Trivy 阻断 / `provenance` / `sbom`：**不适用**（本仓无镜像链）。
- 完整性：当前**无发布资产**，故**无 `SHA256SUMS.txt`**；桌面/APK 分发的签名密钥须按 §3 走私有 `<项目名>-KeyBackup`，**密钥不入库**（本仓 `.gitignore` 已忽略 `*.keystore` / `*.jks` / `*.p12` / `backups/`）。
- **不使用 GPG 分离签名**（§10.1 2026-10-18 裁决）。本仓**从未存在** `gpg-signed-release.yml`。

## 5. 回滚

- **判定**（满足任一即触发）：**本仓无发布链、无在线运行服务**，回滚即代码回退——**待定（负责人 + 触发条件：本仓引入对外发布后，按「APK 不可安装 / 关键功能（免 root 通讯、布局写入）回归失败」等具体阈值补充）**。
- **执行**：`git revert <bad-commit>`（保历史、可再 forward）；配置回滚：**不适用**（本仓无线上配置）；不可变产物按上一版 tag 重推：**暂不适用**（本仓无 tag / Release）。
- **破坏性动作**：重指向 / 删除 tag、移动任何远端 ref → **须仓库所有者单独授权**（本仓当前无 tag，此条仍适用）。
- **回滚后验证**：现有可用命令为 `bash poc/build.sh`（构建冒烟）与 `poc/tools/run-tests.sh`（`PlannerTest` 规划器用例）；`tools/capture-golden.sh` 的 golden 回归需真机 / 模拟器 + `adb`，**属本地运行边界，不在 CI 内**。

## 6. SLA / 错误预算

- 本仓为**无在线服务端的 PoC 工具仓**，暂无可用性 SLA 与错误预算定义——**待定（负责人 + 触发条件：本仓对外提供发布分发后）**。不虚构可用性阈值。

## 7. 发布前检查清单

- [ ] 版本位全部同步（见 §1 清单）——本仓无静态版本位，退化为确认 `git describe` 取值符合预期
- [ ] CHANGELOG `[Unreleased]` 已改版本 + 日期——**本仓当前无 CHANGELOG，此条待引入**
- [ ] 测试门禁：`poc/tools/run-tests.sh`（规划器用例）通过；远端 `ci.yml` 的 `shellcheck` + `build` 全绿
- [ ] lint / format：`shellcheck poc/build.sh poc/tools/capture-golden.sh tools/fetch-references.sh` 通过（当前 `DEBT.md` 登记 D-03 尚有 6 条告警待修）
- [ ] 镜像类仓：Trivy / `provenance` / `sbom`——**不适用（本仓无镜像链）**
- [ ] **本地未产出任何发布交付物**（全部远端构建）——本仓当前连远端发布链亦未建立

---

## 差异点采集清单（本仓实际取值）

| 占位符 | 采集来源 | 本仓取值 |
|---|---|---|
| 版本位文件与字段 | 仓内版本声明 | **无静态版本文件**；`poc/build.sh` 构建时注入 `VERSION_NAME` / `VERSION_CODE` |
| 其它需同步版本位 | `grep -rn` 版本串 | **无**（`AndroidManifest.xml` 不声明版本） |
| 版本一致性校验入口 | `<repo>/tests/**` | **无**——待定（负责人 + 触发条件） |
| 自动化方式 | `.github/workflows/` | **手动 tag（人工）**；无 release-please，发布链尚未建立 |
| 发布 workflow 文件名 | `.github/workflows/` | **无发布 workflow**；仅 `ci.yml` |
| 产物形态与标签规则 | 构建 workflow | 形态：**APK**（`poc/build.sh` 本地构建）；**无标签规则**（无 tag / Release） |
| 完整性手段 | Release 资产清单 | **无**（无 Release 资产，无 `SHA256SUMS.txt`；不使用 GPG） |
| 回滚判定阈值 | 运行手册 / 监控 | **待定（负责人 + 触发条件）** |
| 回滚后验证命令 | `tests/smoke` 等 | `bash poc/build.sh`（构建冒烟）；`poc/tools/run-tests.sh`（规划器用例） |
| 技术栈 | `package.json` / `pyproject.toml` / 静态站 | **Java / Android PoC**（手搓 APK，无 Gradle），UI 为 WebView + `poc/assets/ui.html` |
| 部署目标 | `deploy/` / Pages workflow | **无**（本机侧载 APK，属 §10.4 本地运行边界） |

## 禁止事项

- 不得重写 §10 的公共条款（远端优先、tag 驱动、版本位一致、不可变标签、扫描阻断、无 GPG）。
- 不得引入 `latest` 作发布基线；不得保留或新建 GPG 签名流程。
- 不得留空占位符，不得编造阈值。
