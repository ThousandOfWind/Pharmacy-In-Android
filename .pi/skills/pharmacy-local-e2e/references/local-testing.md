# 本地环境与复现

## 用户级工具，项目级配置

Maestro 独立安装，不添加 Android Gradle 依赖。固定 2.11.0，升级应由用户另行决定。

在项目根目录创建 **Git 忽略** 的 `.pi/pharmacy-e2e.local.json`：

```json
{
  "javaHome": "C:/path/to/jdk-17",
  "androidSdk": "C:/path/to/Android/Sdk",
  "maestroHome": "C:/path/to/maestro-2.11.0/maestro",
  "avdName": "Pharmacy_E2E_<Task>_API34",
  "port": 5560
}
```

把 `<Task>` 替换为本任务名称，例如 `Backup`，不能原样使用占位符。每个并行任务使用不同的 AVD 和端口对，5560 只是示例。只使用符合项目常设授权或另行明确批准的专用 AVD。脚本不会创建、删除或 wipe AVD；不会自动下载工具或改 PATH／安全策略。Node 从现有环境运行。`javaHome` 和 `androidSdk` 省略时使用现有 `JAVA_HOME`、`ANDROID_HOME`／`ANDROID_SDK_ROOT`。Maestro 用 Java 调用官方 CLI，避免 Windows `.bat` 和多层 shell 引号问题。

每个新 worktree 需要自己的本机配置。不要把绝对路径和日志提交到 Git。许可信息：https://github.com/mobile-dev-inc/Maestro 。官方文档：https://docs.maestro.dev/get-started/maestro-mcp 和 https://docs.maestro.dev/maestro-cli/how-to-install-maestro-cli 。

## 常设授权下直接创建独立 AVD

先查询 `emulator -list-avds`、现有进程/设备，以及候选偶数端口和下一奇数端口在 IPv4/IPv6 上是否空闲。不读取或复制已有个人 AVD 数据；不终止占用者、不复用实验任务设备。

本仓库 [AGENTS.md](../../../../AGENTS.md) 已给予独立本地测试常设授权：为当前任务新建、不共享、不登录个人账号、不导入用户数据的 AVD，可直接用已安装 SDK 官方 `avdmanager` 创建，无需逐次 approve；不是克隆旧模拟器，也不把历史同名设备视为已授权：

```powershell
# Android SDK 已安装此 system image、设备定义和 JDK 时才执行：
avdmanager.bat create avd --name Pharmacy_E2E_Backup_API34 --package "system-images;android-34;google_apis;x86_64" --device pixel_6
```

- 不传 `--force`，同名已存在就停止核对；缺少 system image、SDK 许可或工具时报告阻塞，不自行下载/接受许可。
- 创建后在任务 worktree 的 Git 忽略 `build/local-e2e/` 记录来源（官方新建、未克隆）、创建时间、所属任务/worktree、AVD 名称/端口和仅合成测试数据。该同一任务的后续复用仍须核实来源/身份记录及数据性质；仅有名称/配置/预留不证明可丢弃。
- 符合常设授权时，创建、测试 App 安装/操作/数据清空及本任务设备关闭可由 agent 直接执行；不再逐次询问用户。来源不明、共享设备、真机或用户数据不在该授权内，需停止并另行授权或准备全新隔离设备。
- 在任务 worktree 的 Git 忽略配置写入新 AVD 和空闲端口，然后 `doctor` → `start`。配置变更不代表可以删除旧模拟器。

## 验收入口

本入口用于需要设备验证的开发/修复/实验或明确验收请求。仅改文档、注释、提示文案而未改变执行逻辑时，只做内容/diff/必要语法和独立 review，不运行 UT、Lint、构建或以下设备验收流程。

从目标项目根目录运行：

```powershell
node <skill-absolute-path>/scripts/e2e.mjs doctor
node <skill-absolute-path>/scripts/e2e.mjs start
# 核实当前任务隔离边界后由 agent 直接执行，无需逐次人工 approve：
node <skill-absolute-path>/scripts/e2e.mjs run --allow-test-data-reset
node <skill-absolute-path>/scripts/e2e.mjs inspect
node <skill-absolute-path>/scripts/e2e.mjs screenshot
node <skill-absolute-path>/scripts/e2e.mjs stop
```

默认 smoke 覆盖：空名称 → 中文录入 → 数量需单位 → 保存后库存卡片 → 搜索 → 详情 → 更新余量 → 进程重启持久化。它使用前缀 `E2E-` 的合成数据，不触发相机或进入私人应用。

**常设授权与执行标记分开**：AVD 名称或前缀不能证明数据可丢弃。符合本仓库任务隔离边界时，agent 每次主动传入 `--allow-test-data-reset`，无需等待用户再次 approve；它明确标记允许清空测试 App 数据，不是逐次人工授权。缺标记时 runner 仍在构建/安装/设备操作之前拒绝执行，自定义流程也不能省略标记。执行前仍核对 AVD/任务预留/启动身份和本次标记，`result.json` 记录执行标记（不声称证明人工审批或数据来源）。smoke YAML 仍在 `launchApp/clearState` 前检查 `PHARMACY_ALLOW_TEST_DATA_RESET`，只由 runner 传入，不直接设置变量绕过入口。越界或无法证明来源/身份时停止处理，不把标记当成授权依据。

脚本为每个当前项目保留独立状态目录；同时使用一个宿主机临时目录中的设备锁，避免不同 worktree 的本 runner 同时操作同一串口。发现已有锁时停止，不自动删“过期”锁；确认其他进程结束后再人工清理。此外，`start` 在宿主机临时目录 `pharmacy-local-e2e-reservations` 中分别预留 AVD 名称与端口，直到已验证的 `stop`；本地 `build/local-e2e/state/device-reservation.json` 保存任务身份。不同 AVD/端口可并行，同 AVD 换端口或同端口换 AVD 都拒绝。`run/inspect/screenshot` 必须有当前 worktree 的预留。

预留不是 OS 沙箱，也无法控制其他工具；所有外部验收脚本/手动操作都必须使用分配串口，不与其他任务共享此 AVD。已有旧 runner 启动但未登记预留的设备不自动迁移/接管，改用单独新 AVD。worktree 移动后身份路径可能不匹配，应在移动前停止自有设备；不能编辑预留冒领所有权。

`start` 记录进程 PID、创建时间、AVD、端口和所属 worktree。启动后如果身份探测、时间校验或 ownership 写入失败，会重新核对该次启动的 PID／AVD／端口及创建时间，回滚自有进程树；无法证明身份或回滚失败时明确报告并停止，不扩大权限或终止其他进程。`stop` 先验证该记录与实际进程匹配；即使启动超时且 ADB 未就绪，也能终止严格匹配的自有进程树，不按进程名称批量终止。不符合创建时间／命令行的 PID（包括复用的 PID）会被拒绝。

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
