---
phase: 6
title: "Shell frame: accent, density, nav, phone and tablet"
status: done
priority: P1
effort: "2d"
dependencies: [3]
---

# Phase 6: Shell frame: accent, density, nav, phone and tablet

## Goal
The shell sets the open app's accent and the person's density on `<html>`. The sidebar shows the current app once, with an icon on every screen. It is a drawer on phones and a 56px icon rail on tablets, and phones get a top bar. The platform scope line is gone.

## Read first
- `reference/design-system-v4/README.md`, sections "Colour" (the app accent), "Spacing, size and layout" (the shell) and "Responsive".
- `reference/design-system-v4/components/bundle.css`, `/* Shell… */` to the end, and `/* Phone shell… */`.
- `reference/targets/InvoicesScreen-light-1280.png` (sidebar), `InvoicesScreen-light-900.png` (icon rail), `InvoicesPhone-light-390.png` (top bar).
- acceptance.md checks T3, T4, L2, L6, C8, M3, M9.

**Why `<html>` and not the frame's `div`:** Radix portals sheets, dialogs and menus into `<body>`, outside the frame. Attributes on `<html>` reach them, which is why the theme already lives there as `.dark`.

## Files to create / modify
- Modify: `frontend/packages/shell-sdk/src/index.ts` (`NavItem`)
- Modify: `frontend/packages/remote-kit/src/navigation/remote-router.tsx` (`Screen`, `useSections`)
- Modify: `frontend/packages/shell-core/src/theme.ts`, `theme.test.ts`
- Modify: `frontend/packages/shell-core/src/ShellApp.tsx` (`FrameLayout`, density state)
- Modify: `frontend/packages/shell-core/src/frame/frame-context.ts`, `sidebar.tsx`, `header.tsx`
- Modify: `frontend/packages/shell-core/src/i18n.ts`
- Modify: the shell tests under `frontend/packages/shell-core/src/` that assert the old sidebar or header markup (only those assertions)

## Tasks

### Task 6.1: Screens carry an icon to the shell
- Goal: every nav item can show a Lucide icon (L6).
- Target files: `frontend/packages/shell-sdk/src/index.ts` (`NavItem`); `frontend/packages/remote-kit/src/navigation/remote-router.tsx` (`Screen`, `useSections`).
- Steps:
  1. In `NavItem`, add `icon?: string` with the JSDoc "A Lucide icon name from `@suite/ui`'s `IconName`; the shell falls back to `file`." Do not change `SDK_MAJOR`: the field is optional.
  2. In `Screen`, add `icon?: IconName` (import the type from `@suite/ui`).
  3. In `useSections`, copy `screen.icon` into each `NavItem`.
- Success criteria: the field flows from a screen to the shell.
- Verify: `pnpm --filter @suite/shell-sdk test`, `pnpm --filter @suite/remote-kit test` and `pnpm typecheck` exit 0.

### Task 6.2: Density preference
- Goal: density is stored per browser like the theme and applied as `data-density` on `<html>` (L2).
- Target files: `frontend/packages/shell-core/src/theme.ts` and `theme.test.ts`; `frame-context.ts` (`Frame`); `ShellApp.tsx`.
- Steps:
  1. In `theme.ts`, add `export type Density = 'comfortable' | 'compact'`, `DENSITY_KEY = 'suite.density'`, `preferredDensity(): Density` (stored value, or `comfortable`), `saveDensity(density)`, and `applyDensity(density)`, which sets `document.documentElement.dataset.density = density`.
  2. Tests in `theme.test.ts`: `preferredDensity()` is `comfortable` with empty storage, and `compact` after `saveDensity('compact')`; `applyDensity('compact')` sets `document.documentElement.dataset.density` to `compact`.
  3. In `ShellApp.tsx`, add `const [density, setDensity] = useState(preferredDensity)` and an effect that calls `applyDensity` and `saveDensity`. Add `density` and `onDensity` to the `Frame` object and to the `Frame` interface in `frame-context.ts`.
- Success criteria: reloading keeps the choice.
- Verify: `pnpm --filter @suite/shell-core test -- theme` exits 0.

### Task 6.3: The app accent on `<html>`
- Goal: inside a remote, `<html data-app="<appCode>">`; on Home, no `data-app` (T3, T4).
- Target files: `frontend/packages/shell-core/src/ShellApp.tsx` (`FrameLayout`).
- Steps:
  1. In `FrameLayout`, read the place the same way `sidebar.tsx` does (`currentPlace(...)` with the router's pathname and `frame.remotes`).
  2. Add an effect: when `place.remote` exists, set `document.documentElement.dataset.app = place.remote.appCode`; otherwise `delete document.documentElement.dataset.app`. Clean up by deleting it.
  3. Delete the `<span aria-hidden className="absolute inset-x-0 top-0 h-0.5 rounded-t-xl bg-scope-platform" />` platform line.
  4. Add a shell test: render the shell at `/console/r/billing/invoices` with a fake remote whose `appCode` is `billing`, and assert `document.documentElement.dataset.app === 'billing'`. Then navigate to `/console/` and assert it is `undefined`. Use the existing shell test helpers in `ShellApp.test.tsx` as the model.
- Success criteria: the primary button inside Billing renders violet.
- Verify: `pnpm --filter @suite/shell-core test` exits 0.

### Task 6.4: Sidebar: the current app once, icons, other apps, accent
- Goal: the sidebar looks like the one in `reference/targets/InvoicesScreen-light-1280.png` (L6).
- Target files: `frontend/packages/shell-core/src/frame/sidebar.tsx`.
- Steps:
  1. Switcher avatar: `bg-foreground text-background` instead of `bg-primary text-primary-foreground`, so it stays neutral inside an app.
  2. Render the open remote first: one link with `<AppMark app={remote.appCode} size="sm" />` and its title in `font-semibold`. Then its sections:
     - each section label is `px-2.5 pt-3 pb-1 text-caption text-muted-foreground`;
     - each item is `flex h-9 items-center gap-2.5 rounded-md px-2.5 text-body` with `<Icon name={item.icon ?? 'file'} size={16} className="text-muted-foreground" />` and the label in `<span className="truncate">`;
     - the active item is `bg-app-accent-soft text-primary font-semibold`, and its icon `text-primary`.

     Remove the left border line (`border-l pl-2 ml-3`).
  3. Below the open app, render the other remotes under a label `t('shell.otherApps')`, at the bottom of the nav (`mt-auto border-t border-sidebar-border pt-2`). Each is `AppMark size="sm"` plus its title.
  4. On Home (no open remote), list every remote as an app link under `t('shell.remotes')`.
  5. Item height on phones: `max-md:h-11 max-md:text-[15px]` (M2).
- Success criteria: "Platform" appears once in the sidebar on the Platform shell.
- Verify: `pnpm --filter @suite/shell-core test` exits 0. Update only the sidebar assertions that test the removed markup.

### Task 6.5: Tablet icon rail and phone drawer
- Goal: 768px to 1023px shows a 56px rail with tooltips; below 768px the sidebar is a drawer behind a scrim (M3, M9).
- Target files: `frontend/packages/shell-core/src/frame/sidebar.tsx`; `ShellApp.tsx` (the `collapsed` state); `frame-context.ts` (`isNarrowViewport` stays).
- Steps:
  1. Compute `tablet = useMedia(TABLET_QUERY)` and `phone = useMedia(PHONE_QUERY)` in the sidebar, using the hooks from `@suite/ui`.
  2. Rail (`tablet || collapsed`, and not `phone`): width `w-14`; hide every label, section label and the "Other apps" label; centre the icons; wrap each link in `<Tooltip content={label}>`.
  3. Drawer (`phone`): the sidebar is `fixed inset-y-0 left-0 z-50 w-[300px] bg-sidebar p-3 shadow-xl transition-transform`, translated off screen (`-translate-x-full`) unless `drawerOpen`. When open, render a scrim `fixed inset-0 z-40 bg-overlay` that closes it on click. Esc closes it. Navigating closes it (keep the existing pathname effect, applied to `drawerOpen`).
  4. Keep `drawerOpen` in `ShellApp` next to `collapsed`. Expose `drawerOpen` and `onToggleDrawer` on `Frame`.
- Success criteria: three distinct layouts at 1280, 900 and 390 pixels.
- Verify: `pnpm --filter @suite/shell-core test` exits 0. Then, with the stack and console running (`make up` and `make console` from the repository root; reuse them if they already run), open the tenant console at 900px and at 390px in Playwright or a browser, and confirm that the sidebar is 56px wide at 900 and hidden at 390. Measure with `document.querySelector('aside').getBoundingClientRect().width`: expect `56` at 900, and at 390 an `x` below 0 while closed.

### Task 6.6: Header on phones, and the account menu with density
- Goal: phones get a top bar; the account menu holds density (L2).
- Target files: `frontend/packages/shell-core/src/frame/header.tsx`; `frontend/packages/shell-core/src/i18n.ts`.
- Steps:
  1. Add to `i18n.ts`, with `en` / `vi`:

     | Key | en | vi |
     |---|---|---|
     | `shell.density` | Density | Mật độ |
     | `shell.comfortable` | Comfortable | Thoáng |
     | `shell.compact` | Compact | Gọn |
     | `shell.otherApps` | Other apps | Ứng dụng khác |
     | `shell.openNavigation` | Open navigation | Mở điều hướng |

  2. Phones (`max-md:`):
     - the header is a 56px sticky bar with, in order: a 44px icon button (`aria-label={t('shell.openNavigation')}`, icon `panel-left`) that calls `onToggleDrawer`; the open app's `AppMark size="sm"` and the page label (or `t('shell.home')`) in `text-[17px] font-semibold truncate`; a 44px search icon button that opens the palette; the account menu.
     - Hide the breadcrumb, the scope pill and the language button below `md`. Language stays reachable in the account menu (step 3).
  3. Account menu: always render it, not only when `onSignOut` exists. Its trigger is a 28px avatar (`bg-secondary`, initial). Its items, in order:
     - heading `me.email`;
     - heading `t('shell.density')`, then `t('shell.comfortable')` and `t('shell.compact')`, with a `check` icon on the current one, calling `onDensity`;
     - a separator;
     - on phones only, heading `t('shell.language')` with English and Vietnamese;
     - `t('shell.signOut')` when `onSignOut` exists.
  4. Keep the existing Theme button and Language button, with the same accessible names, on `md` and up: the e2e suite clicks `Theme` and `Language`.
- Success criteria: from the account menu a person switches to Compact and rows shrink to 40px.
- Verify: `pnpm --filter @suite/shell-core test` exits 0. Add a test that opens the account menu, clicks "Compact" and asserts `document.documentElement.dataset.density === 'compact'`.

## Todo
- [x] Task 6.1: Screens carry an icon to the shell
- [x] Task 6.2: Density preference
- [x] Task 6.3: The app accent on `<html>`
- [x] Task 6.4: Sidebar: the current app once, icons, other apps, accent
- [x] Task 6.5: Tablet icon rail and phone drawer
- [x] Task 6.6: Header on phones, and the account menu with density
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
