---
phase: 12
title: "Shell bell and remote refresh"
status: pending
priority: P1
effort: "3d"
dependencies: [5, 7, 11]
---

# Phase 12: Shell bell and remote refresh

## Goal

Both shells hold one realtime stream, show a bell with a live unread badge and a notification list, and the billing and
platform remotes refresh the queries their screens depend on when a related signal arrives.

## Context

- `frontend/packages/shell-core/src/frame/header.tsx` is the shell header; `boot.ts` and `contracts.ts` boot the shell
  and expose `ShellContext` from `@suite/shell-sdk` to remotes (`SDK_MAJOR = 1`, a breaking change bumps the major).
- `BootOptions.resolveBaseUrl` knows `'platform' | 'billing'` today; notification and realtime are new bases.
- `@suite/api-client` generates clients from `contracts/openapi/*.yaml` (`openapi-ts.platform.config.ts`,
  `openapi-ts.billing.config.ts`); notification has `contracts/openapi/notification.yaml` but no generated client yet.
- Notification's inbox endpoints (`/api/v1/notifications*`, preferences) work at both scopes.
- `@suite/ui` holds the design tokens and components; the remotes use `@suite/remote-kit` and TanStack Query.

## Requirements

1. api-client: add `openapi-ts.notification.config.ts` and a `./notification` and `./notification/query` export; update
   the `generate` script and regenerate (`pnpm --filter @suite/api-client generate`).
2. shell-core: start `@suite/realtime` after boot with `getToken` and the realtime base URL; stop it on sign-out or
   session end; expose `ctx.realtime.subscribe(pattern, handler)` on `ShellContext` as an additive member (optional in
   the type so older remotes still build; keep `SDK_MAJOR`).
3. Bell: an icon button in the header with the unread count (cap the display at `99+`), opening a list of recent
   notifications with mark-read and mark-all-read; data from the notification API through TanStack Query.
4. Live behaviour: on `notification.created` the bell takes `data.unread` (latest by signal id wins) and invalidates the
   notification list; one toast at most, additional ones collapse to "+N new notifications"; on resync the bell and list
   refetch.
5. Remote refresh: `remote-kit` gets `useRealtimeInvalidation(map)` taking `{ signalOrPrefix: queryKeysFactory }`; billing
   and platform remotes register maps for their lists and detail screens (for example invoice signals invalidate the
   invoices list and the invoice detail query for the referenced id), using the api-client query keys.
6. Both shells (platform and tenant) show the bell; text is in the existing i18n bundles for `en` and `vi`.
7. Accessibility: the button has an accessible name including the unread count; the list is keyboard operable; toasts
   use a polite live region.

## Files

Modify under `frontend/`:
- `packages/api-client/package.json`, `scripts/*`, add `openapi-ts.notification.config.ts`, generated output
- `packages/shell-sdk/src/index.ts` (additive `realtime` member and its type)
- `packages/shell-core/src/boot.ts`, `contracts.ts`, `frame/header.tsx`, new `frame/notification-bell.tsx`,
  `frame/notification-list.tsx`, `i18n.ts`
- `packages/remote-kit/src/runtime` (new hook) and `index.ts` export
- `remotes/billing/src/features/*` and `remotes/platform/src/features/*` (registration only, near each screen's queries)
- `packages/ui/src` if a bell icon or badge primitive is missing
- Tests for each, plus an e2e case in `frontend/e2e`

## Steps

1. Generate the notification client and add one query test that proves the unread count and list hooks type-check.
2. Add the SDK member and the shell wiring behind the boot sequence; test start and stop with a fake client.
3. Build the bell and list from `@suite/ui` primitives; test unread cap, mark-read optimistic update then REST truth, and
   the `+N` toast collapse.
4. Add `useRealtimeInvalidation` and its tests (a signal invalidates exactly the mapped keys, once per batch).
5. For each remote, list the screens and the signals that affect them (from the phase 8 mapping tables) and register the
   maps; add a test per remote proving a signal triggers one refetch of a visible query and none for an inactive one.
6. Add an e2e scenario: a user is granted access, the bell increments without reload, an invoice paid event refreshes the
   open invoices list (uses the smoke fixtures of phase 13).
7. Run `pnpm lint`, `pnpm typecheck`, `pnpm test`, `pnpm build` from `frontend/`.

## Todo

- [ ] Notification client generated and exported
- [ ] Shell wiring and SDK member
- [ ] Bell, list, toast collapse, i18n, accessibility
- [ ] `useRealtimeInvalidation` hook
- [ ] Billing remote registrations and tests
- [ ] Platform remote registrations and tests
- [ ] E2E scenario
- [ ] Lint, typecheck, tests, build green

## Verification

- From `frontend/`: `pnpm lint`, `pnpm typecheck`, `pnpm test`, `pnpm build` pass.
- Burst test at the UI level: 1,000 signals in one second cause at most one refetch per visible query key and at most
  one visible toast.
- E2E: the bell updates without a reload and the open list refreshes (run against the local stack after phase 13).

## Risks

- Changing `ShellContext` can break remotes built against the old SDK; the member is optional and additive, and a test
  keeps a remote without it working.
- Optimistic mark-read can disagree with the server; the next refetch (REST) always wins.
- Bell data for the platform scope reads the Platform tenant's rows; verify with a platform-scope user in e2e.

## Security

- The client never injects payload text into the DOM; the toast message comes from the translated signal type.
- Tokens stay inside `@suite/auth`; the realtime client receives them only through `getToken`.

## Next

Phase 13.
