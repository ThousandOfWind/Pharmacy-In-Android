---
name: pharmacy-local-e2e
description: 使用本地 Maestro 验证 Pharmacy-In-Android 新功能或修复：构建当前 worktree APK，在专用 Android 模拟器上操作药箱、中文录入、搜索、详情及余量更新，检查断言和失败截图。用户要求本地验收，或修改用户可见行为后使用；不用于 CI、云设备、日常手机或实际药盒 OCR 识别率认证。
compatibility: Windows, Node.js 22+, JDK 17, Android SDK API 34 and an authorized dedicated AVD, Maestro CLI 2.11.0 installed separately.
---

# Pharmacy 本地功能验收

默认使用 **Maestro 2.11.0**。这是本项目已实测的本地方案，不安装 Appium/agent-device，不新增 Gradle UI／仪器测试框架，不扩建全面 E2E 套件。

## 首先确认范围

- 在**本次开发 worktree 的项目根目录**执行命令。脚本以当前 Git 根目录构建，不会使用旧对比 APK。
- 仅功能开发／修复或用户明确要求运行时操作模拟器。审阅、规划或仅安装 skill 不授权业务操作。
- 若当前项目存在 `AGENTS.md`，先读取它；同时阅读 [README 的本地开发与构建说明](../../../README.md)，不存在的指导文件不作为运行前提。保护未提交修改，遵循项目 worktree 开发规则；项目内 skill、脚本和文档改动同样需要走提交、PR 与 review 流程。本 skill 的调用仅执行本地验收，不独立触发 push、PR、合并或部署。
- 阅读 [references/local-testing.md](references/local-testing.md)，了解环境、键盘和常见失败。
- 将本 skill 的 `scripts/e2e.mjs` 解析为**绝对路径**；以下 `<runner>` 代表它。不要把占位符原样交给 shell。

## 执行步骤

1. `node <runner> doctor`：检查环境、固定 Maestro 版本、专用 AVD 和当前项目。
   - 配置文件是当前项目根目录 `.pi/pharmacy-e2e.local.json`，只含工具路径和专用 AVD 名称，不能放凭据。
   - 缺工具、未获 ADB 授权、端口被其他 AVD 占用时停止，报告阻塞；不自动安装、升级、禁用安全策略或操作其他设备。
2. `node <runner> start`：仅启动配置的专用模拟器。不会清理日常设备。
3. 先取得用户对**配置的 AVD 可丢弃、允许本次清空测试 App 数据**的明确授权，再运行 `node <runner> run --allow-test-data-reset`：构建当前项目 debug APK，校验 AVD 身份，安装 APK，运行最小验收，保存 SHA-256、Git 提交、JUnit 和诊断产物。
   - 默认 [flows/smoke.yaml](flows/smoke.yaml) 会**清空专用模拟器上本 App 的测试数据**。AVD 名称前缀不证明数据可丢弃；未明确授权时停止询问，不自行补上确认参数。每次 `run` 均需显式 opt-in，避免子流程清数据漏检；在执行流程前重新验证设备身份及本次确认。
   - 可以 `node <runner> run --allow-test-data-reset --flow <absolute-yaml-path>` 执行本任务相关的小流程。即使自定义流程不清数据，也要求确认，因为入口不声称提供 YAML 沙箱。流程必须在当前项目或 skill 内，根 `appId` 必须保持 `com.thousandofwind.pharmacy`。运行前审阅所有子流程；不允许云操作或切换到私人应用。
   - 没有“跳过构建并当作当前版本通过”的选项。
4. 失败后 `node <runner> inspect` 获取精简的界面文字、ID、边界；`node <runner> screenshot` 保存当前屏幕。通过 `read` 查看失败截图和相应层级，定位问题后重跑。
   - 不仅凭编译、RPC 成功、点击成功或“保存成功”提示判断功能正确。断言保存后的卡片、详情、余量和重启后的数据。
   - 不把真实 Bug 的断言删除、设 optional 或改成恒真来让流程通过。
5. 验证完成 `node <runner> stop`：仅关闭本 runner 启动且仍匹配的专用 AVD；不会关闭其他运行者启动的设备。
6. 报告：验证的 APK hash／提交、用例、通过／失败／阻塞、证据目录和未验证项。不提交截图、日志、机器路径或数据库。

## 新功能验收策略

- 优先调整一条最小流程或编写临时 YAML，覆盖新行为和一个重要异常，不堆积大而重复的套件。
- 当前产品**不跨单位相加、不采用统一低库存阈值**。不要使用旧版的总余量/低余量统计断言。
- 日期用明确测试数据；若验收依赖“临期/过期”，按设备日期生成边界输入，不固定某年永久有效。
- 优先来自 `inspect` 的完整文字或稳定 ID；Maestro 的 text 是全字符串正则，转义 ASCII `()[]+?`。Compose testTag 需 `testTagsAsResourceId` 才能被外部工具定位；未经必要性确认，不为了测试修改应用 UI。
- 不存旧坐标或旧树，界面变更后重新检查。必要时用 `scrollUntilVisible`；不要把改变模拟器密度当作修复应用适配问题。
- 使用 [flows/dismiss-keyboard.yaml](flows/dismiss-keyboard.yaml) 的条件关闭方式；不要反复 BACK，也不要无条件 `hideKeyboard` 导航到桌面。
- 固定图片 OCR 回归、实际相机拍照、真实药盒识别率分开报告。默认 smoke 只验证手工业务，不声称拍照/OCR 已通过。

## MCP（可选，不是默认依赖）

CLI 和 Maestro 官方 MCP 使用同一个执行框架。当前 skill 默认走 CLI，减少工具上下文并把设备检查集中在 runner。

如果后续明确配置了官方 stdio `maestro mcp`，先通过 runner 确认专用设备，再通过 MCP discovery 查 `inspect_screen`、`run`、`take_screenshot` 的实际 schema。每次指定经确认的 `device_id`，只运行本 App 已审阅的流程；保持 `MAESTRO_CLI_NO_ANALYTICS=1`。不调用 cloud/login 工具，不开放 HTTP 监听，也不让 MCP 与其他设备驱动并发控制同一设备。构建和安装仍用 runner，不从历史 APK 推断当前代码正确。
