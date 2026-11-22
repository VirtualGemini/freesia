# Freesia Web

<img src="./public/brand/freesia-mark.svg" width="64" height="64" alt="Freesia" />

[English](./README.md) | 简体中文

Freesia Admin 的 Web 界面，使用 Vue 3、TypeScript、Vite、Element Plus 和 Pinia。

## 本地开发

需要 Node 20.19+ 和 pnpm 11。

```sh
pnpm install --frozen-lockfile
pnpm dev
```

访问 `http://localhost:3000`。开发环境将 `/api` 转发到 `http://localhost:8080`，可通过 `VITE_API_PROXY_URL` 指定其他后端。密码登录和当前端退出已接真实后端，首位管理员初始化见根 README；其余管理能力逐步接通。

登录滑块只保留本地交互，不是服务端防刷；不持久化密码或请求后端验证码票据。Access 只在内存，不进入浏览器存储；Refresh 使用持久化 HttpOnly Cookie，不出现在响应 JSON 或 JS 可读凭据中。刷新／重开浏览器只恢复经服务器验证的会话，并重新查询账号、菜单和权限。

只有明确 Access 过期才刷新并续接尚未执行的原请求一次，同页并发共享刷新。网络／503 故障和刷新响应不明保留页面与会话，不自动重放业务。退出同步到同浏览器其他标签；同账号重登重查权限后恢复工作标签，换账号清空。既有工作台内容仍是演示。

登录、刷新和退出通过同源 `/api` 代理发送 `X-Freesia-CSRF: 1`。生产须使用 HTTPS 与 Secure Cookie，不要通过开放跨来源携带凭据访问绕过同源边界。

浏览器须支持安全上下文中的 Web Locks（HTTPS，本地开发可用回环 HTTP）；只有认证变更共享跨标签锁，Access 更新通过临时 BroadcastChannel 消息传递，不通过存储事件传递凭据。

## 配置

- `VITE_BASE_URL`：部署路径，默认为 `/`。
- `VITE_API_URL`：API 来源地址，生产环境留空时使用同源请求。
- `src/config/product.ts`：产品名称、描述、标志与链接。

## 验证与构建

```sh
pnpm lint
pnpm typecheck
pnpm build
```

构建产物输出到 `dist/`，使用 `pnpm serve` 在本地预览。

## 许可证

[MIT](./LICENSE)。构建产物中包含许可证 `LICENSE.txt`。
