---
name: pharmacy-local-e2e
description: 使用本地 Maestro 验证 Pharmacy-In-Android 新功能或修复：构建当前 worktree APK，在专用 Android 模拟器上操作药箱、中文录入、搜索、详情及余量更新，检查断言和失败截图。用户要求本地验收，或修改用户可见行为后使用；不用于 CI、云设备、日常手机或实际药盒 OCR 识别率认证。
compatibility: Windows, Node.js 22+, JDK 17, Android SDK API 34 and a task-isolated AVD covered by project standing authorization or explicit approval, Maestro CLI 2.11.0 installed separately.
---

# Pharmacy 本地功能验收

默认使用 **Maestro 2.11.0**。这是本项目已实测的本地方案，不安装 Appium/agent-device，不新增 Gradle UI／仪器测试框架，不扩建全面 E2E 套件。

## 首先确认范围

- 在**本次开发 worktree 的项目根目录**执行命令。脚本以当前 Git 根目录构建，不会使用旧对比 APK。
- 仅功能开发／修复、实验范围内必要的本地验证或用户明确要求运行时操作模拟器。审阅、规划、纯文档修改或仅安装 skill 不授权设备/业务操作。
- **只改文档、指令、注释或日志/错误提示文案而不改变执行逻辑时，不运行本验收流程，也不主动跑 UT、Lint 或构建**；仅检查内容、diff、必要语法和独立 review。脚本文件中的纯文案改动不例外，不能因为调用 skill 的 `run` 会构建 APK 就把它当作文本验证。现有 CI 自动运行不等于此类改动需要测试或 APK。
- 若当前项目存在 `AGENTS.md`，先读取它；同时阅读 [README 的本地开发与构建说明](../../../README.md)，不存在的指导文件不作为运行前提。保护未提交修改，遵循项目 worktree 开发规则；项目内 skill、脚本和文档改动同样需要走提交、PR 与 review 流程。本 skill 的调用仅执行本地验收，不独立触发 push、PR、合并或部署。
- 阅读 [references/local-testing.md](references/local-testing.md)，了解独立 AVD 创建、任务预留、环境、键盘和常见失败。**本仓库 [AGENTS.md](../../../AGENTS.md)「独立本地测试的常设授权」已允许任务隔离测试，不逐次等待用户 approve**；来源/身份满足下述边界后直接执行。授权来自用户和项目指令，不来自安装 skill；其他仓库须核对其授权，不能照搬本仓库授权。
- 将本 skill 的 `scripts/e2e.mjs` 解析为**绝对路径**；以下 `<runner>` 代表它。不要把占位符原样交给 shell。

## 无需再次 approve 的边界

- 为本任务新建专用 AVD，未登录个人账号、未导入真实用户数据、不与其他任务共享；仅使用合成测试数据。同一任务可在创建来源/身份记录仍可核实时复用。
- 在任务 worktree 的 Git 忽略 `build/local-e2e/` 记录创建来源、所属任务、AVD 名称/端口及测试数据性质。名称前缀和本机配置不是可丢弃证明；runner 的预留/启动记录也不能代替数据来源判断。
- 覆盖已安装工具下的创建、启动、安装测试 APK、本 App 操作/测试数据清空、证据采集和本任务设备关闭。不包括真机、共享/来源不明设备、用户账号/数据、其他任务设备、全设备 wipe 或删除历史 AVD/产物；越界或身份冲突时停止并询问必要授权，或准备全新隔离设备。
- 测试授权不免除 PR 独立审查、必需人工审批、分支保护或实验交付限制。review 频率按项目 `AGENTS.md` 平衡质量与效率：每个 PR 至少一次覆盖主要改动，小修补自检不机械重复 request，高风险/实质变化按需复审；不自动安装/升级工具、接受 SDK 许可、调用云测试或绕过安全控制。

## 执行步骤

1. `node <runner> doctor`：检查环境、固定 Maestro 版本、专用 AVD 和当前项目。
   - 配置文件是当前项目根目录 `.pi/pharmacy-e2e.local.json`，只含工具路径和专用 AVD 名称，不能放凭据。
   - 缺工具、未获 ADB 授权、端口被其他 AVD 占用时停止，报告阻塞；不自动安装、升级、禁用安全策略或操作其他设备。
2. **先隔离设备，再 `node <runner> start`**：每个并行任务必须使用符合常设授权或另行获明确授权的独立 AVD 与空闲偶数端口对；不能共享旧的 Comparison AVD。`start` 预留 AVD 名称和端口直到 `stop`，仅启动当前任务设备；已运行设备仅在本任务预留和启动身份均匹配时复用，不附着到其他任务。
3. 核实上述任务隔离边界后，**由 agent 直接运行** `node <runner> run --allow-test-data-reset`，不再请求逐次人工 approve：构建当前项目 debug APK，校验 AVD 身份，安装 APK，运行最小验收，保存 SHA-256、Git 提交、JUnit 和诊断产物。
   - 默认 [flows/smoke.yaml](flows/smoke.yaml) 会**清空专用模拟器上本 App 的测试数据**。每次 `run` 仍显式传入 `--allow-test-data-reset`，避免子流程清数据漏检；它是 agent 按常设授权选择的执行标记，不是新的用户审批要求。来源不明或不符合授权时不能自动补参数，先停止处理。
   - 执行前仍重新验证设备身份和执行标记。smoke YAML 继续检查 `PHARMACY_ALLOW_TEST_DATA_RESET`，只由 runner 在标记通过后传入，不能直接设置变量绕过入口。缺标记时 runner 仍拒绝执行，保持 fail-closed。
   - 可以 `node <runner> run --allow-test-data-reset --flow <absolute-yaml-path>` 执行本任务相关的小流程。即使自定义流程不清数据，仍传执行标记并满足同一隔离边界，因为入口不声称提供 YAML 沙箱。流程必须在当前项目或 skill 内，根 `appId` 必须保持 `com.thousandofwind.pharmacy`。运行前审阅所有子流程；不允许云操作或切换到私人应用。
   - 没有“跳过构建并当作当前版本通过”的选项。
4. 失败后 `node <runner> inspect` 获取精简的界面文字、ID、边界；`node <runner> screenshot` 保存当前屏幕。通过 `read` 查看失败截图和相应层级，定位问题后重跑。
   - 不仅凭编译、RPC 成功、点击成功或“保存成功”提示判断功能正确。断言保存后的卡片、详情、余量和重启后的数据。
   - 不把真实 Bug 的断言删除、设 optional 或改成恒真来让流程通过。
5. 验证完成或启动超时后 `node <runner> stop`：校验本 runner 启动的 PID、创建时间、AVD 和端口。ADB 就绪时正常关闭；未就绪时仅终止通过上述校验的自有进程树。身份不符时拒绝，不关闭其他运行者启动的设备。
6. 报告：验证的 APK hash／提交、用例、通过／失败／阻塞、证据目录和未验证项。不提交截图、日志、机器路径或数据库。

## 并行任务设备边界

- 独立 worktree 不等于独立设备：任务必须分别配置 `avdName` 和 `port`，例如 Backup API34 / 5560 与 Copilot API34 / 5558；端口仅为示例，使用前探测。
- AVD 与端口预留跨命令存续；`run/inspect/screenshot/stop` 要求本任务预留，不能跳过 `start` 操作旧运行设备。预留冲突或前次启动残留时停止，不自动删除、抢占或改选另一台设备。
- 所有 ADB、Maestro、临时测试脚本与手动验收都明确指定本任务串口；禁止省略 `-s/--udid` 或使用共享默认设备。非本 runner 的工具也必须尊重此边界。
- 若看到其他任务界面/数据、设备身份变化或端口占用，立即停止操作，不 force-stop/卸载/清空其他 App，不关闭其他任务的模拟器。
- 本 runner 不自动创建、下载或 wipe AVD。符合项目常设授权时，agent 可直接用已安装 SDK 官方 avdmanager 单独创建任务 AVD，无需再次 approve；操作步骤见本地配置说明，不复制个人设备或账号数据。

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
