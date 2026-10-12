# 第三方组件与通知

项目的自定义非商业许可只覆盖有权许可的原创内容，不限制第三方独立授予的权利，也不授予第三方商标、服务账号或凭据权限。以下是维护概览，不代替 APK 内按实际变体生成的完整组件清单、许可及供应商版权通知，不是法律兼容性认证。

| 组件/资源 | 许可或条款 | 来源 |
| --- | --- | --- |
| AndroidX、Compose、Room | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin、协程、序列化、Ktor | Apache-2.0 | https://github.com/JetBrains/kotlin 、https://github.com/Kotlin/kotlinx.coroutines 、https://github.com/Kotlin/kotlinx.serialization 、https://github.com/ktorio/ktor |
| Coil 2.7.0 | Apache-2.0 | https://github.com/coil-kt/coil/tree/2.7.0 |
| OpenAI Kotlin 4.1.0 | MIT（Copyright 2021 Mouaad Aallam） | https://github.com/aallam/openai-kotlin/tree/4.1.0 |
| OkHttp 4.12.0、Okio 3.9.1 | Apache-2.0；Public Suffix 数据另适用 MPL-2.0 | https://github.com/square/okhttp/tree/parent-4.12.0 、https://github.com/square/okio/tree/3.9.1 |
| SLF4J 2.0.16 | MIT（QOS.ch） | https://github.com/qos-ch/slf4j/tree/v_2.0.16 |
| Guava listenablefuture、javax.inject | Apache-2.0 | https://github.com/google/guava 、https://github.com/javax-inject/javax-inject |
| ML Kit 中文识别及相关模块 | ML Kit Terms of Service，包含 Google APIs Terms | https://developers.google.com/ml-kit/terms 、https://developers.google.com/terms |
| 部分 Google Play services、ODML 模块 | Android SDK License；供应商内嵌组件另有通知 | https://developer.android.com/studio/terms |
| Google Data Transport、Firebase components/encoders、Accompanist | POM 声明 Apache-2.0，保留各原包通知 | https://github.com/google ，实际坐标/版本见随包清单 |
| LXGW WenKai Lite 字体 | SIL OFL 1.1，含 Reserved Font Names 条件 | https://github.com/lxgw/LxgwWenKai-Lite ，原文 [WenKai-OFL.txt](app/src/main/assets/font-licenses/WenKai-OFL.txt) |
| Long Cang 字体（静态字子集） | SIL OFL 1.1 | https://github.com/google/fonts/tree/main/ofl/longcang ，原文 [LongCang-OFL.txt](app/src/main/assets/font-licenses/LongCang-OFL.txt) |
| Gradle Wrapper（源码构建工具，不作为 App 功能） | Apache-2.0；脚本保留上游头部 | https://github.com/gradle/gradle |

## 随 APK 分发

“更多 → 关于 → 第三方通知”可离线查看实际 debug/release 运行时组件、POM 署名、供应商原文。Apache/MIT/MPL 全文及字体 OFL 均随包保留；Google AAR 的 `third_party_licenses.json` 偏移索引逐份解析对应原文，按内容去重而非丢弃。供应商通知包含其更广泛的组件清单，不宣称其中全部都是本应用直接依赖。

ML Kit 的二进制/模型不是因文档示例采用 Apache-2.0 就整体开源；图片/结果本地处理也不意味着 SDK 没有指标收集或网络请求，见[隐私说明](docs/privacy.md)。SDK 的代码许可与 Azure、GitHub 等在线服务的条款分别核对，不能据 SDK MIT/Apache 标签推定服务授权。

本项目未修改 OkHttp 内 Public Suffix 规则。原数据随 OkHttp 原包分发，构建时另解码同版本正常/例外规则为可读 `public-suffix-source.txt`，随包提供并收录于离线通知，避免只指向不断更新的上游版本。数据来源为 https://publicsuffix.org/list/public_suffix_list.dat ；对应版本源码入口为 https://github.com/square/okhttp/tree/parent-4.12.0/okhttp/src/main/resources/okhttp3/internal/publicsuffix ，MPL 原文见 [MPL-2.0.txt](app/src/main/legal/MPL-2.0.txt)。该局部许可不把整款应用改成 MPL，但其具体义务仍须保留。

维护步骤和未知许可阻塞规则见 [docs/licensing.md](docs/licensing.md)。新增/升级、复制、修改及发布前都应检查最终产物；本概览不保证以后新增组件自动兼容，不把版权/许可检查当作正式商用或上架批准。
