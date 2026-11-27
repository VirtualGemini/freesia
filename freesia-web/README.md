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

Use Computer Use in a real browser against an isolated test environment. Open single/batch deletion from the existing role table; verify role names/codes, read-only visible accounts with nicknames, the requested single paragraph and only the role-name/code/assigned-account columns. Cancel and verify roles/bindings remain unchanged. Confirm and verify the refreshed list and cleared batch selection. Change a binding or revoke authority between preview and confirmation; verify the refusal message, disabled confirmation and explicit new-preview action, with no false success. Check Chinese/English and a narrow viewport. A preview failure must never fall back to plain deletion confirmation. Actual destructive browser actions require action-time confirmation.

### Account lifecycle regression

Use the actual test Compose environment and native-browser Computer Use with dedicated test accounts. Change single-account status through **Edit account → Account settings → Account Status → Save**, saving the chosen status directly without a status-confirmation dialog; there is no separate row enable/disable icon. Enabled/abnormal use enable permission and disabled uses disable permission; editing, display and search offer these three states without cancellation, and the initial owner's status is read-only. Profile, organization, role and status saves remain separate requests; if a later request fails, the drawer reports that earlier changes may already be saved rather than claiming atomic success.

Check original buttons without spinners and stable form feedback, a toast for enabled single-delete attempts, confirmation before disabled single deletion, hidden delete buttons and disabled selection for super administrator accounts, batch-disable and batch-delete account tables, structured enabled-account refusal lists, disabled-only deletion preview, cancellation, mixed-batch refusal, stale-preview retry, current-list refresh and cleared selection in Chinese/English and a narrow viewport. Keep another device signed in: disabling must reject its next request/refresh, and enabling must not restore old credentials. Preserve existing fixtures; create isolated QA accounts for destructive checks. On 503 or uncertain results, retain the page and show the error rather than reporting completion; already revoked sessions may require a fresh login. Unconnected security and recovery controls remain outside this capability.

Account and role confirmations reuse the original MessageBox appearance and transitions. Single-account deletion has no table; account batch disable/delete and role single/batch deletion show content-sized tables without the warning icon. Confirmation width is capped at 1280px and table height at 600px, shrinking with the available viewport. Long headers and values wrap, short tables reserve no fixed height, and longer tables scroll internally. Check that wide content does not create an unnecessary page-edge scrollbar and that table scrolling still reaches the final row.

## Administrator password-reset regression

Use the actual test Compose environment with Chrome Computer Use and dedicated fixtures. In the existing account editor security tab, enter a nonblank new password and use the original Reset Password button, without an extra confirmation. Check unchanged draft/layout and no button spinner; failure retains input and success clears only the password field. A real second device must lose Access/Refresh while an unrelated account remains signed in.

Only the verbatim new password should support normal login; there is no temporary or forced-change page. Check repeated resets, own-operation M/D denial, protected targets, login/refresh races and failed-reset recovery separately over HTTP. Distinguish automated checks, actual HTTP, Computer Use and uncovered evidence. MFA binding is not implemented, so enabled-factor bypass protection cannot be reported as browser-verified. Preserve all earlier fixtures.

## License

[MIT](./LICENSE). Build output includes the license as `LICENSE.txt`.
