# Security and Reliability

Requirements beyond business functions.

## Authentication and session

- OIDC authentication through Keycloak.
- An app accepts exactly two issuers: the Keycloak realm (a user token) and Platform (a suite token), and only for its
  own audience (`suite.app-code`). Any other issuer or audience is a 401.
- A suite token is verified against Platform's ES256 JWKS; the decoder names ES256 explicitly, because a decoder built
  without an algorithm defaults to RS256 and would refuse every suite token.
- Validate token Issuer, Audience, Expiration and Subject.
- Distinguish Platform Context from Tenant Context; the hostname fixes the scope before the security chain runs.
- A session from one Tenant must not be usable in another Tenant.
- Revoking a role at Platform reaches an app through its replica; a revoked Tenant System credential's tokens stay
  valid until they expire (at most 10 minutes), while disabling the Tenant System is immediate.

## Suite security

- Broker users. Each service has its own RabbitMQ user, limited by permission regexes to its own exchange and queues.
  A service can publish only to its own exchange and read only its own queues; `ops/smoke/isolation.sh` proves a
  cross-exchange publish is refused.
- Token signing key rotation. The ES256 private key comes from the environment. During rotation the previous key stays
  in the JWKS for one token lifetime, so tokens signed just before the switch keep verifying. A blank key is refused
  unless the development-only ephemeral option is set, and an ephemeral key differs per process.
- Database isolation. One database and one unprivileged app role per service, with `revoke connect ... from public`,
  so one service's app role cannot open another service's database.
- No secret in a committed file. `.env.example` holds placeholders only; the developer realm export carries only the
  client secret placeholders that compose substitutes.
- The development-only `suite-smoke` client enables a direct grant so the smoke test can sign in the accounts it
  drives. It exists only in the compose realm export and is documented as such; a real deployment must not import it.

## Data security

- Tenant-scoped data access everywhere, see [multi-tenancy-and-data-isolation.md](multi-tenancy-and-data-isolation.md).
- Composite foreign keys for important relationships.
- PostgreSQL Row-Level Security where appropriate.
- Sensitive data is never exposed through logs, APIs or Webhooks.
- Secrets stored in the database (for example webhook signing secrets and Stripe keys) are AES-GCM envelope-encrypted under a master key from the environment, bound to the owning row, and never returned by an API after creation.
- A call to a Tenant-chosen URL is guarded against server-side request forgery: public addresses only, checked in the connection's own DNS resolution, no redirects, bounded time and size. Only the webhook sender may make such a call, enforced by an architecture rule.

## Payment security

- Encrypt the Stripe Secret Key and Webhook Secret.
- Verify the Stripe Webhook signature, with a bounded timestamp tolerance, before anything in the body is read; the tenant is found from Billing's routing table, not from the request. The webhook endpoints are the only unauthenticated ones and are listed explicitly; a failed verification is recorded as an incident. Because they are open, rate limiting in front of the deployment is advised.
- Keep Stripe's own types inside the Stripe adapter (enforced by an architecture rule) and never copy Stripe's error text into a response, log or exception: it can echo parts of a credential.
- Hold no database row lock across a call to Stripe: what an event needs from Stripe is read before its transaction opens.
- Never store payment card information directly.
- Use Stripe-hosted Checkout or the Payment Element where appropriate.
- Reconcile transactions, and handle Webhooks that arrive late or out of order.

## Reliability

- Transactional Outbox for important Events.
- Bounded Retry with Backoff. The outbox relay and the webhook sender both retry with exponential backoff and jitter within an attempt limit.
- Dead Letter Queue. A row that runs out of attempts becomes `DEAD` and is kept; a dead webhook delivery can be resent. A broker message a consumer cannot process after its retries lands in the queue's `.dlq`; the runbook is in [suite-messaging.md](suite-messaging.md#dead-letter-handling).
- Idempotent Consumer. In-process consumers record each handled event id in an inbox table in the same transaction as their effect.
- Background jobs run once per Tenant, each inside that Tenant's scope, because Row-Level Security lets a job see one Tenant at a time. A failure in one Tenant does not stop the others.
- Singleton heavy jobs (ledger verification and Stripe reconciliation) own a time-bounded lease; a per-day run record makes a repeated verification a no-op. Lock order, lease rules and the retryable lock errors are in [concurrency-and-locking.md](concurrency-and-locking.md).
- Audit Log of administrative actions and transactions.
- Monitoring and Distributed Tracing.

## Related

Stripe handling: [payments-and-stripe.md](payments-and-stripe.md). Outbound delivery: [webhooks-and-events.md](webhooks-and-events.md).
