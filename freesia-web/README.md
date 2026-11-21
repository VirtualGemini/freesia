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

The login slider is local interaction, not server-side protection; no captcha ticket or password is persisted. Network/503 failures retain the current page and session, while confirmed session invalidation clears login. Initial Refresh issuance does not yet provide browser refresh rotation. Existing dashboard content remains a demo.

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
