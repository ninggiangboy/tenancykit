# Card

A titled group of related content: a settings block, a detail list, a list of rows.

## The consumer provides

- `title`, `description`, an `action` (one outline or ghost button), `children`, an optional `footer`.
- `flush` when the children are rows (`.sc-row`) divided by hairlines with no content padding.

## UX rules

- Cards group; they do not decorate. A page of one table needs no card around the table (DataTable has its own).
- Do not nest cards. Inside a card, separate with `.sc-dl`, rows or a section title.
