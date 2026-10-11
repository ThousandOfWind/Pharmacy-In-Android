# Pharmacy-In-Android

一个本地离线的安卓药箱管理小程序。交互围绕「看药箱 → 拍照核对 → 入库 → 更新余量」组织：

- **药箱首页**：药品卡片、搜索，以及临期、已过期、日期待补充筛选。
- **独立添加页**：拍照、相册或手动录入；保存按钮固定在底部，未保存返回时确认放弃。
- **本地中文 OCR**：照片识别后展示候选和原文，用户核对后再入库；不自动覆盖手工填写。
- **药品详情**：放大照片、查看包装信息、更新余量、编辑资料、移除与撤销。
- **余量＋单位**：区分未知与 0，不跨单位相加，不使用统一低余量阈值。
- **本地持久保存**：Room 数据库、应用私有目录照片、屏幕旋转和系统重建时恢复填写草稿。

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
仅保留 5 个必要的核心规则 UT：日期解析、有效期状态、表单与余量校验、OCR 明确标签候选、避免误取药名和生产日期。不新增 UI／仪器测试套件。

页面布局、拍照权限、实际药盒识别和旧数据升级需在专用模拟器或设备上按需检查；UT 不代表这些真实设备流程已经验证。

### Code agent 本地验收 skill

项目自带 [pharmacy-local-e2e](.pi/skills/pharmacy-local-e2e/SKILL.md)，默认使用固定版 **Maestro 2.11.0 CLI**，可选使用官方 MCP。Pi 在项目受信任后发现它；运行 `/reload` 后可用 `/skill:pharmacy-local-e2e` 显式调用。其他支持 Agent Skills 的工具也可加载该目录。

先按 [本地配置说明](.pi/skills/pharmacy-local-e2e/references/local-testing.md) 设置被 Git 忽略的 `.pi/pharmacy-e2e.local.json`，然后在**本次开发 worktree 根目录**运行：

```powershell
node .pi/skills/pharmacy-local-e2e/scripts/e2e.mjs doctor
node .pi/skills/pharmacy-local-e2e/scripts/e2e.mjs start
node .pi/skills/pharmacy-local-e2e/scripts/e2e.mjs run
node .pi/skills/pharmacy-local-e2e/scripts/e2e.mjs stop
```

该入口只操作明确配置的 `Pharmacy_E2E_*` 专用 AVD，构建并校验当前 APK 后安装，执行少量手工业务验收；默认流程会清空该测试 App 的数据。结果、APK hash、JUnit 和失败诊断写入当前 worktree 的 `build/local-e2e/`，不上传云端、不接入 GitHub Actions、不新增 Gradle UI／仪器测试框架。不要用于日常手机；默认通过不代表实际拍照或真实药盒 OCR 已验证。

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
- 产物：`app-debug.apk`

## 下载 APK 到本地使用

1. 打开仓库的 **Actions** 页面。
2. 选择 **Build APK** 工作流并进入某次成功的运行。
3. 在页面底部 **Artifacts** 下载 `pharmacy-debug-apk`。
4. 解压后得到 `app-debug.apk`，传到手机安装即可。

如需命令行安装（已连接 adb）：

```bash
adb install -r app-debug.apk
```

## 说明

当前为本地药箱管理第一版，不提供用药建议或自动服药计划。OCR 只提取明确标注的候选字段；生产日期和有效期时长不会被当作到期日期。保存前应以实际包装为准。

数据库版本 3 使用显式的版本 2 → 3 迁移，不再静默清空旧记录。旧记录缺失单位或有效期格式不明确时，显示待补充/待核对，不自动推断。旧缓存照片若仍可读取，会转存到应用私有目录；已丢失的缓存照片无法恢复。

照片保存在应用私有目录，清缓存不会删除新照片；卸载应用或清除应用数据仍会删除药箱。暂不提供备份导出、照片单独删除或长期撤销历史。
