Suite Console is the one web console of the suite. Platform operators use it at **platform scope** to run tenants, users, roles, entitlements and credentials for everyone. A tenant's staff use it at **tenant scope** (`<tenant-code>.<base-domain>`) to work in the apps their tenant has enabled: Billing, Customer, Mailroom, Survey, Attribution, Booking, Loyalty, Workflow, Helpdesk and Notification.

The system follows shadcn/ui: the same token roles (`background`/`foreground` pairs, `muted`, `accent`, `border`, `input`, `ring`, `sidebar-*`, `chart-*`), oklch values, one radius base, Tailwind's spacing and shadow ramps, and the same component anatomy. It adds what an operations console needs and shadcn leaves out: status colours, platform scope, app identity, money in minor units, keyboard-first navigation and record peek.

## What this system changes from shadcn

- **Monochrome first.** Zinc neutrals carry the whole interface. The primary button, checked checkboxes, switches and the bulk bar are `primary` (zinc-900, zinc-50 in dark). Colour appears only where it means something.
- **One blue, used sparingly.** `brand` is for focus, links, the selected-count chip and `chart-1`. `selection` is a faint blue for selected rows. Nothing else is blue.
- **Status tokens.** `success`, `warning`, `destructive` and `info` (an alias of `brand`) colour a 6px dot or an icon, never a block. A status always pairs its dot with a word.
- **A dark theme with depth.** `sidebar` < `background` < `card` < `popover` step up in lightness, and borders are alpha white, so raised surfaces read without heavy shadows.
- **Faint, layered shadows.** Floating surfaces get a 1px ring and a soft drop (`shadow-md` to `shadow-xl`). Containers rely on borders.
- **Vietnamese.** Inter carries full Vietnamese diacritics. Geist Mono is kept for codes only (ASCII).

## Content fundamentals

- **Name things the way the person sees them.** "Rotate secret", "Apps", "Created". Field names and enum values appear only where people copy them: in mono, in tooltips, in permission codes.
- **Sentence case everywhere**: titles, buttons, tabs, labels. Enum values (`PAST_DUE`) are shown humanized ("Past due") by `StatusBadge`.
- **Buttons are a verb and an object**: "Void invoice", "Disable Mailroom". A confirming button repeats that verb, never "OK".
- **Say what happened and what to do, with real names and dates.** "Rotate before 23 Nov 2026 or Acme's Tenant System stops getting tokens." Errors say that nothing changed when nothing did. No apologies, no exclamation marks, no emoji.
- **Toasts are past tense**: "Loyalty enabled for acme". Alerts are present facts: "Secret expires in 6 days".
- **Numbers carry units.** Money always shows its currency code (`12,500.00 USD`, `3,200,000 VND`). Times are UTC, and the column header says so ("Created (UTC)").

## Visual foundations

### Color

- The sidebar frame is `sidebar`, the inset main panel is `background`, cards and tables are `card`, and everything that floats is `popover`.
- Text is `foreground`. Secondary text is `muted-foreground` (5:1 or more on every surface). Quiet fills are `muted`, and hover is `accent`.
- `border` draws hairlines. Control edges use `input`, which is as light as shadcn's (about 1.5:1); controls also carry `shadow-xs` and a solid 2px `ring` on focus. Where 3:1 control edges are required, raise `input` to oklch(0.68 0.01 286) in light and #ffffff40 in dark.
- `primary` marks the one main action per view and every "on" state. `brand` marks focus, links and `chart-1`. `selection` marks selected rows.
- Status lives in small marks: the dot of a `StatusBadge`, the icon of an `Alert` or toast, a delta's arrow. Danger also colours its text. No tinted blocks.
- **Scope.** At platform scope, a 2px `scope-platform` line runs along the top of the inset panel, the header shows a "Platform scope" pill with an amber dot, and the switcher shows the shield. At tenant scope, the switcher shows the tenant and its domain.
- **Apps** are told apart by a Lucide glyph in a neutral tile (`AppMark`), never by colour.
- **Charts.** `chart-1` is the measure the chart is about; `chart-2` to `chart-4` are further series; `chart-5` is the previous period or a target.

### Type

- One family: Inter (`--font-sans`) at 14px for UI, with its display optical size and tight tracking for headings (`text-title` 22/28 at −0.025em, `text-metric` 28/32 at −0.03em). Body text runs at −0.006em.
- Card titles are `text-heading` (15/22, 600). Descriptions and hints are `text-small` (13/18, `muted-foreground`). Badges, kbd and column headers use `text-caption`.
- Identifiers (`INV-2026-00412`, `cus_0193a1c0`) stay in Inter with tabular figures. Geist Mono (`text-code`) is only for permission codes, scopes, keys and request syntax.

### Spacing, size and layout

- Tailwind's 4px steps: `space-1` 4, `space-1_5` 6, `space-2` 8, `space-3` 12, `space-4` 16, `space-5` 20, `space-6` 24, `space-10` 40. Cards pad `space-5`, and the page pads `space-6`.
- Controls are 32px (`control-md`), 28px in toolbars (`control-sm`), and 40px for the palette input (`control-lg`). Table rows are 44px (`row-height`), or 36px compact.
- **The shell** is shadcn's inset sidebar. The 240px sidebar holds the tenant switcher, the current app's sections, the other enabled apps, Access, and the user at the bottom. The main panel is inset by 8px with `radius-xl`. Its 52px header holds the sidebar toggle, breadcrumbs, the scope pill, the ⌘K search and notifications.
- **A list page**: title and description on the left with Export and the primary action on the right, segmented `Tabs` for saved views, a one-line toolbar (search, facets, chips, View), the table, and the floating `BulkBar` while rows are selected. A row opens a floating `Sheet` over the right of the page.
- **A record page**: title with status, line `Tabs` for sections, then cards in a main column with a 340px side column.
- **Tables lead with what people scan for.** For money records that is the amount and the status, then who it is for. The identifier sits on the second line.

### Shape, borders and elevation

- `radius-lg` (8px) is the base: cards, tables, popovers, alerts. `radius-md` (6px) for buttons, inputs, menu items and tab triggers. `radius-sm` (4px) for checkboxes, kbd and code. `radius-xl` (12px) for dialogs, the palette, the sheet, the bulk bar and the inset panel. Badges and switches are pills.
- `shadow-xs` on inputs and outline buttons; `shadow-sm` on the active tab trigger; `shadow-md` on menus and tooltips; `shadow-lg` on sheets, toasts and the bulk bar; `shadow-xl` on dialogs and the palette. Overlays blur what is behind by 2px.
- No gradients, no tinted cards, no coloured side stripes, no zebra rows.

### Motion

- `duration-fast` (120ms) for hover, press and switches; `duration-base` (180ms) for menus, tooltips and toasts; `duration-slow` (260ms) for sheets, dialogs and the palette. Things enter on `ease-out`. Data never animates in, and reduced motion turns it all off.

## UX principles

1. **Keyboard first.** ⌘K / Ctrl K opens the command palette from anywhere: records by id (`INV-…`, `cus_…`, a tenant code), app sections, actions. `/` focuses the list search. In tables, j/k or ↓/↑ move, Enter opens, x selects. Esc closes the top layer. Every shortcut is shown next to the action it duplicates (`Kbd` in buttons, tooltips and menu items).
2. **Peek, don't navigate.** A row opens a `Sheet` beside the list. Filters, scroll and selection stay where they were, and j/k step through the records inside the sheet. The sheet has its own URL.
3. **Undo over confirm.** Reversible actions (enable an app, archive, remove a filter) apply at once and show a toast with Undo. Only actions that cannot be undone (void, revoke, erase, disable an app) open a `Dialog`.
4. **Confirmations show the impact.** The dialog names the action and the object, lists real counts of what stops, what is kept and who is affected, and asks for the tenant code or record id when the action crosses a tenant or erases data.
5. **Optimistic switches.** A `Switch` moves at once and shows `busy` while it saves. On failure it flips back and a toast says why.
6. **Never a dead end.** Empty lists say why and what fills them. "No results" names the filters to remove. Errors say what failed and offer Retry. Loading keeps the layout with skeleton rows, shown only after 150ms.
7. **Show unsaved state.** Forms that edit many things (roles, settings) show a floating bar with the count of changes, Discard and Save (⌘S), instead of a Save button the person has to scroll to.
8. **Copy what people paste.** Every identifier is a `CopyId`: one click copies it and a check confirms.
9. **State lives in the URL.** Tabs, filters, sort, page size and the open sheet are in the URL, so any view can be shared or reloaded.

## Iconography

- Lucide (shadcn's icon set), embedded in `Icon`: 24px grid, 2px stroke, `currentColor`. Sizes are 14 in small buttons, 16 by default, 18 in large tiles.
- Icons in the sidebar, menus and inputs are `muted-foreground`, and turn `foreground` when active.
- Icons accompany words. Icon-only buttons carry an `aria-label` and a `Tooltip`. No emoji.
- There is no logo yet. The switcher shows the tenant's initial on `primary`, or the shield at platform scope.

## Using this system

- The components are `window.Suite`, for React 18: `Button`, `Field`, `Checkbox`, `Switch`, `StatusBadge`, `AppMark`, `Money`, `CopyId`, `Kbd`, `Tabs`, `Card`, `Stat`, `Alert`, `FilterChip`, `FacetedFilter`, `DataTable`, `BulkBar`, `DropdownMenu`, `Tooltip`, `Dialog`, `Sheet`, `Toaster` with `useToasts`, `CommandPalette` with `useHotkey`, `Skeleton`, `EmptyState` and `Icon`. Their `sc-*` classes in `components/bundle.css` are the same API for server-rendered pages.
- The shell and page parts are CSS only: `.sc-shell`, `.sc-sidebar`, `.sc-switcher`, `.sc-navgroup`, `.sc-navitem`, `.sc-inset`, `.sc-header`, `.sc-crumbs`, `.sc-searchbtn`, `.sc-page`, `.sc-pagehead`, `.sc-title`, `.sc-toolbar`, `.sc-dl`, `.sc-row`, `.sc-timeline`, `.sc-impact` and `.sc-code`. The four samples show them put together: Invoices, Command palette, Tenant apps and Role permissions.
- Render money from the API with `Money`: it is an integer in minor units with a currency code. Render list filters with `FacetedFilter` and `FilterChip`, following the list query contract.
- Porting to a real shadcn codebase: the color token names match shadcn's CSS variables one to one, so they can go into `globals.css` under `:root` and `.dark`. Add the status, brand, selection and scope tokens beside them.
