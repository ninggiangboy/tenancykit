# contracts/booking

The documents the server and every client of booking share. They are documents, not code: the server owns the
implementations and these files are what a Tenant System or a future UI reads to agree with it.

## `recurrence-vectors/`

One JSON file per theme, each validated by [vector.schema.json](recurrence-vectors/vector.schema.json), which a test
skips when it globs `*.json`.

A vector is one RFC 5545 expansion:

| Member | Meaning |
|---|---|
| `name` | What the case proves, in one line. |
| `note` | The arithmetic: the zone's offset on each side of a transition and why each instant is what it is. |
| `zone` | The IANA zone the rule expands in — the resource's zone, never the caller's. |
| `dtstart` | The first local start, wall-clock, without an offset. |
| `rule` | iCalendar content lines: `RRULE` first, then any `EXDATE`/`RDATE`. |
| `from`, `to` | The expansion window as UTC instants: inclusive `from`, exclusive `to`. |
| `expect` | The expanded start instants in UTC, ascending. |

The expectations are computed from the tz database and the RFC 5545 text by hand, never copied from the recurrence
library. A library that disagrees with a vector is the subject of the test, so the vector wins first and the library
second; a vector found wrong is corrected here with its reasoning, not quietly relaxed.

Covered: weekly `BYDAY`, monthly nth weekday (`BYDAY=2TU`), monthly `BYSETPOS=-1`, `EXDATE` and `RDATE`, `UNTIL` and
`COUNT`, a start inside a spring-forward gap, a start inside a fall-back overlap, three DST zones of different
shapes (`America/New_York`, `Europe/London`, the 30-minute `Australia/Lord_Howe`), one zone with no DST, and a
capacity session series.

The canonical state machine, the settings and their defaults, the engines' promises and the Tenant recipe live in
[docs/booking.md](../../docs/booking.md). The HTTP contract is [../openapi/booking.yaml](../openapi/booking.yaml) and
the webhook payloads are [../events/booking/](../events/booking/).
