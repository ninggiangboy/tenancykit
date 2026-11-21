---
phase: 2
title: "Bug fixes and the i18n key test"
status: done
priority: P1
effort: "1d"
dependencies: [1]
---

# Phase 2: Bug fixes and the i18n key test

## Goal
The visible bugs from the 2026-11-20 screenshots are gone, and a test fails whenever code uses a message key that a catalogue does not hold.

## Read first
- `reference/design-system-v4/README.md`, section "Content fundamentals" (error copy, breadcrumbs).
- `reference/design-system-v4/acceptance.md`, checks C5, C7, C8 and S5.

## Files to create / modify
- Modify: `frontend/packages/shell-core/src/i18n.ts`, `frontend/packages/shell-core/src/frame/frame-context.ts`, `frontend/packages/shell-core/src/ShellApp.tsx` (only the `t` typing), `frontend/packages/shell-core/src/frame/header.tsx` (breadcrumb only)
- Modify: `frontend/packages/remote-kit/src/i18n/kit-catalog.ts`, `frontend/packages/remote-kit/src/layout/page.tsx` (`Forbidden`), `frontend/packages/remote-kit/src/layout/error-panel.tsx`, `frontend/packages/remote-kit/src/navigation/remote-router.tsx` (pass the permission to `Forbidden`)
- Modify: `frontend/packages/ui/src/messages.tsx` (`error.hint` in en and vi)
- Modify: `frontend/remotes/platform/src/features/{tenants,users,roles,systems,remotes}/components/*-sheet.tsx` (the `eyebrow` only), and `frontend/remotes/platform/src/i18n/{en,vi}/{tenants,users,roles,systems,remotes}.json`
- Modify: `frontend/remotes/platform/src/features/tenants/components/tenant-apps.tsx` (remove the On/Off badge)
- Create: `frontend/remotes/platform/src/i18n/keys.test.ts`, `frontend/remotes/billing/src/i18n/keys.test.ts`

## Tasks

### Task 2.1: Type the shell's translate function
- Goal: the shell cannot compile with an unknown message key, which is how `shell.open` reached the screen.
- Target files: `frontend/packages/shell-core/src/frame/frame-context.ts` (the `Frame` interface, field `t`); `frontend/packages/shell-core/src/ShellApp.tsx` (the `t` callback); `frontend/packages/shell-core/src/i18n.ts`.
- Steps:
  1. In `frame-context.ts`, import `type ShellMessageKey` from `../i18n` and change `t: (key: string) => string` to `t: (key: ShellMessageKey) => string`.
  2. In `ShellApp.tsx`, change `useCallback((key: string) => …)` to take `key: ShellMessageKey`.
  3. Run `pnpm --filter @suite/shell-core typecheck`. It now fails on `t('shell.open')` in `home.tsx` and on any other unknown key.
  4. In `i18n.ts`, add `'shell.open': 'Open'` to `en` and `'shell.open': 'Mở'` to `vi`. Add any other key the typecheck reported, with real English and Vietnamese text.
- Success criteria: the shell typechecks, and an unknown key is a type error.
- Verify: `pnpm --filter @suite/shell-core typecheck` exits 0. Then add a line `t('shell.doesNotExist')` in `home.tsx`, confirm the same command exits non-zero, and remove the line.

### Task 2.2: A key test for each remote
- Goal: every literal key a remote passes to its translate functions exists in the English catalogue (the existing test already keeps Vietnamese equal to English).
- Target files: create `frontend/remotes/billing/src/i18n/keys.test.ts` and `frontend/remotes/platform/src/i18n/keys.test.ts`, with the same content.
- Steps:
  1. Write a Vitest test that:
     - lists every `.ts` and `.tsx` file under `src/` except `*.test.*`, using `readdirSync` recursively from `fileURLToPath(new URL('..', import.meta.url))`;
     - in each file, finds the bindings `const <name> = useT('<namespace>')` with the regex `/const (\w+) = useT\('(\w+)'\)/g`;
     - for each binding, finds the literal calls `<name>('<key>'` with `new RegExp(\`\\b${name}\\('([^'\`]+)'\`, 'g')` and expects `key in resources.en[namespace]`, with the message `\`${file}: ${namespace}:${key}\``;
     - also finds the strings `'<namespace>:<key>'` in `routes.tsx` with `/'(\w+):([\w.]+)'/g` and checks those the same way.
  2. Template-literal keys (`tc(\`scope.${x}\`)`) are not checked by this test; the runtime guard in `createRemoteI18n` already throws on them in component tests.
- Success criteria: the test passes today and fails when a key is misspelled.
- Verify: `pnpm --filter @suite/remote-billing test -- keys` and `pnpm --filter @suite/remote-platform test -- keys` both exit 0. Then change one `t('title')` in `remotes/billing/src/features/invoices/page.tsx` to `t('titlex')`, confirm the billing command exits non-zero, and revert the change.

### Task 2.3: Breadcrumbs without the repeated shell segment
- Goal: crumbs read "Billing › Invoices" and "Platform › Tenants", never "Platform › Platform › Tenants" (C8).
- Target files: `frontend/packages/shell-core/src/frame/header.tsx`, the `<nav aria-label="Breadcrumb">` element.
- Steps:
  1. Delete the first `<Link to="/">…{definition.title}</Link>` and the chevron `Icon` right after it.
  2. Keep the app segment and the page segment as they are. On Home the crumb stays `t('shell.home')`.
- Success criteria: the breadcrumb has at most two segments.
- Verify: `pnpm --filter @suite/shell-core test` exits 0. If a shell test asserted the old three-segment crumb, update that assertion to the two segments, and change nothing else in the test.

### Task 2.4: Error copy that fits the action
- Goal: a failed read no longer says "Nothing changed", and a forbidden page names the missing permission (C7).
- Target files: `frontend/packages/remote-kit/src/i18n/kit-catalog.ts`; `frontend/packages/remote-kit/src/layout/page.tsx` (`Forbidden`); `frontend/packages/remote-kit/src/layout/error-panel.tsx`; `frontend/packages/remote-kit/src/navigation/remote-router.tsx`; `frontend/packages/ui/src/messages.tsx`.
- Steps:
  1. In `kit-catalog.ts`, add to `en`:
     - `'state.readFailed': 'Could not load this.'`
     - `'state.readFailedHint': 'Retry. If it keeps failing, reload the page.'`
     - `'state.forbiddenHint': 'You need the {{permission}} permission to see this page. Ask an administrator for it.'`
     - `'state.forbiddenHintGeneric': 'Ask an administrator for access to this page.'`

     Add the same keys to `vi`:
     - `'Không tải được nội dung này.'`
     - `'Hãy thử lại. Nếu vẫn lỗi, tải lại trang.'`
     - `'Bạn cần quyền {{permission}} để xem trang này. Hãy nhờ quản trị viên cấp quyền.'`
     - `'Hãy nhờ quản trị viên cấp quyền truy cập trang này.'`

     Keep `state.errorHint` ("Nothing changed") for failed writes only.
  2. `Forbidden` takes `permission?: string`. Its description is `t('state.forbiddenHint', { permission })` when a permission is given, else `t('state.forbiddenHintGeneric')`.
  3. In `remote-router.tsx`, where a screen is refused (`<Forbidden bare />` near line 100), pass `permission={screen.permission}`.
  4. In `error-panel.tsx`, the title becomes `t('state.readFailed')` and the description `t('state.readFailedHint')`. Keep the Retry button.
  5. In `messages.tsx`, change `'error.hint'` to `'Retry. If it keeps failing, reload the page.'` in `en` and `'Hãy thử lại. Nếu vẫn lỗi, tải lại trang.'` in `vi`.
- Success criteria: no read or forbidden state renders "Nothing changed".
- Verify: `pnpm --filter @suite/remote-kit test` and `pnpm --filter @suite/ui test` exit 0. Then `grep -rn "errorHint" frontend/packages/remote-kit/src --include=*.tsx`, run from the repository root, prints no line under `layout/`.

### Task 2.5: Singular sheet eyebrows on Platform
- Goal: a tenant's sheet says "Tenant", not "Tenants".
- Target files: the five sheets listed above (each passes `eyebrow={t('title')}`), and their `en` and `vi` catalogues.
- Steps:
  1. Add a `recordType` key to each namespace:

     | Namespace | en | vi |
     |---|---|---|
     | tenants | Tenant | Tenant |
     | users | User | Người dùng |
     | roles | Role | Vai trò |
     | systems | Tenant system | Hệ thống tenant |
     | remotes | UI remote | UI remote |

  2. In each sheet, change `eyebrow={t('title')}` to `eyebrow={t('recordType')}`.
- Success criteria: eyebrows are singular.
- Verify: `pnpm --filter @suite/remote-platform test` exits 0, including `keys` and `catalog`.

### Task 2.6: Remove the On/Off badge beside the entitlement switch
- Goal: a switch is the state; no badge repeats it (S5).
- Target files: `frontend/remotes/platform/src/features/tenants/components/tenant-apps.tsx`, the `<StatusBadge tone={enabled ? 'success' : 'neutral'}>…</StatusBadge>` near line 61.
- Steps:
  1. Delete that `StatusBadge` element and its children. Keep the `Switch` and its accessible label.
  2. Remove `StatusBadge` from the import if nothing else uses it.
  3. If a test asserted the "On" or "Off" badge text, change it to assert the switch's checked state (`toBeChecked()` / `not.toBeChecked()`).
- Success criteria: each app row shows the mark, the name, the code and the switch only.
- Verify: `pnpm --filter @suite/remote-platform test` exits 0, and `grep -n "StatusBadge" frontend/remotes/platform/src/features/tenants/components/tenant-apps.tsx` (from the repository root) prints nothing.

## Todo
- [x] Task 2.1: Type the shell's translate function
- [x] Task 2.2: A key test for each remote
- [x] Task 2.3: Breadcrumbs without the repeated shell segment
- [x] Task 2.4: Error copy that fits the action
- [x] Task 2.5: Singular sheet eyebrows on Platform
- [x] Task 2.6: Remove the On/Off badge beside the entitlement switch
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
