# 项目 Agent 开发流程

后续功能开发和修复必须按以下顺序执行：

> latest main → 新 Git worktree → 设计、实现、验证 → PR → review → 无问题后合并 → 触发并确认新版本构建

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
- 按风险做必要验证：构建、Lint、核心规则 UT，以及需要时的设备/模拟器检查。
- **只添加必要 UT，不做过度测试。** 同时审查已有测试，移除重复、无价值或维护成本明显高于收益的测试及未使用依赖。
- 默认不新增 UI／仪器测试套件或额外测试框架。不能为减少测试而删除仍有价值的核心规则覆盖。
- 区分“已实现”“已验证”“尚未验证”，不将 UT 通过等同于实际拍照、权限或真实药盒 OCR 效果已验证。
- 提交前检查 diff、意外文件和敏感信息，确保提交只包含本任务所需内容。

## 3. 创建 PR，完成 review 后再合并

- 提交本次改动，推送任务分支，通过 PR 合并到 `main`；不直接推送 main。
- PR 描述包含设计/改动摘要、验证结果、风险和未验证项。
- **必须完成 PR code review。自检或 CI 通过不能代替 PR review。** 可由人工或明确授权的 review agent 审查；安装了工具不等于授权委派。
- 对 review 问题逐项处理，必要时补充验证并重新 review。
- 只有在相关检查通过、审查无未解决的阻塞问题、且满足仓库保护规则后，才能合并。
- 不使用管理员绕过、不强推、不跳过必需的审查或检查。遇到认证、权限或分支保护阻塞时明确报告并停止。

## 4. 合并后触发并确认新版本生成

- 记录合并提交，确认构建对应合并后的 main，而不是旧的开发分支。
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
