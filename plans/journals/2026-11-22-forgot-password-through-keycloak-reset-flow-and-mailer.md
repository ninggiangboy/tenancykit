---
title: Forgot password through Keycloak's reset flow and mailer
date: 2026-11-22
summary: "People reset their own password by email on their tenant's host; Keycloak renders the mail and mailer delivers it. Verified end to end on the local stack; one older e2e spec still fails."
---

# Forgot password through Keycloak's reset flow and mailer

## What happened

The realm had `resetPasswordAllowed` off and no way to send mail. Turning the flag on was not enough: usernames are
`{tenant-code}:{email}` and emails repeat across tenants, so Keycloak's stock lookup finds no one and still says the mail
was sent. The suite also allows only mailer to call AWS, so a realm SMTP setting was ruled out.

- `ops/keycloak/provider/`: a tenant-aware choose-user step for the reset flow (the typed email becomes
  `<tenant>:<email>`, shared with the sign-in form through `TenantFlowContext`), and an email sender that publishes the
  already-rendered mail to `mailer.commands` as an `email.send` command. A contract test checks the command against
  `contracts/commands/email.send.schema.json`.
- Realm: `suite-reset-credentials` bound as the reset flow, `resetPasswordAllowed` on, a 15 minute link, and `tenant_id`
  declared in the user profile (admin only). The authenticator id had to be 30 characters because Keycloak stores at most
  36.
- Platform writes `tenant_id` on every identity it creates. A new Tenant's id is now chosen before its administrator's
  identity is created, because that identity is created before the tenant row exists.
- Broker user `keycloak` may write `mailer.commands` only (`declare-users.sh`, `isolation.sh`, Compose, Terraform).
- `ops/keycloak/backfill-tenant-id.sh` fills in older identities; it does nothing unless `APPLY=1` and backs each user up
  first.
- In Keycloak 26.8.2 the reset email step catches a failed send, logs it and still shows the neutral message, so a broker
  outage does not reveal which emails have accounts. This replaced the earlier plan to swallow the failure in the sender.

## Evidence

- Provider unit tests, Platform identity-provider test and the provisioning, compensation and bootstrap integration
  tests pass.
- A scripted browser flow on the local stack: forgot link, Email-labelled reset page, one mail in Mailpit through the
  broker and mailer, the link sets a new password, and the same email in a second tenant is untouched. An unknown email
  and an unknown tenant get the same answer and send nothing.
- `tests/15-forgot-password.spec.ts` passes against the full compose stack with a tenant administrator that Platform
  provisioned, so `tenant_id` was written by Platform and not by hand.
- `isolation.sh` accepts the `keycloak` grants exactly; its later check for other apps' queues fails only because those
  apps were not running.

## Not working or not done

- `tests/14-tenant-sign-in.spec.ts` fails when a second tenant's user with no role signs in: the console redirects in a
  loop. That spec was not run when it was written, so this is probably an older defect, but it was not confirmed.
- No Vietnamese messages for the reset pages; the theme has only English.
- The Terraform changes could not be validated because `terraform` is not installed here.
- Recreating the local Keycloak loses its users (its dev database lives in the container). They were restored from
  Platform's database with their original subjects, tenant ids and the default seed password.
- The local `.env` still holds a stale `KEYCLOAK_REALM`.

## Next steps

- Run `backfill-tenant-id.sh` against any real realm before relying on resets for existing users.
- Fix spec 14 and `KEYCLOAK_REALM` in `.env`.
- An existing realm needs the reset flow, `tenant_id` attribute and sender variables added by hand (documented).

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
