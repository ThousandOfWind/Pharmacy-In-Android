# Pharmacy-In-Android

一个药箱数据保存在本地、日常功能可离线使用的安卓小程序；仅手动检查和下载 GitHub 更新时联网。交互围绕「看药箱 → 拍照核对 → 入库 → 更新余量」组织：

- **药箱首页**：药品卡片、搜索，以及临期、已过期、日期待补充筛选。
- **独立添加页**：拍照、相册或手动录入；保存按钮固定在底部，未保存返回时确认放弃。
- **本地中文 OCR**：照片识别后展示候选和原文，用户核对后再入库；不自动覆盖手工填写。
- **药品详情**：放大照片、查看包装信息、更新余量、编辑资料、移除与撤销。
- **余量＋单位**：区分未知与 0，不跨单位相加，不使用统一低余量阈值。
- **本地持久保存**：Room 数据库、应用私有目录照片、屏幕旋转和系统重建时恢复填写草稿。
- **GitHub 更新**：首页“更新”检查公开 Release，展示版本/说明，手动下载并校验后交给 Android 确认安装；不上传药箱数据或保存 GitHub 凭据。

交互设计和数据规则见 [docs/interaction-flow.md](docs/interaction-flow.md)。

## 数据字段

- 药品名称（必填）
- 有效期至（可选，`YYYY-MM` 或 `YYYY-MM-DD`；月份精度按月末计算）
- 主要功效（可选）
- 余量及单位（可选，非负整数；有数量时必须选择单位）
- 备注（可选）
- 药品照片（可选，支持正面、有效期等多张照片，每条最多 12 张）

## 技术实现

- Jetpack Compose（UI）
- Room（本地数据库）
- ML Kit 中文文字识别（本地 OCR）
- FileProvider + 相机拍照

## 本地开发与构建（Windows）

环境要求：

- JDK 17，配置 `JAVA_HOME`，将 `%JAVA_HOME%\bin` 加入 `PATH`。
- Android SDK，安装 `platform-tools`、`platforms;android-34` 和 `build-tools;34.0.0`，并接受对应 SDK 许可。
- 配置 `ANDROID_HOME` 指向 SDK，将 `%ANDROID_HOME%\platform-tools` 加入 `PATH`。
- 在项目根目录创建 `local.properties`（已被 Git 忽略），填写本机 SDK 路径，例如：

```properties
sdk.dir=C:/Users/zhiruizhu/AppData/Local/Android/Sdk
```

本仓库自带 Gradle Wrapper，无需额外安装 Gradle。在项目根目录运行：

```powershell
.\gradlew.bat --no-daemon assembleDebug
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。
Lint 报告：`app/build/reports/lint-results-debug.html`。
保留 5 个药箱核心规则 UT，另有 3 个必要更新规则 UT（版本编码、元信息与大小边界、下载地址约束）。不新增 UI／仪器测试套件。

页面布局、拍照权限、实际药盒识别、旧数据升级及更新下载/系统安装授权需在专用模拟器或设备上按需检查；UT 不代表这些真实设备流程已经验证。

### Code agent 本地验收 skill

项目自带 [pharmacy-local-e2e](.pi/skills/pharmacy-local-e2e/SKILL.md)，默认使用固定版 **Maestro 2.11.0 CLI**，可选使用官方 MCP。Pi 在项目受信任后发现它；运行 `/reload` 后可用 `/skill:pharmacy-local-e2e` 显式调用。其他支持 Agent Skills 的工具也可加载该目录。

先按 [本地配置说明](.pi/skills/pharmacy-local-e2e/references/local-testing.md) 设置被 Git 忽略的 `.pi/pharmacy-e2e.local.json`，然后在**本次开发 worktree 根目录**运行：

```powershell
node .pi/skills/pharmacy-local-e2e/scripts/e2e.mjs doctor
node .pi/skills/pharmacy-local-e2e/scripts/e2e.mjs start
# 用户明确确认该 AVD 可丢弃、允许本次清空测试 App 数据后：
node .pi/skills/pharmacy-local-e2e/scripts/e2e.mjs run --allow-test-data-reset
node .pi/skills/pharmacy-local-e2e/scripts/e2e.mjs stop
```

该入口只操作明确配置的 `Pharmacy_E2E_*` 专用 AVD，构建并校验当前 APK 后安装，执行少量手工业务验收；默认流程会清空该测试 App 的数据。AVD 名称不能证明数据可丢弃，每次 `run` 必须另有用户授权并显式传入 `--allow-test-data-reset`，否则在设备操作前拒绝执行。结果、APK hash、JUnit 和失败诊断写入当前 worktree 的 `build/local-e2e/`，不上传云端、不接入 GitHub Actions、不新增 Gradle UI／仪器测试框架。不要用于日常手机；默认通过不代表实际拍照或真实药盒 OCR 已验证。

手机需 Android 8.0（API 26）或更高版本。连接手机并在手机上授权 USB 调试后，可手动安装：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

此 APK 为调试版本，不是发布包。应用运行时使用本地数据库及内置 OCR；首次构建仍需联网下载依赖。
也可使用 Android Studio 打开项目根目录，选择 JDK 17 后同步 Gradle。
设置用户环境变量后，请重新打开终端或 IDE。

## APK Pipeline（GitHub Actions）

仓库已配置自动构建 APK 的流水线：

- Workflow 文件：`.github/workflows/build-apk.yml`
- 触发方式：
  - 手动触发（`workflow_dispatch`）
  - 推送到 `main/master`
  - Pull Request
- PR / 非 main 构建：仅验证和上传 `pharmacy-debug-apk` artifact，不发布更新、不使用固定签名私钥。
- main 构建：UT、Lint、APK 构建通过后，上传 artifact 并自动发布公开 Release（APK + `update.json`）。
- 自动版本：`versionName = 1.0.<workflow run_number>`，`versionCode = run_number + 1`。版本可有间隔，重跑同一版本不替换已发布文件。只将比已有版本更新的发布标记为 latest。
- 固定调试签名保存在仓库 Actions secret `PHARMACY_DEBUG_KEYSTORE` 中，私钥不进入 Git/日志/artifact；只在 main 构建临时恢复并清理。缺少 secret 时拒绝用临时签名发布。
- 不要删除或轮换该 secret，否则后续 APK 无法覆盖现有安装。本地/PR APK 的签名不能作为 main 更新通道使用。

## 下载及应用内更新

1. 打开仓库的 [最新 Release](https://github.com/ThousandOfWind/Pharmacy-In-Android/releases/latest)。
2. 下载附件 `app-debug.apk`，传到手机安装。公开 Release 下载不需要 GitHub 登录。
3. 后续在药箱首页点击 **更新** → **下载更新** → **安装更新**。首次可能需在系统设置中允许本应用安装未知应用，返回后仍由系统确认安装。

下载只允许本仓库 Release 和受信任的 GitHub HTTPS 下载跳转；校验 SHA-256、大小（最大 150 MiB）、包名、版本及与已安装应用一致的签名。不支持静默安装或自动卸载。网络失败、缺少发布或拒绝授权不会影响本地药箱。

**旧 APK 迁移注意：** 历史 Actions/本地 APK 的签名可能不同；新固定签名不保证能覆盖旧安装。签名不一致时应用内更新会阻止安装。请保留旧应用及药箱数据，不要直接卸载或清除数据；目前尚无备份导出功能，应先解决备份/迁移。首次使用固定签名 APK 后，后续同签名新版才能正常覆盖并保留数据。

Actions 的 `pharmacy-debug-apk` artifact 仍保留，内含 APK 与 `update.json`；下载 artifact 通常需要 GitHub 登录。应用更新使用公开 Release，不使用该下载入口。

如需命令行安装（已连接 adb）：

```bash
adb install -r app-debug.apk
```

## 说明

当前为本地药箱管理第一版，不提供用药建议或自动服药计划。OCR 只提取明确标注的候选字段；生产日期和有效期时长不会被当作到期日期。保存前应以实际包装为准。

数据库版本 3 使用显式的版本 2 → 3 迁移，不再静默清空旧记录。旧记录缺失单位或有效期格式不明确时，显示待补充/待核对，不自动推断。旧缓存照片若仍可读取，会转存到应用私有目录；已丢失的缓存照片无法恢复。

照片保存在应用私有目录，清缓存不会删除新照片；卸载应用或清除应用数据仍会删除药箱。暂不提供备份导出、照片单独删除或长期撤销历史。
