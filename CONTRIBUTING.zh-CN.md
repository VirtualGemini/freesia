# Freesia 贡献指南

[English](./CONTRIBUTING.md) | 简体中文

先阅读[提交规范](./specs/COMMIT_SPEC.zh-CN.md)和[PR 与维护者合并规范](./specs/PR_SPEC.zh-CN.md)。前后端包引入时遵循 `specs/` 中适用的开发规范。被忽略的 `docs/` 仅用于本地 skill 工作材料，公开协作说明和 CI 使用已跟踪的规范。

每个任务从最新 main 创建一个分支，命名 `type/kebab-case`，例如 `feat/iam-login`。需要时可带 issue 编号，但不强制关联 issue。在 PR 中说明问题、范围、预期行为和验证结果。实现、提交和修复审核意见在任务分支进行，通过 PR 交给维护者最终合并，维护者可选择 Merge commit、Squash 或 Rebase；不直接更新 main，agent 不合并 PR。

后端执行 `cd freesia-admin && mvn test`，使用临时 PostgreSQL/Valkey 容器验证 reactor；前端执行 `cd freesia-web && pnpm lint && pnpm build`。`make check` 汇总两包与仓库流程检查，发布/标记 ready 前还必须执行 `make check-commits BASE_REF=origin/main HEAD_REF=HEAD`。文档检查链接、状态和中英文一致性，并与方案同步。

提交保留 `<type>(<scope>): <subject>` 和原 type 列表，新增 DCO `Signed-off-by`、`Docs-Impact` footer。安装前端依赖后，Husky 校验任务分支、提交 metadata 和暂存文件，pre-push 校验完整提交范围；CI 重复检查。用户要求“不提交”或“不 push”时，完成相应准备与验证后停止。

PR 使用仓库模板，记录行为、执行过的检查、文档与配置/API/schema 影响。破坏性变更实施前在 GitHub issue 或 PR 讨论中获得维护者确认，并说明迁移和回退方式。维护者审核改动，在全部 required checks 通过后手动合并；GitHub 首次设置和单维护者例外见 PR 规范。提供必要的第三方 LICENSE/版权；构建产物、个人配置、运行数据及 `.gitignore` 列出的本地目录不提交。
