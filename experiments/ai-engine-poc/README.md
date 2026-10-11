# Android AI engine PoC

两套真实 SDK、相同的合成药箱与脚本化 OpenAI Chat Completions 响应：

- **Koog 1.3.0**：只引用 `agents-core` + Ktor transport，不引入全功能 `koog-agents`。用 graph nodes 连接流式请求、工具执行、结果回传。
- **OpenAI Kotlin 4.1.0**：客户端负责协议与 SSE；小型自有循环负责拼接工具参数、白名单、校验、结果回传与预算。
- **Baseline**：相同 Android/Kotlin/fixture/coroutine 环境，不引用 AI SDK。

这是技术验证，不是可用于真实药品的 AI 功能。三个包名为 `com.thousandofwind.aipoc.baseline` / `.openai` / `.koog`，不覆盖 `com.thousandofwind.pharmacy`。

## 安全与范围

- APK 没有 INTERNET 权限；Ktor MockEngine 不开 socket，不访问任何真实供应商。
- `poc.invalid`、`synthetic-not-a-key` 与药品记录都是固定假数据，不读取环境凭据、Pi 登录、真实数据库或照片。
- 两边使用实际 SDK 的请求编码、SSE/响应解析和工具接口，不以假的 Engine 代替 SDK。
- Ktor MockEngine 不支持 SSECapability；fixture 用 Ktor 自带 response adapter 包装内存字节流。此处 opt-in 的 internal API **仅用于隔离 PoC**，不得直接用于生产网络 transport。
- 工具只有只读 `search_medicines`，查询结果必须真实回传给 SDK 后才提供脚本回答。未知工具和非字符串参数不能读取库存。
- 会话只验证完整 user/assistant 文本的 JSON round-trip 与下一轮重建；没有 Room 会话 UI、跨供应商转换或完整工具历史持久化。
- 不测试 OAuth、真实模型质量、TLS、真实网络取消、药盒 OCR 或医疗安全效果。

## 构建

独立 Gradle build 不被根 `settings.gradle.kts` include，因此不会把 PoC 依赖带进正式 APK。根项目保持原工具链；此 build 使用 JDK 17、Gradle 8.13（固定 SHA-256）、Kotlin 2.3.10、AGP 8.5.2、API 34 / min API 26。

在本目录创建 Git 忽略的 `local.properties`，或设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`。不在配置里存 API Key。

Windows：

```powershell
.\gradlew.bat --no-daemon :koog-app:testDebugUnitTest :openai-app:testDebugUnitTest `
  :baseline-app:assembleDebug :koog-app:assembleDebug :openai-app:assembleDebug `
  :koog-app:lintDebug :openai-app:lintDebug
python measure.py
```

Linux 使用 `./gradlew`。依赖首次下载需要联网，测试本身不产生 API 费用。现有药箱 UT 未增加或删除；PoC 每边仅 3 个 JVM UT，将七个检查组合为三个维护单元，没有引入仪器/UI 测试框架。

## 模拟器复现

只使用经过项目本地验收 skill `doctor` 确认的专用 AVD，所有 adb 命令显式指定 serial，不用于日常设备。无需清空任何应用数据。不要使用药箱 skill 的默认 `run`，它的 appId 和清数据流程不是为这两个 PoC 包设计的。

```powershell
adb -s <dedicated-serial> emu avd name
adb -s <dedicated-serial> install -r openai-app/build/outputs/apk/debug/openai-app-debug.apk
adb -s <dedicated-serial> shell am start -n com.thousandofwind.aipoc.openai/.MainActivity
# 等待出现七个 PASS 和 PoC completed，再检查或截图。
adb -s <dedicated-serial> install -r koog-app/build/outputs/apk/debug/koog-app-debug.apk
adb -s <dedicated-serial> shell am start -n com.thousandofwind.aipoc.koog/.MainActivity
```

这两个 Activity 自动运行已审阅的合成场景，将引擎放在 `Dispatchers.Default`，主线程只更新结果。原药箱和旧数据均不操作。退出 Activity 会取消本次任务。已经由其他会话启动的 AVD 不由本任务关闭；PoC 包保留供检查，不擅自删除设备内容。

## 本次结果

本地 Windows JDK 17 / API 34 专用 AVD（2026-10-11）：两边 JVM UT、Lint、APK 构建和 Android 运行均通过。

| 场景 | OpenAI Kotlin | Koog |
| --- | --- | --- |
| 中文流式文本、分片工具参数、真实结果回传、JSON 会话恢复后继续 | PASS | PASS |
| 未注册删除工具，库存读取为 0 | PASS，返回工具错误后结束（2 请求） | PASS，最终由调用上限阻止（4 请求） |
| 数字 query，库存读取为 0 | PASS | PASS |
| 401，不读药箱、不自动重试/切换模型 | PASS | PASS |
| 非 JSON SSE，失败且无后续工具 | PASS | PASS |
| 收到首个 delta 后取消内存响应流，无后续工具 | PASS | PASS |
| 持续工具调用，最多 4 请求后拒绝继续 | PASS | PASS |

Koog 未知工具的默认处理在此 fixture 下没有形成 OpenAI 兼容的 tool-role 错误回传，因此触发四次请求预算，而不是正常回答。这不是“已具备生产级权限策略”；正式采用仍需明确实现拒绝/终止逻辑。

两个初始 Activity 曾因缺少 `kotlinx-coroutines-android` 失败，补齐后重新构建运行；引擎放在主线程也导致设备检查不能完整结束，改为 Default dispatcher 后七项通过。不能只依靠 JVM UT 推定 Android 运行正常。

### 包体（相同 debug，未做 R8 shrink）

| 方案 | APK bytes | 相对 baseline | DEX method references |
| --- | ---: | ---: | ---: |
| Baseline（含相同 fixture） | 3,533,782 | — | 31,874 |
| OpenAI Kotlin + 轻量循环 | 6,544,895 | +3,011,113 bytes（2.87 MiB） | 41,165 |
| Koog 核心 + graph | 10,010,407 | +6,476,625 bytes（6.18 MiB） | 96,067 |

这些不是正式药箱 APK 的预测值，也不是只统计 SDK Jar。fixture、依赖版本解析、debug dex/打包方式都会影响数据；通过 `measure.py` 复现当前 APK 的大小、DEX 引用数和 hash。DEX 引用数跨 dex 求和，不等于去重的方法定义数。构建耗时受首次下载和缓存影响，不作为本次速度结论。

## 选型结论

**按当前目标优先选 OpenAI Kotlin + 小型受控循环**：只读业务工具、聊天和简单会话不需要 graph/persistence/RAG 框架；本次样例代码也没有体现 Koog 的代码量优势。Koog 适合未来确实需要多套原生协议、复杂 agent 流程时再评估，或只引用其模型客户端层。

这次没有把任何方案接入正式药箱，也没有为将来可能的依赖升级主项目。正式采用 OpenAI Kotlin 4.x 同样需要 Kotlin 2.x 与 Compose compiler/KSP 配套升级；Koog 1.3.0 则要求更高版本。独立 build 证明两者在 API 34 可以构建运行，**不代表已验证主项目的 Compose/Room 升级迁移**。主项目升级应与选定方案的正式集成一起做，避免现在引入无必要变化。

后续最小实现：

1. 原生连接设置与本地加密凭据，不复用 Pi auth 文件。
2. 首版 OpenAI-compatible 协议，多连接、模型 ID 与能力标记；其他原生协议逐项增加。
3. SessionRepository（Room）、模型协议 adapter、受限循环与药箱只读工具分层。
4. 流式消息、停止、药品卡片和工具状态；不依赖模型内部推理。
5. HTTP 状态/流中断/大小边界、不同代理的兼容差异与真实网络取消验证。
6. 官方支持的 OAuth 独立评估；本 PoC 不证明 ChatGPT/Claude/Copilot 订阅可用于此 App。

## 上游与许可

- [Koog](https://github.com/JetBrains/koog/releases/tag/1.3.0)，Apache-2.0；[Compose 示例](https://github.com/JetBrains/koog/tree/1.3.0/examples/demo-compose-app)。
- [OpenAI Kotlin](https://github.com/aallam/openai-kotlin/releases/tag/4.1.0)，MIT，非 OpenAI 官方 SDK。
- Ktor / Kotlin / Coroutines / Serialization：Apache-2.0。依赖通过 Maven 引用，没有复制上游引擎实现；发行时仍须保留适用许可证通知。
