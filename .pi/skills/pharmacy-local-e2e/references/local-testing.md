# 本地环境与复现

## 用户级工具，项目级配置

Maestro 独立安装，不添加 Android Gradle 依赖。固定 2.11.0，升级应由用户另行决定。

在项目根目录创建 **Git 忽略** 的 `.pi/pharmacy-e2e.local.json`：

```json
{
  "javaHome": "C:/path/to/jdk-17",
  "androidSdk": "C:/path/to/Android/Sdk",
  "maestroHome": "C:/path/to/maestro-2.11.0/maestro",
  "avdName": "Pharmacy_E2E_Comparison_API34",
  "port": 5556
}
```

只使用用户已批准的专用 AVD。脚本不会创建、删除或 wipe AVD；不会自动下载工具或改 PATH／安全策略。Node 从现有环境运行。`javaHome` 和 `androidSdk` 省略时使用现有 `JAVA_HOME`、`ANDROID_HOME`／`ANDROID_SDK_ROOT`。Maestro 用 Java 调用官方 CLI，避免 Windows `.bat` 和多层 shell 引号问题。

每个新 worktree 需要自己的本机配置。不要把绝对路径和日志提交到 Git。许可信息：https://github.com/mobile-dev-inc/Maestro 。官方文档：https://docs.maestro.dev/get-started/maestro-mcp 和 https://docs.maestro.dev/maestro-cli/how-to-install-maestro-cli 。

## 验收入口

从目标项目根目录运行：

```powershell
node <skill-absolute-path>/scripts/e2e.mjs doctor
node <skill-absolute-path>/scripts/e2e.mjs start
# 仅在用户已明确确认该 AVD 可丢弃、允许本次清空测试 App 数据后：
node <skill-absolute-path>/scripts/e2e.mjs run --allow-test-data-reset
node <skill-absolute-path>/scripts/e2e.mjs inspect
node <skill-absolute-path>/scripts/e2e.mjs screenshot
node <skill-absolute-path>/scripts/e2e.mjs stop
```

默认 smoke 覆盖：空名称 → 中文录入 → 数量需单位 → 保存后库存卡片 → 搜索 → 详情 → 更新余量 → 进程重启持久化。它使用前缀 `E2E-` 的合成数据，不触发相机或进入私人应用。

**单独确认破坏性操作**：AVD 名称或前缀不能证明数据可丢弃。每次运行都需要用户明确允许本次清空该 AVD 的测试 App 数据，再传 `--allow-test-data-reset`；无参数时在构建、安装或设备操作之前拒绝执行。即使使用自定义流程也需要确认，不能只检查根 YAML 而漏掉子流程。流程执行前重新检查 AVD 身份及本次 opt-in，并在 `result.json` 中记录。skill 不可把该参数当作无条件默认值。

脚本为每个当前项目保留独立状态目录；同时使用一个宿主机临时目录中的设备锁，避免不同 worktree 的本 runner 同时操作同一串口。发现已有锁时停止，不自动删“过期”锁；确认其他进程结束后再人工清理。该锁不约束其他工具，所以不要同时开 agent-device、其他 Maestro 或手工操作此 AVD。

默认产物位于当前 worktree 的 `build/local-e2e/<timestamp>/`。`result.json` 保存项目路径、Git commit、修改状态、APK SHA-256、执行状态与耗时；JUnit XML 和 Maestro debug 产物保存在同一目录。退出码非零就是失败／阻塞，不得当作通过。

## 已观察的易错点

- **键盘**：在先前本机对比中，一次无条件 `hideKeyboard` 将应用带到桌面。附带子流程只在 `android:id/input_method_nav_back` 可见时点击它；不存在时不发送 BACK。如果平台 ID 改变，先 inspect，再修正关闭逻辑，不盲点坐标。
- **正则**：`text` 默认是全字符串正则。比如 `当前余量（可选）` 的中文括号无需 ASCII 转义，但 ASCII `剩余 8 (片)` 需要转义括号。复制真实完整文字，不凭截图猜文案。
- **截图路径**：在指定 `--test-output-dir` 时用相对名称 `smoke`；绝对截图路径可能被安全限制拒绝，不关闭保护。
- **异步保存**：等待回到药箱并出现确切记录和余量；仅看 Snackbar 不能证明 Room 写入完成。
- **适配**：旧版 420 dpi 下编辑入口遮挡曾被两套工具同时发现。当前 smoke 保持专用 AVD 正常屏幕；不可通过降低密度掩盖新布局 Bug。
- **驱动冲突**：Maestro 与其他 instrumentation 工具在同一 AVD 上可能冲突。先结束另一工具自己的会话。不要 force-stop 宿主机所有 Java、ADB 或其他设备上的应用。
- **敏感产物**：Maestro debug 可能包含设备日志和屏幕文字。仅用无账号的专用测试 AVD；报告前审阅，不把日志推送到仓库。

## 后续 MCP 配置

本 skill 不自动增加 MCP 配置。CLI 已能让 agent 实际运行本地应用并返回失败证据，且没有驻留 MCP 服务和全部工具 schema 的上下文开销。

需要更自由探索时再配置官方 stdio MCP，不创建 HTTP 服务。先 `doctor/start`、检查 device_id，并始终用本次构建 APK；`run` 结果须检查 `isError` 和业务断言，不仅检查 JSON-RPC 传输成功。只用 local tools，不启用云端功能。Pi 技能重新扫描用 `/reload`；显式调用 `/skill:pharmacy-local-e2e`。
