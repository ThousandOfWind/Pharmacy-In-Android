# Pharmacy-In-Android

**仅限非商业使用，禁止商用。** 本项目采用自定义的 [Pharmacy-In-Android 非商业使用许可证 1.0](LICENSE)，不提供商业使用例外或双重许可方案；不是 MIT／Apache 或 OSI 批准的开源许可。

一个药箱数据保存在本地、日常功能可离线使用的安卓小程序；仅手动检查和下载 GitHub 更新时联网。交互围绕「看药箱 → 拍照核对 → 入库 → 更新余量」组织：

- **手绘药箱首页**：手绘字标、药品清单、搜索和必要的过期/临期提醒；有效期与标签筛选按需展开（可叠加），不堆积在首屏。
- **多标签整理**：常用标签快捷多选、自定义标签和已有标签复用；详情展示全部标签，首页按需筛选，搜索包含标签。
- **独立添加页**：拍照、相册或手动录入；保存按钮固定在底部，未保存返回时确认放弃。
- **本地中文 OCR 与归类**：照片中的药名、资料和明确说明书章节生成候选；核对后采用空白资料，安全条目逐项采用，不覆盖手工填写。
- **框选重识别**：识别不理想时可在已拍照片上框住文字并指定字段；保留原图，结果仍需核对。
- **药品详情**：先看有效期、余量与位置；包装资料、照片和说明书按组展开，支持放大照片、底部面板更新余量、编辑资料、移除与撤销。
- **药品资料与说明书**：通用名、品牌、厂家、批号及位置等均可选；说明书可由 OCR 标题归类后逐项核对，或手工记录，不自动生成剂量或用药建议。
- **余量＋单位**：区分未知与 0，不跨单位相加，不使用统一低余量阈值。
- **本地持久保存**：Room 数据库、应用私有目录照片、屏幕旋转和系统重建时恢复填写草稿。
- **完整备份与合并恢复**：更多菜单导出已保存药品及照片；恢复前校验与预览，只新增缺少的记录，不覆盖现有药箱。备份未加密，请妥善保管。
- **GitHub 更新**：首页“更多 → 检查更新”检查公开 Release，展示版本/说明，手动下载并校验后交给 Android 确认安装；不上传药箱数据或保存 GitHub 凭据。

备份格式、安全边界与使用步骤见 [docs/backup-restore.md](docs/backup-restore.md)。交互设计和数据规则见 [docs/interaction-flow.md](docs/interaction-flow.md)；OCR 样本结果、框选流程与未验证项见 [docs/photo-ocr.md](docs/photo-ocr.md)；手绘样式、当前信息层级、字体许可和验证边界见 [docs/paper-design.md](docs/paper-design.md)。

## 数据字段

- 药品名称（必填）
- 有效期至（可选，`YYYY-MM` 或 `YYYY-MM-DD`；月份精度按月末计算）
- 适应症 / 功能主治（可选，沿用原主要功效字段）
- 药品资料（可选）：通用名、品牌 / 商品名、规格 / 包装、生产厂家、批准文号、生产批号、存放位置
- 说明书（可选）：成分、用法用量、禁忌、不良反应 / 副作用、注意事项、药物相互作用、贮藏条件、说明书来源 / 修订日期
- 余量及单位（可选，非负整数；有数量时必须选择单位）
- 标签（可选，每条最多 8 个，每个最多 12 个字符，去除首尾空白并忽略大小写去重）
- 备注（可选）
- 药品照片（可选，支持正面、有效期、说明书等多张照片，每条最多 12 张）

药品资料、说明书和备注按组展开，不要求一次填完整。原药品名称保留为库存列表标题，通用名与品牌可单独记录；不同厂家、规格、批号或有效期的库存建议分开添加。基本资料每项最多 200 个字符；说明书各项最多 5000 个字符，来源 / 修订日期最多 500 个字符。保存仅去首尾空白，保留说明书内部换行；超长提示而不静默截断，可留存完整说明书照片。

**说明书是包装原文记录，不是在线查询或用药建议。OCR 可按明确标题归类候选，但错字、漏字不能保证自动修正，必须核对当前同厂家同规格的原图。** 内容应来自当前药品同厂家、同规格的包装或说明书；不会根据药名、品牌、标签生成剂量，也不将留空解释为“无禁忌 / 无副作用”。有疑问应咨询医生或药师。旧备注与功效不自动拆分，原文保留；新资料全部默认为空。

搜索匹配库存名称、标签、功效、备注及全部基本资料（含品牌、通用名、规格、厂家、批准文号、批号和存放位置）。说明书安全条目不参与首页搜索，避免把禁忌中提到的疾病误认为适应症。

## 常用标签

常用标签只是可选的整理词，不会自动分配，也不代表适应症或用药建议：

| 分组 | 标签 |
| --- | --- |
| 用途整理 | 感冒相关、退热、止痛、过敏相关、肠胃相关、咽喉相关、眼部、皮肤相关 |
| 使用方式 | 口服、外用 |
| 备用场景 | 家庭常备、出行备用 |
| 保存提示 | 需冷藏、避光保存 |

用途、使用方式和保存条件应自行核对包装。可以新增如“床头抽屉”“急救包”的自定义标签，后续记录可复用。点击已选标签可移除；直接保存也会加入自定义输入框里尚未点击添加的标签。标签筛选使用精确匹配，搜索使用文字包含匹配；无匹配时“查看全部药品”会同时清除搜索、有效期和标签筛选。

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
保留 9 个药箱核心规则 UT（日期、余量、OCR、标签规则，以及资料字段独立性/原文保留、资料校验/搜索范围），另有 4 个 OCR 归类/框选规则 UT、4 个备份规则 UT 及 3 个必要更新规则 UT（版本编码、元信息与大小边界、下载地址约束）。不新增 UI／仪器测试套件。

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
3. 后续在药箱首页点击 **更多 → 检查更新** → **下载更新** → **安装更新**。首次可能需在系统设置中允许本应用安装未知应用，返回后仍由系统确认安装。

下载只允许本仓库 Release 和受信任的 GitHub HTTPS 下载跳转；校验 SHA-256、大小（最大 150 MiB）、包名、版本及与已安装应用一致的签名。不支持静默安装或自动卸载。网络失败、缺少发布或拒绝授权不会影响本地药箱。

**旧 APK 迁移注意：** 历史 Actions/本地 APK 的签名可能不同；新固定签名不保证能覆盖旧安装。签名不一致时应用内更新会阻止安装。请保留旧应用及药箱数据，不要直接卸载或清除数据；请先在“更多 → 备份与恢复”导出完整备份并确认可恢复，再规划迁移；旧版本不一定具备该入口，不能因此直接卸载。首次使用固定签名 APK 后，后续同签名新版才能正常覆盖并保留数据。

Actions 的 `pharmacy-debug-apk` artifact 仍保留，内含 APK 与 `update.json`；下载 artifact 通常需要 GitHub 登录。应用更新使用公开 Release，不使用该下载入口。

如需命令行安装（已连接 adb）：

```bash
adb install -r app-debug.apk
```

## 许可证与使用边界

本项目原创内容允许非商业使用、研究、修改及免费分发，须保留许可证和版权通知。禁止收费分发、广告或订阅变现、商业产品／服务集成、商业经营中的内部使用，以及面向预期商业应用的开发和测试。组织身份（包括公益或教育机构）不自动豁免商业用途；具体权限以 [LICENSE](LICENSE) 全文为准。

这是**源码可见、非商业授权**，不应对外宣称为 MIT／Apache 或标准开源。第三方代码和依赖仍遵守各自许可证，例如 Gradle Wrapper 的 Apache-2.0，以及 [字体通知](app/src/main/assets/font-licenses/) 中的 SIL Open Font License 1.1；项目不能用自己的非商业条款重新限制第三方独立授予的权利。许可证不授予用户药箱数据、照片、账号凭据或第三方内容的权利。

提交贡献须确保有权按项目许可证提供原创改动，保留所有第三方许可。许可证边界不等于技术防复制措施或法律效果保证，正式分发仍建议由法律专业人士审阅。

未来 AI 服务接入需分别核对服务商的许可、账号授权和资格条件；本许可证**不自动满足**任何服务商的“开源客户端”订阅接入要求，不能为了接入而擅自改成允许商用的许可证。

## 说明

当前为本地药箱管理第一版，不提供用药建议或自动服药计划。OCR 只提取明确标注的候选字段；生产日期和有效期时长不会被当作到期日期。保存前应以实际包装为准。

数据库版本 5 使用显式的版本 2 → 3 → 4 → 5 迁移，不再静默清空旧记录。版本 3 → 4 只新增默认为空数组的标签字段；4 → 5 只新增默认为空文本的 15 个药品资料/说明书字段，旧药品、照片、标签、功效、备注、余量和时间信息保留，不自动推断或拆分内容。旧记录缺失单位或有效期格式不明确时，显示待补充/待核对，不自动推断。旧缓存照片若仍可读取，会转存到应用私有目录；已丢失的缓存照片无法恢复。

照片保存在应用私有目录，清缓存不会删除新照片；卸载应用或清除应用数据仍会删除药箱。可以导出完整备份及合并恢复；暂不提供覆盖式回滚、照片单独删除或长期撤销历史。
