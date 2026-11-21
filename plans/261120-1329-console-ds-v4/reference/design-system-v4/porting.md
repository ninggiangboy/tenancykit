# Porting to @suite/ui

How v4 reaches the console's code (`frontend/`: React 19, TypeScript, Tailwind 4, Radix primitives, Module Federation). This bundle (`window.Suite`, React 18) is the reference; it is not shipped. Port behaviour and classes, not the file.

## Rules that are easy to get wrong

1. **Never compute a colour.** Every colour is a token. If a design needs a colour no token has, add the token here first, then sync.
2. **Re-declare the accent aliases inside the app scope.** `tokens.css` resolves `primary`, `selection` and the others to literal values on `:root` and `.dark`. Setting `--app-accent` on an element does not change `--primary` below it. The app-scope block must set `--primary`, `--primary-foreground`, `--selection`, `--ring`, `--ring-halo`, `--sidebar-ring` and `--link` itself, as `components/bundle.css` does at its top.
3. **The shell owns the CSS variables.** Remotes bundle `@suite/ui` separately, but tokens, the app-scope block and the density block are in the shell's stylesheet only. A remote never defines a custom property.
4. **Screens declare; the kit decides.** A screen never reads the viewport width. It gives each column a `mobile` role and each page its primary action; `DataTable`, `Sheet`, `Dialog` and the shell decide how they look on each device.
5. **No new gesture library.** The bottom sheet's drag is a small pointer-events hook on the existing Radix Dialog (see `useDragDismiss` in `components/bundle.js`). vaul is unmaintained.

## Sync procedure

1. Copy `tokens.json` from this system to `packages/ui/design/tokens.json` and the README sections to the snapshot directory named in `docs/console.md`.
2. Extend `packages/ui/scripts/build-tokens.mjs` so it also writes, from the new families:
   - `--size-*` for every `size` token (it already does), including the `*-compact` ones and `touch-target`;
   - `--space-*` for every `spacing` token (it does not yet).
   Keep resolving colour aliases to literals, as today.
3. Run `pnpm --filter @suite/ui tokens:build`, then the token test. Extend the test's list of required names with `app-accent`, `app-accent-soft`, every `app-<code>` and `app-<code>-soft`, and the five `-soft` status tokens.
4. Add the hand-written blocks to `packages/ui/src/styles/theme.css`, after the `tokens.css` import:
   - the `[data-app]` block and one `[data-app="<code>"]` rule per app (copy them from the top of `components/bundle.css`, renaming `--control-*` and `--row-height` to the `--size-*` names the build writes);
   - the `[data-density="compact"]` block and the phone block that raises controls to `--size-touch-target`;
   - `@theme inline` entries for the new colours: `--color-app-accent`, `--color-app-accent-soft`, `--color-app-accent-foreground`, `--color-success-soft`, `--color-warning-soft`, `--color-destructive-soft`, `--color-info-soft`, `--color-neutral-soft`, `--color-link`;
   - the v4 type scale in `@theme`: `--text-title` 24px / 30px / 700, `--text-metric` 28px / 34px / 700, `--text-figure` 20px / 26px / 600 (new), the rest unchanged.
5. Tailwind's `md` (768px) and `lg` (1024px) breakpoints already equal `bp-tablet` and `bp-desktop`. Use `md:` and `lg:`; do not add custom breakpoints.
6. Update `docs/console.md`: the version (`tokens.json` `version` 4 and this system's version id), and replace "monochrome first" in the list of rules with "one accent per app".

## Where each piece goes

| v4 piece | Code |
|---|---|
| `data-app` on the shell root | `packages/shell-core/src/frame/` (the frame component that renders the sidebar and the inset panel): set it from the open remote's `appCode`; none on Home |
| `data-density` and the user menu | `packages/shell-core/src/frame/header.tsx` (menu) and the frame root (attribute); persist like the theme in `packages/shell-core/src/theme.ts` |
| Drawer, icon rail, top bar | `packages/shell-core/src/frame/sidebar.tsx`, `header.tsx`, `frame-context.ts` (it already knows the narrow viewport) |
| Launcher | `packages/shell-core/src/frame/home.tsx` |
| `AppMark` colour | `packages/ui/src/app-mark.tsx`: render `data-app={app}` on the tile; add `tone: 'color' \| 'neutral'` |
| `StatusBadge` pill and icon | `packages/ui/src/status-badge.tsx` |
| `Button` `danger` variant | `packages/ui/src/button.tsx` |
| Saved-view tiles | `packages/ui/src/tabs.tsx` (`variant: 'tiles'`) and `packages/remote-kit/src/list/resource-page.tsx` |
| `mobile` column roles, card list | `packages/ui/src/data-table.tsx` and `packages/remote-kit/src/list/list-spec.ts` (the column type gains `mobile` and `cardText`) |
| Bottom sheet, actions in the head | `packages/ui/src/sheet.tsx`, `packages/remote-kit/src/record/record-sheet.tsx` |
| Dialog as bottom sheet | `packages/ui/src/dialog.tsx`, `packages/remote-kit/src/dialogs/*` |
| UTC date-time field | `packages/remote-kit/src/forms/form-fields.tsx` |
| Floating action button | `packages/remote-kit/src/layout/page.tsx` (the page's primary action) |

## Component changes from v3

| Component | Change |
|---|---|
| `StatusBadge` | A tinted pill with an icon: info `clock`, success `check`, warning `alert`, danger `x-circle`, neutral none. `variant: 'plain'` is muted text with no pill. |
| `AppMark` | The tile is the app's accent with its glyph in `app-accent-foreground`. `tone: 'neutral'` keeps the v3 look where colour would compete. |
| `Button` | `default` follows the app accent. New `danger`: red text on transparent, `destructive-soft` on hover, for destructive actions outside a confirmation. `destructive` (filled) only confirms inside a Dialog. Heights follow density. |
| `Tabs` | New `variant: 'tiles'`: saved views with counts; pills on phones. |
| `DataTable` | Columns take `mobile: 'primary' \| 'badge' \| 'secondary' \| 'meta' \| 'hidden'` and `cardText(row)`. `layout: 'auto' \| 'table' \| 'cards'` (auto: cards below 768px). Rows follow density. The active row is `selection` with a 2px accent edge. |
| `Sheet` | `placement: 'auto' \| 'side' \| 'bottom'`, `actions` (shown under the title, docked at the bottom on phones), `figure` (the title is the key figure). Drag to close in the bottom placement. Modal with a scrim by default. |
| `Dialog` | A bottom sheet below 768px, with the same drag; buttons stack full width. |
| `CopyId` | The copy icon is always visible on touch screens. |
| `useMedia` | New hook: `useMedia('(max-width: 767.98px)')`. Only the kit and the shell call it. |
