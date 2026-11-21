---
phase: 8
title: "Platform screens"
status: done
priority: P1
effort: "1.5d"
dependencies: [2, 4, 5, 6]
---

# Phase 8: Platform screens

## Goal
The five Platform screens (tenants, users, roles, systems, remotes) use the v4 kit: nav icons, a primary action, a `mobile` role on every column, no UUID column, sheets with actions under the title, and `danger` instead of filled destructive buttons.

## Read first
- `reference/design-system-v4/acceptance.md`, sections 2 to 6.
- `reference/targets/TenantAppsScreen-light-1280.png` and `RoleScreen-dark-1280.png`: Platform in its blue accent.
- `reference/targets/InvoicesScreen-light-1280.png`: the list and sheet pattern every screen follows.

## Files to create / modify
- Modify: `frontend/remotes/platform/src/routes.tsx` (the `icon` of each screen)
- Modify: `frontend/remotes/platform/src/features/{tenants,users,roles,systems,remotes}/columns.tsx`
- Modify: `frontend/remotes/platform/src/features/{tenants,users,roles,systems,remotes}/page.tsx` (the create button only)
- Modify: `frontend/remotes/platform/src/features/{tenants,users,roles,systems,remotes}/components/*-sheet.tsx` (footer to `actions`, destructive to `danger`)
- Modify: `frontend/remotes/platform/src/i18n/{en,vi}/*.json` (only when a step names a new key)
- Modify: the tests under `frontend/remotes/platform/src/` that assert changed markup (only those assertions)

## Before the tasks
Add the key `actions.more` to `frontend/remotes/platform/src/i18n/en/common.json` ("More actions") and `vi/common.json` ("Thao tác khác"). `fields.code` and `fields.id` already exist.

## Rules for every screen in this phase
1. **Columns:** give every column a `mobile` role from the tables below. Delete the `id` column (`IdValue`): the sheet shows the ID. If a sheet does not show the ID yet, add a row with `<CopyId value={record.id} />` labelled `tc('fields.id')`.
2. **Primary action:** move the page's create `Button` from `actions` to the new `primaryAction` prop of `ResourcePage` (phase 4).
3. **Sheet actions:**
   - move the buttons of the sheet's `footer` into `actions` and remove `footer`;
   - change `variant="destructive"` to `variant="danger"`;
   - drop `size="sm"`;
   - keep at most three buttons: when there are more, keep the first non-danger button and the danger button, and put the rest into a `DropdownMenu` (trigger: an outline icon-only `Button` with `icon="more"` and the label `tc('actions.more')`).

   Confirmation dialogs keep their filled `destructive` confirm button.
4. **Two-line cells:** the table keeps them. For the phone card, add a `cardOnly` column with `mobile: 'secondary'` for the second-line value, and set `cardText` on the two-line column to its first line.

## Tasks

### Task 8.1: Nav icons
- Goal: each Platform screen has a sidebar icon (L6).
- Target files: `frontend/remotes/platform/src/routes.tsx` (`screens`).
- Steps:
  1. Add `icon` to each screen: tenants `building`, users `users`, roles `shield`, systems `key`, remotes `grid`.
- Success criteria: the icons type-check as `IconName`.
- Verify: `pnpm --filter @suite/remote-platform typecheck` exits 0.

### Task 8.2: Tenants
- Goal: the tenants list and sheet follow v4.
- Target files: `features/tenants/columns.tsx`, `page.tsx`, `components/tenant-sheet.tsx`.
- Steps:
  1. Columns:

     | key | mobile | notes |
     |---|---|---|
     | `name` | `primary` | `cardText: (row) => row.name` |
     | `code` (new) | `secondary` | `cardOnly: true`, `cardText: (row) => row.code`, `header: tc('fields.code')` (add the key if missing) |
     | `status` | `badge` | |
     | `created_at` | `meta` | `cardText: (row) => row.createdAt?.slice(0, 10)` |

     Delete `id`.
  2. Apply rules 2 and 3. The sheet's actions are:
     - Add domain (outline);
     - Rename, in the More menu;
     - Deactivate (danger).
- Success criteria: no `IdValue` in the tenants table; the sheet has no footer.
- Verify: `pnpm --filter @suite/remote-platform test -- tenants` exits 0.

### Task 8.3: Users
- Target files: `features/users/columns.tsx`, `page.tsx`, `components/user-sheet.tsx`.
- Steps:
  1. Columns:

     | key | mobile | notes |
     |---|---|---|
     | `display_name` | `primary` | `cardText: (row) => row.displayName` |
     | `email` (new) | `secondary` | `cardOnly: true`, `cardText: (row) => row.email` |
     | `scope` | `secondary` | render the scope label as plain text: `<span className="text-small">{tc(\`scope.${row.scope}\`)}</span>`, not a `StatusBadge` (a scope is not a status, S1) |
     | `tenant` | `secondary` | |
     | `status` | `badge` | |

     Delete `id`.
  2. Apply rules 2 and 3. Rename stays one outline action.
- Verify: `pnpm --filter @suite/remote-platform test -- users` exits 0.

### Task 8.4: Roles
- Target files: `features/roles/columns.tsx`, `page.tsx`, `components/role-sheet.tsx`, `components/assign-user.tsx` (only `size="sm"` on the assign button: drop it).
- Steps:
  1. Columns:

     | key | mobile | notes |
     |---|---|---|
     | `name` | `primary` | |
     | `scope` | `secondary` | plain text, as for users |
     | `tenant` | `secondary` | |
     | `permissions` | `meta` | |

     Delete `id`.
  2. Apply rules 2 and 3.
- Verify: `pnpm --filter @suite/remote-platform test -- roles` exits 0.

### Task 8.5: Systems
- Target files: `features/systems/columns.tsx`, `page.tsx`, `components/system-sheet.tsx`, `components/credentials.tsx` (drop `size="sm"`).
- Steps:
  1. Columns:

     | key | mobile | notes |
     |---|---|---|
     | `name` | `primary` | `cardText: (row) => row.name` |
     | `purpose` (new) | `secondary` | `cardOnly: true`, `cardText: (row) => row.purpose` |
     | `status` | `badge` | |
     | `created_at` | `meta` | as in tenants |

     Delete `id`.
  2. Apply rules 2 and 3. The actions are Rename (outline) and Disable (danger).
- Verify: `pnpm --filter @suite/remote-platform test -- systems` exits 0.

### Task 8.6: Remotes
- Target files: `features/remotes/columns.tsx`, `page.tsx`, `components/remote-sheet.tsx`.
- Steps:
  1. Columns:

     | key | mobile | notes |
     |---|---|---|
     | `name` | `primary` | |
     | `audiences` | `secondary` | |
     | `activeVersion` | `hidden` | long; it is in the sheet |
     | `versions` | `meta` | |
     | `enabled` | `badge` | it is a `Switch`, which is fine in the badge slot |

  2. This screen has saved views (All, Enabled, Disabled). Pass this to `ResourcePage`:
     ```tsx
     viewCounts={useViewCounts(spec.views, (params) =>
       listRemotesOptions({ client: platform, throwOnError: true, query: asListQuery(params) }),
     )}
     ```
     Import `listRemotesOptions` from `@suite/api-client/platform/query` and get `platform` from `useApi()`, exactly as `features/remotes/hooks/use-remotes.ts` does.
- Verify: `pnpm --filter @suite/remote-platform test -- remotes` exits 0.

## Todo
- [x] Task 8.1: Nav icons
- [x] Task 8.2: Tenants
- [x] Task 8.3: Users
- [x] Task 8.4: Roles
- [x] Task 8.5: Systems
- [x] Task 8.6: Remotes
- [x] Phase gate passed

## Phase gate
- `pnpm --filter @suite/remote-platform test && pnpm typecheck && pnpm lint`, run in `frontend/`, exits 0.
- From the repository root, `grep -rn "IdValue\|variant=\"destructive\"" frontend/remotes/platform/src/features --include=columns.tsx --include=*-sheet.tsx` prints nothing.

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
