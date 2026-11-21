# BulkBar

A floating bar that appears when rows are selected, with the actions that apply to all of them. It floats above the table on wide screens and docks at the bottom on phones.

## The consumer provides

- `count`, `onClear`, and `children`: two to four ghost buttons.

## UX rules

- It floats, so selecting never shifts the layout.
- Bulk actions that change money or access still open a confirmation that names the count ("Write off 3 invoices").
- Esc clears the selection. On phones, selection starts from a "Select" button in the toolbar; tapping a card then toggles it.
