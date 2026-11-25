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

Menu maintenance refreshes the current account's usable navigation without reloading the app. Clearing a cached iframe menu's external link switches back to its internal page without leaving an empty iframe above it. Tabs whose paths are no longer registered are removed, including fixed tabs; the 404 fallback does not make a stale tab valid. Hidden but usable pages remain valid.

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

### Navigation refresh regression

Check a production build in one browser session, using dedicated menus and retaining the test data:

1. Open a cached iframe menu, return through the app's Menu Management tab, change its link from A to B and reopen it. Verify the iframe loads B.
2. Return through the same tab, clear the link while retaining an internal component, save and reopen the menu. Without a browser reload, verify the internal page starts at the top with no empty iframe; repeat the iframe-to-internal switch.
3. Open another menu, return to management and disable it. Verify both its navigation entry and tab disappear while unrelated usable tabs remain. Repeat with a fixed tab and re-enable the test menus afterwards.
4. Change an opened menu's path without changing its route name. Verify the stale path's tab is removed and the new navigation entry opens correctly. Restore the test path afterwards.
5. Hide an enabled menu without removing its use permission. Its navigation entry disappears, but its existing tab and direct route remain usable.

Use app navigation throughout steps 1–5: a full reload destroys the old cache and can conceal refresh defects. A final reload may separately verify saved settings, not replace these checks.

### Role deletion regression

Use Computer Use in a real browser against an isolated test environment. Open single/batch deletion from the existing role table; verify role names/codes, read-only visible account lists, deduplicated counts, the scope disclaimer and retained-permission explanation. Cancel and verify roles/bindings remain unchanged. Confirm and verify the refreshed list and cleared batch selection. Change a binding or revoke authority between preview and confirmation; verify the refusal message, disabled confirmation and explicit new-preview action, with no false success. Check Chinese/English and a narrow viewport. A preview failure must never fall back to plain deletion confirmation. Actual destructive browser actions require action-time confirmation.

## License

[MIT](./LICENSE). Build output includes the license as `LICENSE.txt`.
