# Freesia Web

<img src="./public/brand/freesia-mark.svg" width="64" height="64" alt="Freesia" />

English | [简体中文](./README.zh-CN.md)

The web interface for Freesia Admin, built with Vue 3, TypeScript, Vite, Element Plus and Pinia.

## Development

Requires Node 20.19+ and pnpm 11.

```sh
pnpm install --frozen-lockfile
pnpm dev
```

Open http://localhost:3000. Development requests to `/api` are forwarded to `http://localhost:8080`; set `VITE_API_PROXY_URL` to use a different backend. Password login and current-session logout use the real backend; initialize the first administrator as described in the root README. Management capabilities are being connected incrementally.

The login slider is local interaction, not server-side protection; no captcha ticket or password is persisted. Access stays in memory, never browser storage. Refresh is a persistent HttpOnly cookie, not response JSON or a JavaScript-readable credential. A reload/reopen restores only a server-validated session and reloads account/menu permissions.

Only explicit Access expiry permits one refresh and one continuation of the unexecuted request. Concurrent requests share refresh work; network/503 failures and uncertain refresh responses retain the page/session without automatically replaying business operations. Logout synchronizes across tabs. Same-account login can restore work tabs after permission checks; account changes clear them. Existing dashboard content remains a demo.

Login, refresh and logout send `X-Freesia-CSRF: 1` through the same-origin `/api` proxy. Production requires HTTPS and Secure cookies; do not enable cross-origin credential access as a workaround.

Use a browser supporting Web Locks in a secure context (HTTPS, or loopback HTTP for development). Only authentication mutations share this cross-tab lock; Access updates use ephemeral BroadcastChannel messages, never credential-bearing storage events.

## Configuration

- `VITE_BASE_URL`: deployment path, default `/`.
- `VITE_API_URL`: API origin, empty for same-origin production requests.
- `src/config/product.ts`: product name, description, logo and links.

## Checks and build

```sh
pnpm lint
pnpm typecheck
pnpm build
```

Build output is written to `dist/`. Use `pnpm serve` to preview it locally.

## License

[MIT](./LICENSE). Build output includes the license as `LICENSE.txt`.
