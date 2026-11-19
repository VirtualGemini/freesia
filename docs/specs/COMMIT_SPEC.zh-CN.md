# Freesia Admin 提交规范

[English](./COMMIT_SPEC.md) | 简体中文

本仓库使用 Conventional Commits。

## 1. 提交格式

```text
<type>(<scope>): <subject>
```

`scope` 可选，但推荐填写。

示例：

```text
feat(frontend): add file config status filter
fix(backend): handle expired reset password code
docs(root): add contribution guide
refactor(file): split file config query service
chore(repo): update gitignore rules
```

## 2. 允许的 type

- `feat`：新增功能。
- `fix`：修复缺陷。
- `docs`：文档变更。
- `style`：代码格式调整，不影响功能。
- `refactor`：代码重构，不包含功能新增或缺陷修复。
- `perf`：性能优化。
- `test`：新增或调整测试。
- `build`：构建流程、依赖或打包配置变更。
- `ci`：CI 配置或脚本变更。
- `revert`：回滚提交。
- `chore`：工具、维护或非业务变更。
- `wip`：临时工作提交，合并前应清理或 squash。

## 3. 推荐 scope

- `root`：根目录 README、贡献规范或仓库配置。
- `docs`：文档。
- `frontend`：前端应用。
- `backend`：后端应用。
- `auth`：认证、注册、找回密码。
- `user`：用户和个人资料。
- `role`：角色和权限配置。
- `menu`：菜单和动态路由。
- `file`：文件中心。
- `file-config`：文件配置管理。
- `dependencies`：后端 BOM 与版本管理。
- `common`：响应、分页和错误契约。
- `framework`：后端 starter 能力。
- `iam`：账号、认证、RBAC 与登录策略模块。
- `notification`：通知渠道、邮件和模板模块。
- `audit`：审计记录和查询模块。
- `dashboard`：产品概览模块。
- `extensions`：按需扩展适配器。
- `infra`：根目录 PostgreSQL/Valkey 等基础设施编排。
- `server`：后端启动模块和应用配置。
- `repo`：仓库治理、忽略规则、hook 或提交规则。

如果自定义 scope 能更准确描述变更范围，也可以使用。

## 4. subject 规则

- 保持简短、明确。
- 英文推荐小写动词开头，也可以使用中文。
- 不以句号结尾。
- 描述具体变更，不写 “update code” 这类泛化内容。

推荐：

```text
fix(auth): reject expired reset password code
docs(root): add monorepo development guide
```

不推荐：

```text
fix: bug
docs: update
chore: misc changes
```

## 5. body 和 footer

以下情况建议补充 body：

- 影响多个子包。
- 有重要设计取舍。
- 涉及兼容性、配置、数据库结构或接口契约变化。
- 修复原因无法从 subject 看清。

关联 issue 可放在 footer：

```text
Closes #123
```

破坏性变更必须使用 `BREAKING CHANGE:`，并在 PR 中说明迁移方式。

## 6. 工具

根目录提交规范只描述仓库统一提交信息格式。依赖单个子包工具链的提交工具配置应保留在对应子包内。

仓库协作流程见 [PR_SPEC.zh-CN.md](./PR_SPEC.zh-CN.md)：任务分支提交，PR 交维护者使用 Merge commit、Squash 或 Rebase 合并，不向 main 直接提交。原格式和 type 不变，所有内容提交必须使用真实 footer：

```text
Signed-off-by: Your Name <your-email@example.com>
Docs-Impact: updated
```

使用 `git commit -s` 为本人有权提交的贡献签署 DCO。文档未受影响时使用：

```text
Docs-Impact: none
Docs-Impact-Reason: 具体说明为何不影响文档
Docs-Impact-Approved-By: @reviewer-login
```

脚本检查完整提交范围并从 GitHub review 核验豁免；用户名 footer 本身不代表批准。破坏性提交含 `BREAKING CHANGE:` 与 `Docs-Impact: updated`；实施前在 GitHub issue 或 PR 讨论中获得维护者确认，PR 关联确认记录与迁移/回退方案，详见 PR 规范。`wip` 仅供本地阶段，ready 范围不得含 wip、`fixup!`、`squash!`。

每次发布任务分支或标记 PR ready 前执行 `make check-commits BASE_REF=origin/main HEAD_REF=HEAD`。后续修复或 revert 不能抵销范围内较早提交的格式/metadata 错误。

## 7. PR 前检查

- commit message 符合本规范。
- 每个提交只有一个主要意图。
- 没有混入无关格式化、构建产物或本地配置。
- 前端变更已执行相关前端检查。
- 后端变更已执行相关后端检查。
