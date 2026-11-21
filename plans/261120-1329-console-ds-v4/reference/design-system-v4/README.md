Suite Console is the one web console of the suite. Platform operators use it at **platform scope** to run tenants, users, roles, entitlements and credentials for everyone. A tenant's staff use it at **tenant scope** (`<tenant-code>.<base-domain>`) to work in the apps their tenant has enabled: Billing, Customer, Mailroom, Survey, Attribution, Booking, Loyalty, Workflow, Helpdesk and Notification. They use it at a desk and on a phone, and both must do everything.

The system follows shadcn/ui: the same token roles (`background`/`foreground` pairs, `muted`, `accent`, `border`, `input`, `ring`, `sidebar-*`, `chart-*`), oklch values, Tailwind's spacing and shadow ramps, and the same component anatomy. It adds what an operations console needs and shadcn leaves out: app identity, status colours, platform scope, money in minor units, two densities, phone layouts, keyboard-first navigation and record peek.

This is **version 4**. The two other sections are part of the system and every screen follows them: **Acceptance criteria** (what a screen must pass) and **Porting to @suite/ui** (how the tokens and components reach the React code).

## What changed in v4

v3 was monochrome first: zinc carried everything, apps were told apart only by a glyph and status was a 6px dot. v4 keeps the neutral frame and adds colour where it helps people find their place and read state at a glance.

- **One accent per app.** Each app has a colour, `app-<code>`, and a faint fill, `app-<code>-soft`. Inside an app, the primary button, the active nav item, links, focus, the selected row and the active saved view take that colour. The shell frame stays neutral. Outside any app (Home, the command palette, sign-in) the accent is ink, zinc-900 in light and zinc-50 in dark.
- **Status is a tinted pill with an icon and a word**: `success`, `warning`, `destructive`, `info` on their `-soft` grounds, and `neutral-soft` for the quiet states. The word always carries the meaning.
- **Two densities.** Comfortable is the default (52px rows, 36px controls). Compact is a person's choice (40px rows, 32px controls). It lives in the user menu.
- **Phones and tablets.** Below 768px the console is a phone app: a top bar, the nav in a drawer, saved views as pills, tables as card lists, a floating main action and bottom sheets. From 768px to 1023px the sidebar is an icon rail. From 1024px it is the full desktop.
- **Saved views as tiles.** A list's views (All, Open, Past due…) show their counts as tiles above the table.
- **The sheet leads with the record's key figure** (the amount of an invoice) and puts its actions under it. Dangerous actions are red text, never a red block, until the confirmation dialog.
- **Softer shape, bolder titles.** 8px controls, 10px containers, 14px overlays. Titles are 24px bold.
- **The platform scope line is gone.** The "Platform scope" pill in the header remains.

## Content fundamentals

- **Name things the way the person sees them.** "Rotate secret", "Apps", "Created". Field names and enum values appear only where people copy them: in mono, in tooltips, in permission codes.
- **Sentence case everywhere**: titles, buttons, tabs, labels. Enum values (`PAST_DUE`) are shown humanized ("Past due") by `StatusBadge`.
- **Buttons are a verb and an object**: "Void invoice", "Disable Mailroom". A confirming button repeats that verb, never "OK".
- **Say what happened and what to do, with real names and dates.** "Rotate before 23 Nov 2026 or Acme's Tenant System stops getting tokens." No apologies, no exclamation marks, no emoji.
- **Errors fit the action.** A failed read says what could not be loaded and offers Retry ("Could not load invoices. Retry"). Only a failed write says that nothing changed. A missing permission names it ("You need billing:invoice:read to see invoices").
- **Toasts are past tense**: "Loyalty enabled for acme". Alerts are present facts: "Secret expires in 6 days".
- **Numbers carry units.** Money always shows its currency code (`12,500.00 USD`, `3,200,000 VND`); in tables the code is a quieter second part. Times are UTC, and the column header says so ("Created (UTC)").
- **Every visible string comes from the message catalogue** in English and Vietnamese. A raw key such as `shell.open` on screen is a bug.

## Visual foundations

### Colour

- The sidebar frame is `sidebar`, the inset main panel is `background`, cards and tables are `card`, and everything that floats is `popover`.
- Text is `foreground`. Secondary text is `muted-foreground` (5:1 or more on every surface). Quiet fills are `muted`, and hover is `accent`.
- `border` draws hairlines. Control edges use `input` (about 1.5:1, as in shadcn); controls also carry `shadow-xs`, and focus adds a solid 2px `ring` and a soft halo.
- **The app accent.** An element with `data-app="<code>"` sets `app-accent` and `app-accent-soft` to that app's pair, and with them `primary`, `primary-foreground`, `selection`, `ring` and links. The shell puts `data-app` on its root for the app whose route is open, so the sidebar, the page and its overlays all follow. `AppMark` carries its own `data-app`, so an app's tile keeps its colour anywhere.
- **What takes the accent**: the one primary button per view, checked checkboxes and switches, the active nav item (text and icon in `app-accent` on `app-accent-soft`), links, the focus ring, the selected and active rows (`app-accent-soft`), the active saved-view tile (a 2px `app-accent` ring), the AppMark tile and the floating action button. **Nothing else** is coloured by the app: no tinted cards, no coloured headers, no gradients.
- **Status** colours a `StatusBadge` pill, an `Alert` or toast icon, a delta's arrow and danger text. Apps never use status colours to mean anything, and a status always shows its word.
- **Scope.** At platform scope, the header shows a "Platform scope" pill with a `scope-platform` dot, and the switcher shows the shield.
- **Charts.** `chart-1` is the measure the chart is about; `chart-2` to `chart-4` are further series; `chart-5` is the previous period or a target.

| App | Accent | Glyph |
|---|---|---|
| Platform | `app-platform` (blue) | building |
| Billing | `app-billing` (violet) | receipt |
| Customer | `app-customer` (teal) | contact |
| Mailroom | `app-mailroom` (cyan) | mail |
| Notification | `app-notification` (magenta) | bell |
| Survey | `app-survey` (pink) | clipboard |
| Loyalty | `app-loyalty` (orange) | gift |
| Booking | `app-booking` (green) | calendar |
| Attribution | `app-attribution` (olive) | pointer |
| Workflow | `app-workflow` (indigo) | workflow |
| Helpdesk | `app-helpdesk` (rose) | life-buoy |

A new app picks a hue at least 25° from every other app, sets its light value so white on it is 5.5:1 or more and its dark value so ink on it is 7:1 or more, and adds both pairs to tokens.json and the `data-app` block.

### Type

- One family: Inter (`--font-sans`) at 14px for UI. Titles and figures are bold and tight: `text-title` 24/30 at 700 (22/28 on phones), `text-metric` 28/34 at 700 for the key figure of a record, `text-figure` 20/26 at 600 for saved-view counts.
- Card, sheet-section and dialog titles are `text-heading` (15/22, 600). Descriptions and hints are `text-small` (13/18, `muted-foreground`). Badges, kbd and column headers use `text-caption`.
- Identifiers (`INV-2026-00412`, `01a1f40e-…`) stay in Inter with tabular figures. Geist Mono (`text-code`) is only for permission codes, scopes, keys and request syntax.
- Amounts are `.sc-amount` (600, tabular) with the currency code after them in `muted-foreground`.

### Spacing, size and layout

- Tailwind's 4px steps. Cards pad `space-5`. The page pads `space-8` (comfortable), `space-6` (compact) or `space-4` (phone).
- **Density** (`data-density` on the root, set by the shell from the person's choice): comfortable has 36px controls, 32px toolbar controls and 52px rows. Compact has 32px, 28px and 40px. On phones every tapped thing is at least `touch-target` (44px) in either density.
- **The shell** is shadcn's inset sidebar. The 240px sidebar holds the tenant switcher, the current app (its AppMark and name) with its sections, each item with a Lucide icon, then the other enabled apps, and the user at the bottom. The main panel is inset by 8px with `radius-xl`. Its 52px header holds the sidebar toggle, the breadcrumbs (app, then page, never repeated), the scope pill, the ⌘K search and the user menu (density, theme, sign out).
- **Home** is a launcher: a greeting, then one card per enabled app with its AppMark, a one-line purpose, a sentence and links to its common screens. The shell stays neutral there.
- **A list page**: title and description on the left with Export and the primary action on the right, saved views as tiles with counts, a one-line toolbar (search, facets, chips, View), the table with a footer for paging, and the floating `BulkBar` while rows are selected. A row opens a `Sheet` over the right of the page, with a scrim.
- **Tables lead with what people scan for.** For money records that is the amount and the status, then the record number and who it is for, then the date. Long identifiers (UUIDs) do not get a column; they live in the sheet as a `CopyId`. A checkbox column appears only when the list has bulk actions.

### Responsive

| Width | Name | Layout |
|---|---|---|
| below 768px (`bp-tablet`) | phone | Top bar (menu, the app's mark and the page title, search, avatar). The nav is a 300px drawer with a scrim. Saved views are pills that scroll sideways. The toolbar is the search field; facets open in a bottom sheet. Tables are card lists. The main action is a floating button bottom right. Sheets and dialogs are bottom sheets. |
| 768px to 1023px | tablet | 56px icon rail with tooltips. Tables keep their columns except those marked `hidden`. The sheet is a side sheet. |
| 1024px and up (`bp-desktop`) | desktop | The full sidebar and every column. |

- **The card list** shows each record in two lines: the `primary` column (an amount, a name) with the `badge` column on the right, then the `secondary` columns joined by " · " with the `meta` column (a short date) on the right. Rows are at least 64px. A screen declares these roles on its columns; it never builds its own card.
- **The bottom sheet** rises to 88% of the screen with a grab handle. Dragging the handle or the head down by a quarter of its height, or flicking it, closes it, as do the scrim, the close button and the browser's Back. The main action docks at the bottom; the rest sit in a "…" menu.
- **Bulk actions on a phone**: "Select" in the toolbar turns on selection; tapping a card toggles it, and the `BulkBar` docks at the bottom.
- **Forms** lay out in one column on phones; the confirming button is full width at the bottom. Inputs are 16px there so the browser does not zoom.
- **Hover does not exist on touch screens**: row actions and copy icons are always visible there.

### Shape, borders and elevation

- `radius-md` (8px) for buttons, inputs, menu items, tab triggers and status pills. `radius-lg` (10px) for cards, tables, popovers, alerts and saved-view tiles. `radius-xl` (14px) for dialogs, the palette, the side sheet, the bulk bar and the inset panel. `radius-2xl` (20px) for the top of the bottom sheet and the floating action button. Switches, avatars and filter chips are pills.
- `shadow-xs` on inputs and outline buttons; `shadow-sm` on the active segmented tab; `shadow-md` on menus and tooltips; `shadow-lg` on sheets, toasts, the bulk bar and the floating button; `shadow-xl` on dialogs, the palette and the nav drawer. Overlays blur what is behind by 2px.
- No gradients, no tinted cards, no coloured side stripes, no zebra rows.

### Motion

- `duration-fast` (120ms) for hover, press and switches; `duration-base` (180ms) for menus, tooltips and toasts; `duration-slow` (260ms) for sheets, dialogs, the drawer and the palette. Things enter on `ease-out`. Data never animates in, and reduced motion turns it all off. A sheet follows the finger while dragged.

## UX principles

1. **Keyboard first, touch equal.** ⌘K / Ctrl K opens the command palette from anywhere: records by id, app sections, actions. `/` focuses the list search. In tables, j/k or ↓/↑ move, Enter opens, x selects. Esc closes the top layer. Every shortcut is shown next to the action it duplicates. On a phone every one of these actions has a visible control: the search button, the "Select" button, the sheet's close button.
2. **Peek, don't navigate.** A row opens a `Sheet` beside the list (a bottom sheet on phones). Filters, scroll and selection stay where they were, and j/k step through the records inside the sheet. The sheet has its own URL, so Back closes it.
3. **Undo over confirm.** Reversible actions (enable an app, archive, remove a filter) apply at once and show a toast with Undo. Only actions that cannot be undone (void, revoke, erase, disable an app) open a `Dialog`.
4. **Confirmations show the impact.** The dialog names the action and the object, lists real counts of what stops, what is kept and who is affected, and asks for the tenant code or record id when the action crosses a tenant or erases data.
5. **Optimistic switches.** A `Switch` moves at once and shows `busy` while it saves. On failure it flips back and a toast says why. A switch never has an On/Off badge beside it; the switch is the state.
6. **Never a dead end.** Empty lists say why and what fills them. "No results" names the filters to remove. Errors say what failed and offer Retry. Loading keeps the layout with skeleton rows, shown only after 150ms. A screen the person may not open is not in the nav, and its URL explains which permission is missing.
7. **Show unsaved state.** Forms that edit many things (roles, settings) show a floating bar with the count of changes, Discard and Save (⌘S).
8. **Copy what people paste.** Every identifier is a `CopyId`: one click copies it and a check confirms.
9. **State lives in the URL.** Saved view, filters, sort, page and the open sheet are in the URL, so any view can be shared or reloaded. Density and theme are the person's, kept in their browser.

## Iconography

- Lucide (shadcn's icon set), embedded in `Icon`: 24px grid, 2px stroke, `currentColor`. Sizes are 14 in small buttons, 16 by default, 18 in large tiles, 20 in the phone top bar.
- Every nav item has an icon. Icons in the sidebar, menus and inputs are `muted-foreground`; the active nav item's icon is `app-accent`.
- Icons accompany words. Icon-only buttons carry an `aria-label` and a `Tooltip`. No emoji.
- There is no logo yet. The switcher shows the tenant's initial on `foreground`, or the shield at platform scope.

## Using this system

- The components are `window.Suite`, for React 18: `Button`, `Field`, `Checkbox`, `Switch`, `StatusBadge`, `AppMark`, `Money`, `CopyId`, `Kbd`, `Tabs`, `Card`, `Stat`, `Alert`, `FilterChip`, `FacetedFilter`, `DataTable`, `BulkBar`, `DropdownMenu`, `Tooltip`, `Dialog`, `Sheet`, `Toaster` with `useToasts`, `CommandPalette` with `useHotkey`, `useMedia`, `Skeleton`, `EmptyState` and `Icon`. Their `sc-*` classes in `components/bundle.css` are the same API for server-rendered pages.
- The shell and page parts are CSS only: `.sc-shell` (with `data-app`, `data-density`, `data-drawer`), `.sc-sidebar`, `.sc-switcher`, `.sc-navgroup`, `.sc-navitem` (text in `.sc-navitem__text`), `.sc-inset`, `.sc-header`, `.sc-topbar`, `.sc-crumbs`, `.sc-searchbtn`, `.sc-page`, `.sc-pagehead`, `.sc-title`, `.sc-toolbar`, `.sc-fab`, `.sc-launcher`, `.sc-appcard`, `.sc-dl`, `.sc-lines`, `.sc-amount`, `.sc-row`, `.sc-timeline`, `.sc-impact` and `.sc-code`.
- The samples show them put together: Invoices (desktop), Invoices on a phone, an invoice peek on a phone, Home, Density, plus the v3 Command palette, Tenant apps and Role permissions screens, which now render in v4's styles.
- Render money from the API with `Money` inside `.sc-amount`: it is an integer in minor units with a currency code. Render list filters with `FacetedFilter` and `FilterChip`, following the list query contract.
