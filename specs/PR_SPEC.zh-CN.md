# Freesia 分支、PR 与维护者合并规范

[English](./PR_SPEC.md) | 简体中文

Freesia 的 Java/Vue 单仓库统一通过 **任务分支 → PR → 人类审核 → 维护者合并** 更新代码、文档、依赖与配置，维护者可选择 Merge commit、Squash 或 Rebase。提交格式继续遵循 [COMMIT_SPEC.zh-CN.md](./COMMIT_SPEC.zh-CN.md)。公开协作与开发规范位于 `specs/`，前后端实现规则随各业务底座引入。

## 1. 分支与执行范围

- `main` 是唯一长期分支，由维护者通过 PR 合并更新，保持可构建状态。
- 每个任务使用一个分支，在 PR 中说明任务及验证结果；需要独立讨论或需求记录时关联 GitHub issue，不强制创建 issue。
- 分支从最新 main 创建，命名 `type/kebab-case`，例如 `feat/iam-login`、`docs/pr-workflow`；需要时可带 issue 编号，如 `feat/123-iam-login`，合并后删除。
- 开发者和 agent 在任务分支实现、验证、修复审核意见。**agent 不合并 PR、不向 main 直接推送、不启用自动合并，也不绕过仓库规则。**最终合并由 `.github/maintainers.txt` 中的维护者执行。
- 用户的执行范围优先：要求“不提交”“不 push”或“先准备”时，完成可审阅文件与验证后停止，不创建提交、发布分支或 PR。
- 开始新任务前检查工作区；已有改动保留或放入合适的任务分支，不能通过 reset/clean 覆盖他人工作。

常规起点（工作区允许切换分支后）：

```sh
git fetch origin
git switch main
git pull --ff-only origin main
git switch -c feat/iam-login
```

## 2. 实现、提交与本地检查

1. 阅读任务需求及已纳入版本管理的开发、架构规范，在 PR 中说明预期行为与文档影响。新运行依赖先在 issue 或 PR 讨论中说明用途、许可证、替代方案和移除成本，由维护者确认；依赖升级使用独立 PR。
2. 实现一个完整任务切片，运行对应包检查。后端 `mvn test` 使用临时 PostgreSQL/Valkey；前端 `pnpm lint` / `pnpm build` 包含类型检查。文档/流程修改运行 `make check-policy` 并检查链接。
3. 只暂存本任务文件，检查 `git diff --cached --check` 和暂存 diff。使用 `git commit -s` 签署本人有权提交的贡献；不代写他人的签署。

公开 README 说明 Freesia 的当前功能、运行方式和验证命令，实现过程与内部计划记录在本地工作材料中。

4. 提交后检查新增提交；发布分支、创建 PR 或标记 ready 前检查**完整可达提交范围**：

```sh
make check
make check-commits BASE_REF=origin/main HEAD_REF=HEAD
```

应用改动还须对实际 test Compose 环境执行 `make check-test-docker` 并记录结果。要求 PostgreSQL、Valkey、后端、前端全部健康且同源 HTTP 检查通过；临时测试容器或独立进程不能作为部署证据。V1 checksum 不一致时保留现有数据并明确解决差异后，才可声称 test 部署通过。

`make check` 检查源码；`make check-commits` 检查每个新增提交的格式、DCO、文档影响和破坏性变更，以及整个 PR 的文档同步。后来补一个有效提交或 revert 不会消除历史中的无效提交。未发布历史可修正；已发布历史的改写先协调，不能覆盖未知远端工作。

Husky 保留在 `freesia-web`：pre-commit 拒绝 main 提交并运行现有 lint-staged，commit-msg 校验提交与暂存文档影响，pre-push 拒绝更新 main 并检查完整提交范围。CI 重复检查，钩子不能作为唯一门槛。`wip` 可作临时本地提交，ready 范围不能包含 wip、`fixup!` 或 `squash!`。

仓库忽略 `docs/`、`.scratch/` 和 `.agents/`，这些目录只留在本地。`docs/` 仅存放可选的 skill 工作材料，`specs/` 保留版本管理。公开协作说明、文档检查和 CI 使用已跟踪文件。必要的许可证与版权声明随分发文件保留，不依赖被忽略的目录。

## 3. PR 内容与审核

获准发布后只推送任务分支，PR 目标为 main；尚未完成时使用 Draft。PR 标题使用 Conventional Commits，正文遵循 [.github/pull_request_template.md](../.github/pull_request_template.md)，包含：

- 具体问题、最终行为和改动范围，存在独立讨论或需求记录时关联 GitHub issue；
- 预期行为的验证证据及相关开发规范、模块边界、事务/错误处理说明；
- 执行过的命令/结果，以及 API、schema、配置、许可证和外部邮件/存储调用影响；
- 文档同步、破坏性变更批准及回退/迁移方式。

人类审核检查预期行为、开发规范、模块边界、事务/错误处理、测试、依赖、提交和文档；PR 除构建结果外，还应说明行为验证证据。作者和 agent 不能批准自己的改动；agent 的分析供人类审核参考。严重或高风险问题阻止合并，中风险问题修复或由维护者写明接受理由。

## 4. 文档影响与破坏性变更

每个内容提交包含 `Signed-off-by` 和 `Docs-Impact` footer，具体格式见提交规范。`.docs-impact.yml` 规定改动路径需要同步的文档：暂存检查读取已提交 HEAD 的规则，单提交读取父提交规则，PR 读取可信目标分支规则；改动不能通过修改自己的规则获得豁免。重命名按旧/新路径计算，删除文档不算更新证据。

`Docs-Impact: none` 必须说明原因并指定批准者；可信策略检查实际 GitHub review，而不是把 footer 中的用户名当作批准。普通豁免要求非 PR/提交作者的当前 head 人类批准。若可信目标分支只列一名维护者且该维护者本人是 PR 作者，可确认自己的文档豁免或策略变更；这个狭窄例外**不授权 agent 合并，也不替代 GitHub 的合并要求**。

已发布 API、schema、配置或前端行为的破坏性变更实施前，在 GitHub issue 或 PR 讨论中获得维护者确认。PR 关联确认记录，说明迁移和回退方式。维护者在审核时核对讨论记录；本地检查验证提交 metadata 和文档，可信策略验证当前 head 的审核记录。

提交包含 `BREAKING CHANGE:` 和 `Docs-Impact: updated`。未发布的内部接口调整由维护者在讨论中明确范围，不把所有重构自动视为发布接口破坏。

## 5. CI 与可信策略

`.github/workflows/ci.yml` 在 PR 和 main 更新运行，分别验证后端、前端和仓库流程。CI 不发布产品，不接收邮件、对象存储或发布凭据；测试使用临时环境。

main 更新检查全部新增内容提交，支持 Merge commit、Squash 和 Rebase。与 Git 自动合并结果完全一致的干净合并提交不单独要求消息 metadata，其来源提交仍逐个检查；冲突解决或额外编辑属于内容变更，遵循提交规范。

流程初始化可先独立提交，此时前后端 job 明确报告包尚未进入 Git 历史；包一旦已在目标分支，PR 删除其入口以跳过 CI 会失败。业务包随后通过各自 PR 引入，不由流程提交一并带入。

`.github/workflows/policy.yml` 的 `Trusted Policy / policy` 从 PR 的可信 base 执行仓库策略，只把 PR head 当 Git 数据读取，校验提交、文档、批准和受保护策略文件；不执行 PR 代码或安装 PR 依赖。`pull_request_target` 不应运行不可信 head 代码，依据 [GitHub 事件说明](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request_target)。

`.policy-files` 列出策略执行文件；`.github/CODEOWNERS` 保护 IAM、安全装配、事务/schema、构建、工作流、钩子和协作规范。这些变更需要维护者审阅，当前 head 变更后的旧批准不能复用。

审核批准后，维护者在 Actions 重跑当前 PR 的 Trusted Policy，脚本会重新读取当前 head 和实际 review。此可信工作流不监听 `pull_request_review` 执行 PR 版本的 workflow；PR 新提交或正文修改由 `pull_request_target` 自动重查。

## 6. 维护者合并与完成条件

维护者核对预期行为、验证证据、审核意见、文档和全部检查，选择 Merge commit、Squash 或 Rebase 执行最终合并。内容提交标题遵循提交规范，保留 DCO、`Docs-Impact` 及适用的破坏性变更 footer。合并后 main CI 保持绿色；删除任务分支并关闭相关 issue。

“已实现”“已提交 PR”“已审核”“已合并”分别报告。开发者完成到待审核 PR 时不声称任务已合并；agent 的工作在把可审阅 PR 交给维护者后停止，维护者合并后才满足集成完成条件。

## 7. 首次 GitHub 配置（尚未执行）

本次只准备仓库文件，**不会**自动修改 GitHub 设置。维护者首次将流程通过 PR 合入后，在仓库 Rulesets/General 配置：

- main 必须通过 PR 更新，阻止删除和 force push；允许 Merge commit、Squash 和 Rebase，关闭自动合并。
- 普通 PR 至少一次人类批准、要求 CODEOWNER review、清除过期批准并解决审核讨论。
- 必过检查：`CI / Backend`、`CI / Frontend`、`CI / Repository`、`Trusted Policy / policy`；按首次实际运行展示的名称选取，启用合并前最新 base 检查。
- 合并权限仅给维护者；其他贡献者通过任务分支或 fork 提 PR。agent 账号、自动依赖工具不配置合并或 ruleset bypass 权限。
- GitHub 不允许作者批准自己的 PR，见[官方审核说明](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/reviewing-changes-in-pull-requests/approving-a-pull-request-with-required-reviews)。单维护者本人发起的 PR 由其他合格人类审核；如仓库管理员人工使用例外，记录具体理由和执行人，agent 不代为操作。

首次目标分支尚无可信策略文件时，策略标记为初始化，不退回执行 PR 自带脚本。维护者人工审阅这一次初始化 PR 和本地检查；规则与脚本进入 main 后，所有后续 PR 使用可信 base 执行。设置文件和 workflow 本身不能启用远端 ruleset，依据 [GitHub Rulesets 文档](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)。
