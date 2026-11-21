# CopyId

An identifier that copies itself. The copy icon slides in on hover or focus and turns into a green check for 1.4s after copying. Identifiers render in Inter with tabular figures; pass `mono` only for keys and secrets.

## The consumer provides

- `value` (and `children` if the visible text differs, for example a shortened id). `onCopy` to show a toast.

## UX rules

- Use it for every identifier a person will paste elsewhere: invoice numbers, customer ids, client ids, webhook ids.
- In a table row, clicking a CopyId copies and does not open the row.
