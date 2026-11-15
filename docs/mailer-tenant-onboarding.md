# Mailer: Tenant Onboarding

How a Tenant sends mail through **its own** Amazon SES identity instead of the suite's. Mailer has two modes, chosen per
`fromKey` in the credential row:

| Mode | Who is the SES caller | Whose reputation, quota and suppression list | When to use it |
|---|---|---|---|
| `SHARED` | The suite's own verified identity, with mailer's task role | Ours | The default. Fine for system mail a Tenant never sees the headers of |
| `TENANT` | A role in the Tenant's AWS account, assumed at delivery time | **Theirs** | The Tenant wants its own domain in the `From` header, its own bounce handling and its own quota |

Under `TENANT`, mailer holds **no long-lived AWS credential**: one role ARN, one region and one encrypted external id per
credential row, and nothing else. That is the whole reason this mode was chosen over static access keys. The trade-off,
accepted deliberately, is that SES then treats the Tenant's account as the caller — quota, reputation, suppression and
bounce events are all theirs, and mailer does not consume them.

## What the Tenant creates

1. An **SES identity** for the domain or address it will send from, in the region it will tell us, with Easy DKIM
   verified. The Tenant owns that verification; the same three CNAMEs it would publish for any SES identity.
2. An **IAM role** with the trust policy below, so only our task role can assume it, and only with the external id.
3. **SES permissions** on that role, the two actions the table after the trust policy lists.

### The trust policy

This is the exact policy the Tenant attaches to the role it creates. It is the only place the external-id **value** is
checked:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::<our-account-id>:role/<environment>-tenancykit-task"
      },
      "Action": "sts:AssumeRole",
      "Condition": {
        "StringEquals": {
          "sts:ExternalId": "<the-external-id-we-handed-you>",
          "aws:PrincipalArn": "arn:aws:iam::<our-account-id>:role/<environment>-tenancykit-task"
        }
      }
    }
  ]
}
```

Both conditions are `StringEquals`, and both are required:

- **`sts:ExternalId`** blocks the confused-deputy case: an attacker who learns the role ARN still cannot assume it
  without the value. The external ids we mint always start with `mailer-`. That prefix is the only thing our own task
  role checks — a `StringLike` on `mailer-*` matches any string, so it is a presence check, not a value check. **The
  value check lives here, in the Tenant's trust policy**, because this is the only document that knows the value.
- **`aws:PrincipalArn`** pins the caller to our mailer task role. Naming the account in `Principal` is not enough on its
  own: any principal in our account that may call `sts:AssumeRole` could otherwise use it. We set the same condition on
  our side, from the other direction.

Mailer's task role is additionally limited to accounts ops has registered in `var.tenant_aws_account_ids`, one
`arn:aws:iam::<account>:role/mailer-*` resource each, and the account in the role ARN must match the account id on the
credential row. A role in an account nobody registered is not assumable at all, whatever its trust policy says.

### The role's permissions

The role needs exactly two actions. Note the prefix: `ses:` is the IAM action namespace for **both** SES API generations —
`sesv2` is the SDK and ARN service name, never an action prefix:

| Action | Resource | Why |
|---|---|---|
| `ses:SendEmail` | `arn:aws:ses:<region>:<their-account-id>:identity/<their-domain>` | The delivery itself |
| `ses:GetAccount` | `*` (the action takes no resource) | Onboarding and the credential health job. SESv2 has no `GetSendQuota`; `GetAccount` is what exists |

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "ses:SendEmail",
      "Resource": "arn:aws:ses:<region>:<their-account-id>:identity/<their-domain>"
    },
    {
      "Effect": "Allow",
      "Action": "ses:GetAccount",
      "Resource": "*"
    }
  ]
}
```

`ses:SendEmail` covers the SESv2 `SendEmail` call — do not add `sesv2:SendEmail`, which is not an action that exists.
Because `GetAccount` is what the onboarding probe calls, a role that omits it fails registration even though sending
would have worked; the probe is deliberately the cheapest call that proves both the trust policy and the permission.

Adding a `Condition` on `ses:FromAddress` on their side too is a reasonable extra: it keeps the role from being usable
for any other address.

## The region rule

**Region is a per-credential-row property, not a suite-wide setting.** An SES identity exists per AWS account *and* per
region: the same account with the same domain verified in `eu-west-1` has no usable identity in `us-east-1`. The role
is global (IAM), so the region is the only thing that decides which SES endpoint the send reaches.

This is why a Tenant tells us the region with the role, and why we store it on the row: the credential resolver builds
its client with that region, always. A client built with the wrong region does not fail as a permission error — it
answers with an identity-not-found or `MessageRejected` style error that looks unrelated to the mistake. A Tenant that
sends from two regions therefore has two credential rows, one per `fromKey`.

## The SES sandbox

A new AWS account is in the SES **sandbox**: it can send only to verified addresses or domains, and its daily quota is
small. Production access is a separate, account-level request with an initial response measured in days, not minutes.

Two consequences:

- Under `TENANT`, the Tenant's sandbox state applies, not ours. A Tenant that has not asked for production access can
  only mail recipients it has verified, and mailer cannot tell the difference until SES rejects the send — which it does
  as a permanent rejection, so the delivery reaches `DEAD` on the first attempt rather than retrying.
- Under `SHARED`, our account's sandbox state applies to every Tenant's mail. Onboarding a Tenant to production before
  the suite's own account is out of the sandbox is a mistake that shows up as a wall of `DEAD` rows.

Before onboarding anyone, confirm both the sending state and the quota:

```bash
aws sesv2 get-account --region "$SES_REGION" \
  --query '{production:ProductionAccessEnabled,enabled:SendingEnabled,quota:SendQuota}'
```

## What `SUCCEEDED` means, and what it does not

Under `TENANT` (and under `SHARED`), `SUCCEEDED` means **SES accepted the message**: the API returned a
`MessageId` and took responsibility for trying. It does not mean a mailbox received it. Between acceptance and an inbox
sit the recipient's mail server, the Tenant's reputation, their suppression list and, for a bounce or a complaint, an
asynchronous SES event that mailer does not consume.

So a delivery can be `SUCCEEDED` and still have bounced. The credential health job and the attempt history will not show
it, and neither will the ledger's status; that visibility needs the Tenant to let our account subscribe to their SES
notification topic, which is out of scope here and is the known gap this mode ships with.

## Registering a Tenant

Until an admin API exists, a credential row is written by ops (the database constraints are the last line of defence:
a `TENANT` row without a role ARN, an external id and an account id is impossible, and a `SHARED` row cannot carry a
role ARN). The values stored are exactly:

| Column | Value |
|---|---|
| `tenant_id` | The Tenant's id |
| `from_key` | The key producers name in the command, e.g. `default` |
| `mode` | `TENANT` |
| `region` | The region of the Tenant's identity — see the region rule above |
| `role_arn` | The role from the trust policy above |
| `external_id_ciphertext` | The external id, AES-GCM encrypted with the row id as its context, so a ciphertext moved to another row stops decrypting |
| `aws_account_id` | Must match the role ARN's account, and must be in `var.tenant_aws_account_ids` |
| `from_address`, `from_name` | What goes in the `From` header, inside the identity the Tenant verified |
| `reply_to_allowlist` | Empty means "the sender's own domain"; a producer's `replyTo` outside it is refused |

Registration is verify-then-store: the role is assumed and `GetAccount` is read **before** the row is written, and a
credential that fails verification writes nothing. A role revoked later surfaces as `AccessDenied` at send time, which
dies immediately instead of burning the retry budget, and the credential health sweep reports it.

## Related

The service itself: [mailer.md](mailer.md). Mailer's own AWS policy and the SES identity the suite creates:
`ops/terraform/README.md`.
