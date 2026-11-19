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

Open http://localhost:3000. Development requests to `/api` are forwarded to `http://localhost:8080`; set `VITE_API_PROXY_URL` to use a different backend. Authentication and management APIs are under development.

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
