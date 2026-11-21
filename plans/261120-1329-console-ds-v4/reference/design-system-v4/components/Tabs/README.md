# Tabs

Three looks. `tiles` shows a list's saved views as tiles with their counts, above the table; on phones they become pills that scroll sideways. `segmented` (shadcn's muted list) switches small views inside a card. `line` switches sections of one record.

## The consumer provides

- `items`: `{id, label, count?, icon?}`; `value` / `onChange` or `defaultValue`; `label` for the tab list; `variant`.

## UX rules

- Two to seven tabs, one or two words each. Counts come from the list API with `page_info=full` (one request per view, `limit=1`, read `total`).
- The active tile has a 2px ring in the app accent and accent text; the active pill on phones is `app-accent-soft`.
- Arrow keys move between tabs. Put the tab in the URL so it can be linked.
