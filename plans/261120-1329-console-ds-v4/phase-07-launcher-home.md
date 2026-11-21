---
phase: 7
title: "Launcher home"
status: done
priority: P2
effort: "0.5d"
dependencies: [6]
---

# Phase 7: Launcher home

## Goal
Home greets the person and shows one card per enabled app, in that app's accent, with its purpose, a sentence and links to its common screens (L7).

## Read first
- `reference/design-system-v4/README.md`, the "Home" bullet under "Spacing, size and layout".
- `reference/design-system-v4/components/HomeLauncher/preview.html` and the `.sc-launcher` / `.sc-appcard` rules in `bundle.css`.
- `reference/targets/HomeLauncher-light-1280.png` and `HomeLauncher-dark-1280.png`.

## Files to create / modify
- Create: `frontend/packages/shell-core/src/frame/app-catalog.ts`, `frontend/packages/shell-core/src/frame/app-catalog.test.ts`
- Modify: `frontend/packages/shell-core/src/frame/home.tsx` (`Home`)
- Modify: `frontend/packages/shell-core/src/i18n.ts`

## Tasks

### Task 7.1: The app catalogue
- Goal: one static place says what each app is for and which screens to link from Home.
- Target files: create `frontend/packages/shell-core/src/frame/app-catalog.ts`.
- Steps:
  1. Export the type `AppEntry = { purpose: ShellMessageKey; description: ShellMessageKey; shortcuts: Array<{ label: ShellMessageKey; path: string }> }`.
  2. Export `const appCatalog: Partial<Record<AppCode, AppEntry>>` with two entries:
     - `billing`: purpose `apps.billing.purpose`, description `apps.billing.description`, and shortcuts with paths `invoices`, `payments`, `customers` and `usage`;
     - `platform`: purpose `apps.platform.purpose`, description `apps.platform.description`, and shortcuts with paths `users` and `roles`; on the Platform shell also `tenants`.

     Use `AppCode` from `@suite/ui`. Shortcut labels reuse existing keys where possible; otherwise add keys as `apps.<app>.<path>`.
  3. Export `appEntry(appCode: string, audience: 'PLATFORM' | 'TENANT'): AppEntry | undefined`. For `platform` it returns the `tenants` shortcut only when `audience === 'PLATFORM'`.
  4. Add the keys to `i18n.ts`, in `en` and `vi`:

     | Key | en | vi |
     |---|---|---|
     | `apps.billing.purpose` | Invoices, payments, wallets and usage | Hoá đơn, thanh toán, ví và mức sử dụng |
     | `apps.billing.description` | Charge customers, issue invoices, take card payments through Stripe and meter usage against wallets. | Thu tiền khách hàng, xuất hoá đơn, nhận thanh toán thẻ qua Stripe và tính mức sử dụng trên ví. |
     | `apps.platform.purpose` | Users, roles and access | Người dùng, vai trò và quyền truy cập |
     | `apps.platform.description` | Invite staff, assign roles and choose which apps and domains are in use. | Mời nhân viên, gán vai trò và chọn ứng dụng, tên miền được dùng. |
     | `shell.greeting` | Hello, {{name}} | Xin chào, {{name}} |
     | `shell.appsEnabled` | {{count}} apps enabled | {{count}} ứng dụng đang bật |

     Add the shortcut labels too: Invoices / Hoá đơn, Payments / Thanh toán, Customers / Khách hàng, Usage / Mức sử dụng, Users / Người dùng, Roles / Vai trò, Tenants / Tenant.
  5. Test in `app-catalog.test.ts`:
     - `appEntry('billing','TENANT')?.shortcuts.map((s) => s.path)` equals `['invoices','payments','customers','usage']`;
     - `appEntry('platform','TENANT')` has no `tenants` shortcut and `appEntry('platform','PLATFORM')` has one;
     - `appEntry('unknown','TENANT')` is `undefined`.
- Success criteria: typecheck proves every key exists. The shell's `t` is typed since phase 2; if `i18n.ts` interpolation takes values, widen `t` to `(key: ShellMessageKey, values?: Record<string, unknown>) => string`.
- Verify: `pnpm --filter @suite/shell-core test -- app-catalog` and `pnpm --filter @suite/shell-core typecheck` exit 0.

### Task 7.2: The launcher
- Goal: Home looks like `reference/targets/HomeLauncher-light-1280.png`.
- Target files: `frontend/packages/shell-core/src/frame/home.tsx` (`Home`).
- Steps:
  1. Header: `h1` `t('shell.greeting', { name: me.displayName })` in `text-title`; below it, in `text-small text-muted-foreground`, the tenant or platform name and `t('shell.appsEnabled', { count: remotes.length })`. The page padding is `px-(--page-pad) py-6`.
  2. Grid: `grid gap-4 [grid-template-columns:repeat(auto-fill,minmax(280px,1fr))]`.
  3. Each card is a `div` with `data-app={remote.appCode}` and the classes `flex flex-col gap-3 rounded-lg bg-card p-5 shadow-[0_0_0_1px_var(--border)] hover:shadow-[0_0_0_1px_var(--app-accent)]`:
     - a head: a `Link` to the remote (`/r/$remote/$`, empty splat) wrapping `<AppMark app={remote.appCode} size="lg" />`, the title in `text-[15px] font-semibold`, and the purpose in `text-small text-muted-foreground`;
     - the description in `text-[13.5px] text-muted-foreground`;
     - the shortcuts: `Link`s with `rounded-md bg-app-accent-soft px-2.5 py-1 text-small font-medium text-app-accent`.

     An app without a catalogue entry shows the head only.
  4. Remove the `appCode` mono line and the "Open ↗" text.
  5. Keep the empty state for a tenant with no apps.
- Success criteria: no raw key, one card per remote, accent per card.
- Verify: `pnpm --filter @suite/shell-core test` exits 0. Add a test: render Home with remotes `billing` and `platform`, and assert two cards, the link text "Invoices", and `data-app="billing"` on the first card.

## Todo
- [x] Task 7.1: The app catalogue
- [x] Task 7.2: The launcher
- [x] Phase gate passed

## Phase gate
- `pnpm typecheck && pnpm test && pnpm lint`, run in `frontend/`, exits 0.

## Failure Protocol
If any Verify step does not meet its stated pass condition, STOP this phase.
Do not improvise a fix, retry blindly, or reason around the failure.
Spawn the `kongming` subagent for next-step counsel and pass:
- the phase and task id,
- what you attempted (the steps you ran),
- the exact command and its full output,
- the pass condition it failed to meet.
Apply kongming's guidance, then re-run the Verify step.
If `kongming` cannot be spawned in this environment, STOP and report the same failure evidence to the user. Never continue by self-reasoning.
