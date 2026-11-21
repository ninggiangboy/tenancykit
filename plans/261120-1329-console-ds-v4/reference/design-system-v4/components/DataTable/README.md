# DataTable

A list of records of one kind, after shadcn's data table, with the suite's additions: keyboard navigation, row peek, hover actions, a loading state, an empty state that says how to widen the search, two densities and a card list on phones.

## The consumer provides

- `columns`: `{key, header, align?, mono?, width?, sortable?, sort?, render?, mobile, cardText?}`. Compose `StatusBadge`, `Money` in `.sc-amount`, `AppMark` and `.sc-cell-2` in `render`.
- `mobile` on every column: `primary` (the first line, left: an amount or a name), `badge` (first line, right: the status), `secondary` (second line, joined by " · "), `meta` (second line, right: a short date), or `hidden` (sheet only; also leaves the table on tablets). `cardText(row)` gives the plain text a card shows when `render` returns a two-line cell.
- `rows`, `rowKey`; `selectable` with `selected` / `onSelect` only when the list has bulk actions (pair with `BulkBar`).
- `onRowOpen(row)` to open the record in a `Sheet`; `activeKey` follows the open row.
- `rowActions(row)`: a `DropdownMenu`, shown on hover, focus, for the active row, and always on touch screens.
- `onSort(key, dir)`, `loading` (skeleton rows keep the layout), `empty`, `footer` (paging).
- `layout`: `auto` (default: cards below 768px), `table` or `cards`.

## UX rules

- Lead with what people scan for: for money records, the amount and the status, then the number and who it is for, then the date. No column for a UUID; it lives in the sheet.
- Rows are 52px comfortable and 40px compact; card rows at least 64px. Density comes from the root, not from each table.
- Keyboard: j/k or ↓/↑ move the active row, Enter opens it, x selects it. Clicking a row opens it; clicking a control inside it does not.
- Money and numbers end-aligned with tabular figures; times as `YYYY-MM-DD HH:mm` with "(UTC)" in the header.
- Paging: "1–25 of 128" with `page_info=full`; with a cursor, Previous and Next alone. Keep page and size in the URL.
- Never show an empty table without saying why and what to change.
