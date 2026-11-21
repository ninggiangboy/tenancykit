# Sheet

A panel to read or act on one record without leaving the list (the "peek"). A side sheet on tablets and desktops; a bottom sheet on phones. The list stays behind it with its filters, scroll and selection.

## The consumer provides

- `open`, `onClose`, `title`, `eyebrow` (the record type and its number as a `CopyId`), `description` (the `StatusBadge` and the key date), `children` (sections with `.sc-section-title`).
- `figure: true` when the title is the record's key figure (an invoice's amount as `Money`).
- `actions`: the primary action, then up to one outline action, then a "…" `DropdownMenu` holding the rest, destructive items last and red. Shown under the title on wide screens and docked at the bottom of a bottom sheet.
- `onPrev` / `onNext` to step through the list (j/k); `placement: 'auto' | 'side' | 'bottom'` (auto picks bottom below 768px).

## UX rules

- Open from a row click or Enter; the row stays marked active. Esc, the scrim, the close button and the browser's Back close it, and focus returns to the row.
- The sheet's URL is the record's URL, so a peek can be shared and reloaded.
- Widths: `sheet-width` (460px) beside the list with a scrim; `sheet-height-phone` (88%) from the bottom on phones.
- The bottom sheet follows the finger when its grab handle or head is dragged; past 25% of its height, or flicked, it closes. Its body scrolls on its own and never starts a drag.
- Empty sections say so in one line with what fills them ("No payments yet. Record one when the customer pays by bank transfer.").
