# Azure OpenAI 助手

正式助手入口：药箱首页「更多 → AI 助手」。不配置连接或不使用助手时，本地药箱与 OCR 照常使用。

## 接入范围

- 用户自有 Azure 公有云资源、API Key 和 deployment，不创建 Azure 资源、不申请 RBAC/组织权限、不保存 Azure 登录密码。
- v1：填写 HTTPS 资源根地址，如 `https://your-resource.openai.azure.com`，请求 `/openai/v1/chat/completions`，model 为部署名称，无 api-version。
- 传统部署接口：请求 `/openai/deployments/<deployment>/chat/completions?api-version=...`，默认 version `2024-10-21`，使用 api-key。
- 白名单根域：`openai.azure.com`、`cognitiveservices.azure.com`、`services.ai.azure.com`。不支持代理、自定义域、其他云、任意 URL 路径或重定向。保留 TLS 校验，禁止明文。
- deployment 必须支持 Chat Completions/流式文本；药箱查询还需要 function/tool calling。Responses-only 部署不适用。不会发现/启用模型或静默改 deployment。
- 不接 ChatGPT、Copilot 订阅；暂无 Microsoft Entra 登录、多个连接配置、图像输入、联网资料检索或自动修改库存。

## 配置与发送

1. 在「连接设置」填写资源根地址、Deployment 名称、协议类型；传统接口填写该资源支持的 API version。
2. 在应用内输入 API Key。不要将 Key 发到聊天、Git、issue 或测试日志。保存后不回显；留空保留原 Key，更换资源必须填新 Key。
3. 明确确认数据发送说明，再手动测试已保存连接。测试发送固定文本，可能按量计费；通过只证明流式文本可用，不证明工具功能。
4. 新建会话后提问。问题、有限最近历史将发送到配置资源。
5. 「本次允许查询库存基本资料」默认关闭。开启才提供只读 search_medicines / query_expiry，最多每次 10 条；关闭时不读取/发送新药箱数据，但历史已发送的内容不能撤回。

助手不是医疗服务：不诊断、不推荐吃哪种药、不生成个体化剂量；资料缺失为未知，不是没有禁忌。无联网工具，不能把模型记忆称为已检索说明书。AI 仍可能违反提示或产生错误，应核对包装原文并咨询医生/药师。

## 架构与约束

- `ai/AzureModelAdapter.kt`：OpenAI Kotlin 4.1.0 处理 Azure 协议、流式文本和分片工具参数，不依赖药箱/Room。
- `ai/AiContract.kt`：统一消息、工具接口与受控循环。最多 4 个模型请求，末轮不执行工具；检查完整 finish reason、取消、参数/输出大小与 ID。
- `ai/MedicineTools.kt`：只读工具白名单、严格参数；使用现有日期规则。只投影名称、品牌/通用名、规格、位置、有效期、余量/单位，不上传备注、功效、照片、OCR 原文、说明书或整个药箱。
- `ai/AiStorage.kt`：独立 Room `azure-ai.db` 保存完整问答及记录引用，不改 `pharmacy.db` 或药品迁移。会话新建/继续/重命名/确认删除；仅完成回答保存。最近最多 8 回合、24000 字符上下文，完整本地历史仍保留。
- `ai/AiViewModel.kt`：串行用户任务、发送确认、默认关闭库存授权、取消、可控错误。不自动重试或切换计费/模型。
- `ai/AiScreen.kt`：流式回答、工具状态、可点击库存记录；安全窗口防截图。当前用原生文本显示，不引入复杂 Markdown/插件框架。

Key 以 Android Keystore AES-GCM 加密保存在 noBackupFilesDir，并把资源 endpoint 一起加密绑定；取消/失败保存不能把另一个资源与旧凭据配对。Key 不进入 UI 状态、Bundle、Room、系统备份或药箱备份。连接元数据和 AI 会话也排除系统 cloud/device-transfer 备份；药箱原备份行为不变。删除连接清除本地密文，不撤销 Azure 远端 Key；需要轮换应在 Azure 官方控制台处理。

删除对话不能撤销 Azure 已接收的内容。切换连接后不能把旧会话发送到新连接，须新建；API Key 不写入会话配置。会话只保存完成问答，不持久保存工具参数全文；历史库存回答标记为可能过时；模型不保证每轮自动重查。开启查询权限后可明确要求重新查询，库存当前值仍以详情页为准。

## 工具链

OpenAI Kotlin 4.x 需要 Kotlin 2.x。本次升级 Kotlin/Compose compiler plugin 到 2.0.21、KSP 到匹配版本；AGP 8.5.2、Gradle 8.7、JDK 17、Compose UI BOM、Room 2.6.1 与 minSdk 26 保持不变，不增加实验目录或额外工作流。

## 验证边界

- 必要 UT：Azure endpoint/auth/path/version 与真实 SDK 的文本/分片参数解析；工具循环预算、禁用授权与取消；只读投影不泄漏备注/照片/OCR/说明书、完整文本会话编码。
- 原核心 9、OCR 4、更新 3 个 UT 保留；新增 AI 3 个，不新增 UI/仪器框架。
- 本地 assembleDebug、testDebugUnitTest、lintDebug 通过，Lint 仅依赖升级提示。
- 专用 API34 AVD 检查真实 UI：入口、配置保存、Keystore 密文读回、创建会话、进程重启恢复配置与会话；仅使用合成 Key/测试地址，没有发起真实 Azure 请求，也不清除已有设备数据。
- **真实 Azure API Key、部署兼容、工具效果、网络策略以及真实服务调用尚未验证。** 需用户在 App 输入凭据，手动同意发送后验证；不能用 mock 或编译通过替代。

官方参考：https://learn.microsoft.com/en-us/azure/ai-foundry/openai/how-to/switching-endpoints 。上游 SDK：https://github.com/aallam/openai-kotlin （MIT）；Ktor/Kotlin Apache-2.0，不改变本项目非商业许可。
