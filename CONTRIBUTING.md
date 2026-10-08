# 贡献指南

本项目是**研究型 PoC**（验证 Launcher3 官方布局通道 + 落地免 root 一键整理）。欢迎 issue 与 PR。
动手前请先读 [`README.md`](README.md) 与 [`HANDOFF.md`](HANDOFF.md)，了解范围、现状与已知坑。

## 支持范围

仅支持 **Launcher3 系**启动器（Pixel Launcher / Lawnchair 等）。Nova 等第三方启动器不在范围内。

## 环境

- JDK（`javac` / `keytool` 在 PATH）
- Android SDK（含 build-tools 与 platform）
- Python 3
- **不需要 Gradle**：构建走 `poc/build.sh`（aapt2 + d8 + zipalign + apksigner）

```bash
cd poc
bash build.sh          # 产出 poc/poc-layoutprovider.apk
```

SDK 路径与版本自动探测，可用 `ANDROID_SDK_ROOT` / `TARGET_API` / `BT_VERSION` / `PYTHON` 覆盖。

## 回归基线（改动布局逻辑时必做）

不靠"重跑看看没坏"，而是抓取重构前后的生成 XML 做**逐字节比对**：

```bash
bash poc/tools/capture-golden.sh <输出目录>
```

## references/ 还原

`references/` 因体积与版权不入仓，用脚本按钉住的 commit 确定性还原：

```bash
bash tools/fetch-references.sh
```

## 约定

- 提交信息建议使用 `feat:` / `fix:` / `docs:` / `refactor:` / `chore:` 前缀。
- 可选本地钩子：`pip install pre-commit && pre-commit install`。
- **请勿修改** `poc/artifacts/**`、`*.png`、`*.db`、`*.jar` 等实验产物 / 二进制。
- 许可：本项目以 Apache-2.0 发布；提交即表示你同意以同一许可分发你的贡献。
