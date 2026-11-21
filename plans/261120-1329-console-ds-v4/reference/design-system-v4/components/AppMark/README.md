# AppMark

The identity of one suite app: its Lucide glyph on its accent colour, optionally with its name. The tile carries its own `data-app`, so it shows its app's colour wherever it sits.

| app | glyph | accent |
|---|---|---|
| Platform | building | `app-platform` |
| Billing | receipt | `app-billing` |
| Mailroom | mail | `app-mailroom` |
| Notification | bell | `app-notification` |
| Survey | clipboard | `app-survey` |
| Attribution | pointer | `app-attribution` |
| Booking | calendar | `app-booking` |
| Customer | contact | `app-customer` |
| Loyalty | gift | `app-loyalty` |
| Workflow | workflow | `app-workflow` |
| Helpdesk | life-buoy | `app-helpdesk` |

## The consumer provides

- `app`; `withName`; `size`: `sm` 20px in the sidebar, menus, the palette and the phone top bar, `md` 26px, `lg` 40px on launcher cards and entitlement rows.
- `tone: 'neutral'` for a monochrome tile where many marks would compete (a long permission list, a dense audit log).

## UX rules

- The AppMark is the only place an app's colour appears outside that app.
- A new app picks a glyph no other app uses and a hue at least 25° from the others (see Colour in the brand book), and adds both to `AppMark.apps` and tokens.json.
