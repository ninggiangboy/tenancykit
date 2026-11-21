# Acceptance criteria

A screen, a component or a shell change is done when every line below that applies to it holds. Each line can be checked by reading the code, measuring the rendered page, or running a test. "Looks fine" is not a check.

The criteria apply in **light and dark**, in **comfortable and compact**, and at **1280×800 (desktop), 900×800 (tablet) and 390×844 (phone)**: twelve combinations per screen. The screenshot suite captures all twelve for every screen.

## 1. Tokens and colour

- **T1.** No colour literal in component or screen code: no `#hex`, `rgb()`, `oklch()` or Tailwind palette class (`bg-violet-600`, `text-zinc-500`). Colours come from tokens (`bg-primary`, `text-muted-foreground`, `var(--app-accent)`). A lint rule or a grep over `src/` for those patterns returns nothing outside the generated `tokens.css`.
- **T2.** `tokens.css` is generated from `tokens.json` and never edited by hand; the token test passes.
- **T3.** Inside an app, `document.querySelector('[data-app]')` is the shell root and its value is the open app's code. On Home it is absent.
- **T4.** Inside an app, the computed `background-color` of the primary button equals the computed value of `--app-<code>`; outside an app it equals the ink accent.
- **T5.** Only these take the app accent: the primary button, checked checkbox and switch, the active nav item, links, the focus ring, selected and active rows, the active saved-view tile, the AppMark tile, the floating action button. No card, header, sidebar background or table header is tinted.
- **T6.** Text contrast is 4.5:1 or more and large text (24px and up) 3:1 or more, in both themes. The focus ring and icons that carry meaning are 3:1 or more against their ground. axe-core reports no `color-contrast` violation on any screen.

## 2. Type and copy

- **C1.** One `h1` per page, in `text-title` (24/30, 700; 22/28 on phones).
- **C2.** Identifiers render in Inter with tabular figures. Geist Mono appears only on permission codes, scopes, keys and request syntax.
- **C3.** Every amount shows its currency code; in tables and cards the code is the quieter second part (`.sc-amount__ccy` or `muted-foreground`). Amounts are right-aligned in tables.
- **C4.** Every date column header ends in "(UTC)" and every cell is `YYYY-MM-DD HH:mm`. Card lists may shorten the date to `MM-DD` or `DD Mon`.
- **C5.** No raw message key on screen. A test walks every `t('…')` key used in `src/` and finds it in both the English and Vietnamese catalogues.
- **C6.** Sentence case for titles, buttons, tabs and labels. Buttons are a verb and an object.
- **C7.** Error copy fits the action: a failed read offers Retry and does not say "Nothing changed"; a missing permission names the permission.
- **C8.** Breadcrumbs never repeat a segment ("Platform › Platform" fails).

## 3. Layout and density

- **L1.** Comfortable: buttons and inputs are 36px tall, toolbar controls 32px, table rows 52px. Compact: 32px, 28px, 40px. Measured with `getBoundingClientRect().height`.
- **L2.** The density choice lives in the user menu, persists across reloads for that person, and is applied as `data-density` on the root.
- **L3.** The page pads 32px (comfortable), 24px (compact) or 16px (phone) on each side.
- **L4.** A list page has, in order: page head (title, description, Export if any, the primary action), saved-view tiles when the list has views, the toolbar, the table with a paging footer ("1–25 of 128", Previous, Next).
- **L5.** A table has a checkbox column only when the list has bulk actions. No column shows a full UUID; UUIDs are in the sheet as `CopyId`.
- **L6.** Every sidebar nav item has a Lucide icon. The current app appears once (its AppMark and name), not as both a header and an item.
- **L7.** Home shows one launcher card per enabled app, each with its AppMark, purpose, description and at least one link.

## 4. Status and actions

- **S1.** Every status is a `StatusBadge` from the enum value; the tone follows the table in the StatusBadge guidelines. No hand-made badges.
- **S2.** A status pill always shows its word; the icon is decoration.
- **S3.** One primary button per view (page head, sheet, dialog).
- **S4.** A destructive action outside a confirmation dialog is a `danger` (red text) button or a red menu item, never a filled red button. The filled `destructive` button appears only as the confirming button of a dialog.
- **S5.** A setting with a switch shows no On/Off badge beside it.

## 5. Sheet and dialog

- **D1.** A row click or Enter opens the sheet; its URL changes; Back, Esc, the close button and the scrim close it, and focus returns to the row.
- **D2.** The side sheet is 460px wide with a scrim; it never cuts table columns that stay interactive behind it.
- **D3.** The sheet head shows the eyebrow (record type and number), the title (the key figure for money records, else the name), the status and key date, then the actions. Section titles are `text-heading`.
- **D4.** Dialogs are 520px wide (640px for two-column forms); inputs inside them follow density.
- **D5.** Date and time inputs show and accept `YYYY-MM-DD HH:mm` in UTC, never the browser's locale format.

## 6. Phone and tablet

- **M1.** At 390px no screen scrolls sideways: `document.documentElement.scrollWidth <= window.innerWidth`. Pills and the toolbar may scroll inside themselves.
- **M2.** Every tap target is at least 44×44px: buttons, nav items, inputs, card rows, the sheet's close button. axe-core reports no `target-size` violation.
- **M3.** The sidebar is a drawer opened from the top bar's menu button; a scrim and Esc close it.
- **M4.** Tables render as the card list: the `primary` and `badge` on the first line, the `secondary` values joined by " · " and the `meta` on the second. Every column of a list declares a `mobile` role.
- **M5.** Sheets and dialogs are bottom sheets at 88% height with a grab handle. Dragging the handle or head past 25% of the height, or a flick, closes them. The main action is docked at the bottom.
- **M6.** The page's primary action is the floating button; the page head does not repeat it.
- **M7.** Every action that exists on desktop is reachable on a phone (a desktop-only hover action is a failure). The phone end-to-end tests cover the main flows of each screen.
- **M8.** Inputs are at least 16px on phones.
- **M9.** Tablet (900px): the icon rail is 56px with tooltips on its items; only columns marked `hidden` leave the table.

## 7. States

- **E1.** Loading keeps the layout with skeleton rows, shown after 150ms.
- **E2.** An empty list says why and what fills it, with the action that fills it when the person may take it.
- **E3.** "No results" names the filters to remove.
- **E4.** An expected "not set up yet" answer (a 404 that means "nothing configured") renders as an empty state, not an error, and does not log a console error.
- **E5.** No console error and no unhandled HTTP error on any screen in the screenshot suite.

## 8. How to verify

1. Run the unit tests, the token test, typecheck and lint.
2. Run the screenshot suite: every screen in the twelve combinations above, failing on any console error, any unhandled HTTP status, and any horizontal scroll at 390px.
3. Run axe-core on every screen in light and dark, at desktop and phone width, with `color-contrast` and `target-size` enabled.
4. Compare each screen against the samples in this system (Invoices, Invoices on a phone, Invoice peek on a phone, Home, Density). A screen that differs in structure from its closest sample needs a reason in its pull request.
