---
title: "Phase 8: Remote platform"
status: done
priority: P1
effort: "6d"
dependencies: [3, 7]
---

# Phase 8: Remote platform

## Goal

`remote-platform` is the UI of the Platform backend. It is mounted in both shells; each page appears only when `/me`
lists the permission its operations need (generated from `x-required-permission`), and the backend still decides.
Its own CI pipeline deploys it through the registry, which makes it the first real use of the deploy path.

## Pages

| Page | Operations (`contracts/openapi/platform.yaml`) | Permissions (`contracts/permissions/platform.yaml`) |
|---|---|---|
| Tenants (platform shell) | list, create, rename (`PUT /tenants/{id}`), deactivate, add domain (`POST /tenants/{id}/domains`) | `platform:tenant:*`, `platform:tenant-domain:manage` |
| Tenant app entitlements (tenant detail) | `GET /tenants/{tenantId}/apps` (phase 3), `PUT /tenants/{tenantId}/apps/{appCode}` with `If-Match` | `platform:tenant:read`, `platform:entitlement:manage` |
| Users | list, create, patch (enable, disable, rename), direct permission `PUT /users/{id}/permissions/{code}` | `platform:user:read`, `platform:user:manage` |
| Roles | list, create, edit, set permissions, assign and unassign a user | `platform:role:read`, `platform:role:manage` |
| Tenant systems (tenant scope) | list, create, rename, disable, credentials list, create, rotate, revoke | `platform:tenant-system:read`, `platform:tenant-system:manage`, `platform:credential:manage` |
| UI remotes (platform shell) | list with versions, enable or disable, activate a registered version (the only way a deploy goes live), rollback = activate an older one | `platform:ui-remote:read`, `platform:ui-remote:manage` |

A new credential or rotated secret is shown once, in a dialog that states it cannot be shown again, and is never kept in
query cache, logs or the URL.

## Language

Every user-visible string goes through the remote's own `i18next` instance (initialised from `ctx.locale`, reacting to
changes) with catalogs `src/i18n/en/*.json` and `src/i18n/vi/*.json`, one namespace per feature. Tests set a
`missingKeyHandler` that throws, and render each page of this phase in `en` and `vi`; a key present in one catalog and
absent in the other fails the test. Text from API responses is shown as returned.

## Design

Follow the design system rules in [plan.md](./plan.md). List pages use the pattern of the `InvoicesScreen` sample: title and
description on the left with Export and the one primary action on the right, segmented `Tabs` for saved views, a one-line
toolbar (search, `FacetedFilter`, `FilterChip`s, View), `DataTable` with rows that lead with what people scan for and an
id on the second line, the floating `BulkBar` while rows are selected, and a record `Sheet` that has its own URL. Each
phase lists below which of its actions are reversible (Undo toast) and which are not (impact `Dialog`).

- **Reversible, Undo toast:** enable an app for a tenant, rename a tenant or role, enable or disable a user, assign or
  unassign a role, change a role's permissions before saving, activate a registered UI remote version (undo re-activates
  the previous one).
- **Irreversible, impact `Dialog`:** deactivate a tenant (type the tenant code; lists what stops), disable an app that has
  live data (counts), revoke or rotate a credential (names the Tenant System and the expiry date), delete a role.
- **Samples to match:** tenant app entitlements follow `TenantAppsScreen` (optimistic `Switch` per app, `AppMark`
  tiles); roles and permissions follow `RoleScreen` (permission codes in mono, `UnsavedBar` with Cmd or Ctrl S).

## Files

- Create: `frontend/remotes/platform/src/**` (routes, pages, forms, tests), `.github/workflows/remote-platform.yml`
  (build, test, then `ops/console/deploy-remote.sh platform dist` on pushes to `dev`).
- Modify: `frontend/remotes/platform/package.json` (version and `sdkMajor`), `ops/console/deploy-remote.sh` only if the real
  remote exposes a gap.

## Steps

1. Read each operation's request and response in `platform.yaml`; use the generated hooks, not hand-written fetches.
2. Build list pages on `DataTable` with the filters the OpenAPI declares; detail and edit pages as sheets or routes.
3. Permission-gate navigation and controls with `usePermission` using the generated map; test that a user lacking a
   code never sees its control, and that the backend `403` is shown as a message when forced.
4. Optimistic-concurrency writes send `If-Match` from the entity `version`; a `412` or `409` shows a "changed
   elsewhere, reload" state.
5. Write the pipeline and deploy a version through the real registry; confirm both shells list it per audience.

## Todo

- [x] List pattern, peek sheet, URL state, Undo toasts and impact dialogs as classified above
- [x] `en` and `vi` catalogs for every string, both languages rendered in tests
- [x] Tenants with domains and entitlements
- [x] Users
- [x] Roles and assignments
- [x] Tenant systems and credentials with one-time secret display
- [x] UI remotes admin
- [x] Permission-gated navigation
- [x] Component tests and one MSW flow per page
- [x] CI workflow deploys through the registry

## Verification

```bash
cd frontend && pnpm --filter "@suite/remote-platform" test && pnpm turbo run lint typecheck build
```

and a manual pass on the compose stack of every page in both shells.

## Risks

The same remote in two shells means two permission sets; keep page visibility derived from `/me` only, never from the
shell. Entitlement toggles race with another operator; the `If-Match` handling covers it.

## Security

Secrets are shown once and held only in component state. Operator controls for the registry are gated on
`platform:ui-remote:manage` in the UI and enforced by Platform. No remote can call another remote's code.
