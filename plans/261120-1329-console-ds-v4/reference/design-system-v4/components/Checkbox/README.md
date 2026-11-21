# Checkbox

Chooses any number of items: permissions in a role, rows in a table, values in a facet. Checked fills with `primary`, as in shadcn.

## The consumer provides

- `label` and an optional `description` (the permission code, a consequence).
- `checked` / `onChange` or `defaultChecked`; `indeterminate` for a parent whose children are partly checked.

## UX rules

- The whole row (label and description) is the hit area.
- A group header checkbox is indeterminate when some children are checked, and checks all on click.
- For a setting that applies at once, use `Switch`.
