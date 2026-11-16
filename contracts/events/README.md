# Suite events and webhook delivery

Event schemas are namespaced by the service that produces them:

| Folder | Producer | Delivery |
|---|---|---|
| `billing/` | Billing | AMQP (`billing.events`) between services, and signed HTTP to Tenant Systems. |
| `platform/` | Platform | AMQP (`platform.events`) only: state-transfer `*.changed` events other services replicate. |
| `mailer/` | Mailer | AMQP (`mailer.events`) only: the terminal delivery outcome (`email-delivery.changed`) a producer maps onto its own ledger. |
| `survey/` | Survey | AMQP (`survey.events`) between services, and signed HTTP to Tenant Systems: the three facts a Tenant's own system reacts to. |
| `crm/` | crm | Signed HTTP webhooks to Tenant Systems only: the customer and order facts a Tenant's own system reacts to. No AMQP exchange carries them. |
| `loyalty/` | loyalty | Signed HTTP webhooks to Tenant Systems only: the enrolment, points, tier and voucher facts a Tenant's own system reacts to. No AMQP exchange carries them. |

Within a producer folder, the `*.schema.json` files are the source of truth for which event types that service
publishes and for the shape of each event's `data`. The application packages them and rejects a subscription to a
type that has no schema. A test builds every event through the real code and validates it against its schema, so a
payload that drifts from its schema fails the build.

Between services the AMQP message body is the one canonical envelope the webhook writer emits too, described in
[messaging/README.md](../messaging/README.md): both are produced by `EventDocument`, so a receiver of either gets the
same bytes. The rest of this file describes the signed HTTP delivery to a Tenant System.

Billing reports business state changes to a Tenant System as signed HTTP webhooks, and Survey reports its own three
facts the same way with `Survey-*` headers. A webhook only reports a fact; it never asks the receiver to do anything,
and the receiver decides its own policy. The signing, destination policy, guarded DNS resolution, HTTP send and retry
schedule are the shared `core-webhook` mechanics; each app owns its endpoints, its deliveries, its tables and its
event catalog, so a delivery is never cross-app.

## The delivered document

`POST` to the endpoint URL, `Content-Type: application/json`:

This is the same canonical envelope the broker carries between services (see
[messaging/README.md](../messaging/README.md)); a consumer on AMQP and a webhook receiver get the same bytes for
one event.

```json
{
  "id": "0198c1a0-7b1e-7c3e-9b52-0d6f6f1c2a10",
  "type": "charge.succeeded",
  "aggregate": "charge/42",
  "tenantId": "0198c1a0-0000-7000-8000-000000000042",
  "occurredAt": "2026-11-10T09:30:00.123456Z",
  "schemaVersion": 2,
  "data": { "chargeId": "...", "walletId": "...", "amount": { "minorUnits": 1250, "currency": "USD" } }
}
```

`data` is validated by `<type>.schema.json`. Amounts are integers in minor units with an ISO 4217 code.
`schemaVersion` is bumped only when the envelope changes incompatibly, and a receiver must reject a `schemaVersion`
it does not understand rather than guess at the shape.

## Headers

| Header | Meaning |
|---|---|
| `Billing-Event-Id` | The event id (same as `id`). **The deduplication key.** |
| `Billing-Event-Type` | The event type. |
| `Billing-Timestamp` | Unix time in seconds when this attempt was signed. |
| `Billing-Key-Id` | Identifies which signing secret signed this delivery. |
| `Billing-Signature` | `v1=` followed by the lowercase hex HMAC-SHA256 of `timestamp + "." + rawBody`. |
| `Billing-Attempt` | 1 for the first attempt, then 2, 3, and so on. |

The signing secret is returned once, when the endpoint is created or its secret is rotated, as a string such as
`whsec_...`. It is separate from the API credential used to call Billing. The HMAC key is the **whole secret
string**, including the `whsec_` prefix, as UTF-8 bytes.

## Delivery guarantees

- **At least once.** A receiver may see the same event more than once, for example after a timeout where the
  receiver had actually processed it, or after a resend. Record the `Billing-Event-Id` values you have
  processed and ignore repeats.
- **Not ordered.** Retries mean a later event can arrive before an earlier one. Use the `occurredAt` and the
  identifiers in `data` to reconcile, and fetch current state from the REST API when order matters.
- **Success is any 2xx** answered within the read timeout (10 seconds by default). Anything else, or no answer,
  is retried with exponential backoff and jitter (30 seconds doubling up to 6 hours, 10 attempts by default).
  After the last attempt the delivery is marked `DEAD`; it stays visible in the delivery history and can be
  resent with `POST /api/v1/webhook-deliveries/{id}/resend`.
- Redirects are **not** followed. Answer 2xx from the registered URL.
- Production endpoints must use HTTPS and must resolve to a public address; loopback, private, link-local and
  other non-public destinations are refused.

## Verifying a delivery

1. Read the **raw** request body bytes before any JSON parsing; re-serialising changes the bytes.
2. Reject the request if `Billing-Timestamp` is more than **5 minutes** from your clock (replay protection).
3. Select the secret that matches `Billing-Key-Id` (you may hold two during a rotation).
4. Compute `v1=` + hex(HMAC-SHA256(secret, timestamp + "." + rawBody)) and compare it with `Billing-Signature`
   in constant time.
5. Look up `Billing-Event-Id`; if you have processed it, answer 2xx and do nothing else.

### Java

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

static boolean verify(String secret, String timestamp, byte[] rawBody, String signatureHeader, long nowSeconds) throws Exception {
    if (Math.abs(nowSeconds - Long.parseLong(timestamp)) > 300) {
        return false; // stale or from the future
    }
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
    mac.update(rawBody);
    byte[] expected = ("v1=" + HexFormat.of().formatHex(mac.doFinal())).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expected, signatureHeader.getBytes(StandardCharsets.UTF_8));
}
```

### TypeScript (Node)

```ts
import { createHmac, timingSafeEqual } from "node:crypto";

export function verify(secret: string, timestamp: string, rawBody: Buffer, signatureHeader: string, nowSeconds = Date.now() / 1000): boolean {
  if (Math.abs(nowSeconds - Number(timestamp)) > 300) return false; // stale or from the future
  const expected = Buffer.from(
    "v1=" + createHmac("sha256", secret).update(`${timestamp}.`).update(rawBody).digest("hex"),
  );
  const received = Buffer.from(signatureHeader);
  return expected.length === received.length && timingSafeEqual(expected, received);
}
```

## Adding an event

1. Add `<type>.schema.json` in the producer's folder (copy a neighbour; keep `additionalProperties: false`).
2. Emit the event from the owning module with a payload that matches it.
3. Run the event contract test; it fails if an emitted type has no schema or a payload does not validate.
