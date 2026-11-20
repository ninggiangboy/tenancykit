# Console frontend overhaul

Status: done (2026-11-20)

## Outcome

The Suite Console (`frontend/`) has a feature structure a person can navigate: each screen is composed from small
components and hooks instead of one 250 to 480 line `page.tsx`, and the list, sheet and dialog kit is shared instead of
copied into every remote. Every screen was captured and exercised against the compose stack, and the defects and UX
problems found there are fixed.

## Audit findings (2026-11-19, compose stack, 1440x900)

Defects:

1. Every `default`-variant button (Create, Save, Assign, Estimate...) renders as a black block with no label.
   `cn()` runs `tailwind-merge` without the theme's typography scale, so it reads `text-body-strong` as a text color
   and drops `text-primary-foreground`. Any component that combines a type size with a text color is affected.
2. Stripe shows Disconnect, Webhook secret and Reconcile while the tenant is not connected; the Return URLs hint is
   rendered twice.
3. Role sheet: the permissions hint is rendered twice; permissions are edited as free text although the generated
   permission catalog exists; Assign takes a raw user UUID.
4. Usage renders two page headers (Usage, then Events with the same description).
5. The breadcrumb shows the remote's machine name (`platform`, `billing`) and never the section.
6. An empty list shows `0-0` and disabled paging.
7. Tenant systems showed an empty table to a caller without the read permission; the screen now keeps its header and says the caller may not read it (the Platform administrator role holds no tenant-system read permission, which is why it has no nav entry there).
8. A platform-scope role shows the platform tenant UUID as its tenant.
9. Webhook delivery rows in the sheet wrap the timestamp onto three lines.

UX problems:

- Billing's 15 sections are a tab strip that wraps to two lines; the shell sidebar lists only apps.
- Home is an empty card ("Pick an app from the sidebar").
- Forms take raw IDs (customer, wallet, plan, user) and type enums as free text (wallet mode, plan model, billing
  cycle, currency); nothing is validated before the request.
- A sheet opens with focus on the "next record" arrow.

Not a console defect: the e2e `seedBilling` usage event is rejected silently (Billing has no usage rows), so Usage is
correctly empty. `make up` does not start `billing-worker`, so a tenant created after `make up` never reaches Billing.

## Structure decisions

- New workspace package `@suite/remote-kit` holds what both remotes copied byte for byte (`lib/page.tsx`,
  `lib/runtime.tsx`): the remote runtime provider, list state, the resource page, record sheet, dialogs, forms and its
  own `kit` i18n catalog. It is bundled into each remote like `@suite/ui` (not a federation singleton).
- A feature folder is `page.tsx` (composition only), `columns.tsx`, `components/`, `hooks/` (queries and mutations)
  and `schema.ts` (zod) when it has forms.
- Forms use React Hook Form with Zod 4, the versions already in the workspace catalog.
- `ShellContext` gains an optional `registerNavigation(sections)`. The shell renders a remote's sections under that app
  in the sidebar; a remote mounted by a shell without it keeps its own nav. The SDK major stays 1 because the field is
  optional on both sides.

## Phases

| # | Phase | Acceptance |
|---|-------|------------|
| 1 | `@suite/ui` fixes: `cn` typography groups, select and textarea controls, entity picker | primary buttons show their label; a unit test pins the merge |
| 2 | `@suite/remote-kit` from the duplicated libs, both remotes switched | no file under `remotes/*/src/lib` duplicates another remote |
| 3 | Shell navigation, breadcrumb, home | sections in the sidebar, breadcrumb `App > Section`, home lists apps |
| 4 | Billing features split, forms and UX fixes | no billing `page.tsx` over about 120 lines; findings 2, 4, 9 fixed |
| 5 | Platform features split, forms and UX fixes | findings 3, 7, 8 fixed |
| 6 | Verification | `pnpm turbo run lint typecheck test build` green; audit rerun with no defect left; e2e screens spec passes |
| 7 | Docs | `frontend/README.md` and `docs/console.md` describe the feature layout and the kit |

## Non-goals

No new screens or backend endpoints, no change to the registry, auth or deploy flow, no redesign of the design
tokens.

## Result

All phases done. Verification on 2026-11-20: Biome clean; `pnpm turbo run typecheck test build` green; the
Playwright suite passes 10 of 10 against the compose stack; every screen recaptured at 1440 and 390 pixels with no
console error other than Stripe's 404 for a tenant that never connected (a state, not a fault).
Beyond the findings above the work also fixed: a render loop when `ShellApp` got no `history` (it rebuilt the router on
every render and froze the page), Tailwind not scanning shared packages, a theme switch that left the page half-faded
for 120 ms, and a sidebar that took 60% of a phone screen.
