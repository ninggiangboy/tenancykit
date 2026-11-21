# Field

A label, one control and one line of hint or error, stacked with an 8px gap. Inputs follow density (36px comfortable, 32px compact, 44px on phones) with a hairline `input` border and `shadow-xs`, as in shadcn; focus turns the border `ring` (the app accent inside an app) and adds the halo.

## The consumer provides

- `label` (required; `hideLabel` keeps it for screen readers only). Units go in the label: "Amount (minor units)".
- `hint`: what the value is for, one sentence. `error`: what is wrong and how to fix it. The error replaces the hint and is announced.
- `type`: input types, `select` with `options`, or `textarea`.
- `icon` for a leading search icon; `prefix` / `suffix` for fixed parts (a domain, an HTTP method).
- `mono` only for codes typed exactly (a permission code, a key). `optional` marks the rare optional field.

## UX rules

- Validate on blur and on submit, never on each keystroke. Keep what the person typed when an error shows.
- Dates and times are typed and shown as `YYYY-MM-DD HH:mm` in UTC, with "(UTC)" in the label. Do not use the browser's `datetime-local` control: it shows the viewer's locale.
- A select with nothing chosen shows its purpose ("Choose a charge"), not "Select…".
- Inputs are 16px on phones so the browser does not zoom.
- Read-only values use `readOnly` on `muted`, so they can still be selected and copied; prefer `CopyId` for identifiers.
