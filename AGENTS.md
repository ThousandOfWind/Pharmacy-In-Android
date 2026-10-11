# 项目 Agent 开发流程

后续仓库变更（包括功能、修复、文档和指令）默认执行完整交付流程：

> latest main → 新 Git worktree → 设计、实现、必要验证 → PR → 独立 review → 无问题后合并 → 需要时生成新 APK

- 用户请求开发或“提交”时，默认继续完成上述流程，不因本地 commit 完成而停止，也不逐步等待用户催促。
- 用户明确要求仅本地、暂不推送、仅 review 或暂不合并时，以该次限制为准；遇到授权、权限或审查阻塞时停止并报告，不推定更广泛的业务权限。
- 用户已授权本仓库流程中的独立 review。默认使用 GitHub 原生 Copilot Code Review，无需本机 Pi host；后续直接请求审查，不反复询问同一授权。若采用本地 reviewer，仅允许只读审查，不包括让子 agent 实现、提交、推送或合并。

## 1. 从最新 main 创建新 worktree

- 开始前检查工作区、分支和现有 worktree，不覆盖或丢弃用户未提交的修改。
- 获取远端最新状态，以最新 `origin/main` 为基线创建本次任务专用的分支和新 worktree。
- 例如：
  ```bash
  git fetch origin
  git worktree add ../Pharmacy-In-Android-<task> -b feature/<task> origin/main
  ```
- 不复用基于旧 main 的开发 worktree，不直接在 main 上开发。
- 如果需要更新本地 main，仅允许安全的 fast-forward；遇到分叉或未提交修改先处理，不使用强制重置。

## 2. 完成功能设计、实现和验证

- 先明确用户目标、交互/信息流、范围和验收条件，再实现；不擅自扩大范围。
- 代码和文档改动均在本次 worktree 中进行。数据结构变更必须考虑迁移和旧数据保留。
- 按风险做必要验证：应用改动按需构建、Lint、核心规则 UT 和设备/模拟器检查；纯文档／指令变更只需检查内容、diff 和独立 review，不为此运行应用测试或生成 APK。
- **只添加必要 UT，不做过度测试。** 同时审查已有测试，移除重复、无价值或维护成本明显高于收益的测试及未使用依赖。
- 默认不新增 UI／仪器测试套件或额外测试框架。不能为减少测试而删除仍有价值的核心规则覆盖。
- 区分“已实现”“已验证”“尚未验证”，不将 UT 通过等同于实际拍照、权限或真实药盒 OCR 效果已验证。
- 提交前检查 diff、意外文件和敏感信息，确保提交只包含本任务所需内容。

## 3. 创建 PR，完成 review 后再合并

- 提交本次改动，推送任务分支，通过 PR 合并到 `main`；不直接推送 main。
- PR 描述包含设计/改动摘要、验证结果、风险和未验证项。
- **必须完成独立 PR code review。自检或 CI 通过不能代替 PR review。** 默认使用 GitHub 原生 Copilot Code Review：
  ```bash
  gh pr edit <PR编号> --add-reviewer "@copilot"
  ```
- 若创建 PR 时已自动请求 Copilot，避免重复请求；检查现有审查对应的提交。修复或更新 PR 后，若最新提交尚未被审查，重新请求 Copilot。
- review 必须对应最终 PR head commit；通过 PR review 的 `commit.oid` 与 `headRefOid` 核对，不把旧提交的审查当作当前批准。记录审查来源、被审查提交、结论和问题处理结果，并将审查摘要留在 PR 中。
- Copilot 的 `COMMENTED` 审查和“推荐批准”不是正式 `APPROVED`；仓库要求的人工审批仍须满足。Copilot 不可用或权限不足时报告阻塞，不假装已审查，也不绕过保护规则。
- 对 review 问题逐项处理，必要时补充验证并重新 review。
- 只有在相关检查通过、审查无未解决的阻塞问题、且满足仓库保护规则后，才能合并。
- 不使用管理员绕过、不强推、不跳过必需的审查或检查。遇到认证、权限或分支保护阻塞时明确报告并停止。

## 4. 合并后按变更类型决定是否生成 APK

- 记录合并提交。**纯文档／指令变更不需要生成或发布 APK**，不主动 dispatch、不等待 APK 产物，不创建版本号、tag 或 Release；若现有 CI 自动运行，不能将它当作本次交付要求。
- 仅影响应用代码、资源、依赖或构建配置，或用户明确要求新 APK 时，执行下面的构建确认步骤；对应合并后的 main，而不是旧的开发分支。
- 当前构建工作流：`.github/workflows/build-apk.yml`（`Build APK`）。推送 main 会自动触发；**先确认自动触发，不重复派发同一版本的构建**。
- 如果没有自动触发，通过该工作流的 `workflow_dispatch` 在 main 上触发：
  ```bash
  gh workflow run build-apk.yml --ref main
  ```
- 跟踪构建状态。成功后确认 `pharmacy-debug-apk` artifact 已生成，向用户提供 PR、合并提交、构建运行链接及产物入口。
- 构建仍在运行时如实报告；失败时报告失败，不宣称新版本已生成。
- 当前产物是 debug APK，不是签名发布包。版本号变更、Git tag、GitHub Release 或正式发布按用户明确要求执行，不额外部署应用。
- 安全同步本地 main。清理开发分支、worktree 或备份前确认没有未保存内容，不擅自删除用户工作。

## GitHub 与提交身份

- GitHub 账号必须是 `ThousandOfWind`，使用 HTTPS；操作前可仅查询可见登录名确认账号，不读取或打印凭据。
- Git 提交身份保持 `Julie Zhu (M365) (from Dev Box)` / `zhiruizhu@microsoft.com`，与 GitHub 登录身份独立。
- 不读取、复制或输出 token、OAuth 凭据、Cookie、SSH 私钥或凭据存储；不更改 SSH、签名或访问控制来绕过失败。
