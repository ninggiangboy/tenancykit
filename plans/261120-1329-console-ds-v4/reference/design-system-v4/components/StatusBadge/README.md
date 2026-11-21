# StatusBadge

The state of one record: an icon and a word in a pill tinted by its tone. The word carries the meaning, so it reads without colour; the tint lets a column of statuses be scanned at a glance.

## The consumer provides

- `status`: the enum value from the API (`PAID`, `PAST_DUE`). The badge picks the tone and the icon and writes the word in sentence case; the raw value is the tooltip.
- Or `tone` plus `children` for a state the table below does not list.
- `variant: 'plain'` drops the pill and the icon and shows muted text, for tags such as a source or a cycle.

| tone | pill | icon | statuses |
|---|---|---|---|
| `neutral` | `neutral-soft`, `muted-foreground` text | none | DRAFT, VOID, CANCELED, ENDED, DISABLED, EXPIRED, REFUNDED |
| `info` | `info-soft`, `info` text | clock | OPEN, TRIALING, RUNNING |
| `success` | `success-soft`, `success` text | check | PAID, ACTIVE, SUCCEEDED, ENABLED, DELIVERED, COMPLETED |
| `warning` | `warning-soft`, `warning` text | alert | PAST_DUE, PENDING, SUSPENDED, RETRYING, WAITING |
| `danger` | `destructive-soft`, `destructive` text | x-circle | UNCOLLECTIBLE, FAILED, REVOKED, DEAD |

## UX rules

- One badge per record. Secondary facts go in the cell's second line.
- Never colour a status by the app accent, and never build a badge by hand: add the enum to the table instead.
- A setting shown by a `Switch` has no badge beside it.
