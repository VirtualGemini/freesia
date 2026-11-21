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

登录滑块只保留本地交互，不是服务端防刷；不持久化密码或请求后端验证码票据。网络／503 故障保留页面与会话，只有明确会话失效才清理登录。初始 Refresh 签发不代表浏览器刷新轮换已完成，既有工作台内容仍是演示。

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
