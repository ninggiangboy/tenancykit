locals {
  # One randomly generated password per credential. The values live in Secrets Manager; the .tf files only ever
  # reference ARNs.
  generated_secrets = toset([
    "POSTGRES_SUPERUSER_PASSWORD",
    "PLATFORM_MIGRATION_DB_PASSWORD",
    "PLATFORM_DB_PASSWORD",
    "BILLING_MIGRATION_DB_PASSWORD",
    "BILLING_DB_PASSWORD",
    "TENANT_MIGRATION_DB_PASSWORD",
    "TENANT_DB_PASSWORD",
    "PLATFORM_RABBITMQ_PASSWORD",
    "BILLING_RABBITMQ_PASSWORD",
    "MQ_ADMIN_PASSWORD",
    "KEYCLOAK_DB_PASSWORD",
    "KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD",
    "PLATFORM_BOOTSTRAP_ADMIN_TEMPORARY_PASSWORD",
    "PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET",
    "SMOKE_CLIENT_SECRET",
    "BILLING_REPLICA_CLIENT_SECRET",
    "BILLING_SECRETS_MASTER_KEY",
    "MAILER_MIGRATION_DB_PASSWORD",
    "MAILER_DB_PASSWORD",
    "MAILER_RABBITMQ_PASSWORD",
    "MAILER_SECRETS_MASTER_KEY",
    "NOTIFICATION_MIGRATION_DB_PASSWORD",
    "NOTIFICATION_DB_PASSWORD",
    "NOTIFICATION_RABBITMQ_PASSWORD",
    "NOTIFICATION_REPLICA_CLIENT_SECRET",
    "MAILROOM_MIGRATION_DB_PASSWORD",
    "MAILROOM_DB_PASSWORD",
    "MAILROOM_RABBITMQ_PASSWORD",
    "MAILROOM_REPLICA_CLIENT_SECRET",
    "MAILROOM_TRACKING_SECRET",
  ])

  derived_secrets = toset([
    "PLATFORM_TOKEN_SIGNING_KEY",
    "PLATFORM_SERVICE_CLIENT_SECRET_HASH",
    "PLATFORM_NOTIFICATION_SERVICE_CLIENT_SECRET_HASH",
    "PLATFORM_MAILROOM_SERVICE_CLIENT_SECRET_HASH",
  ])

  secret_names = setunion(local.generated_secrets, local.derived_secrets)

  secret_values = merge(
    { for name in local.generated_secrets : name => random_password.this[name].result },
    {
      # ES256 private key in PEM form. One key for every Platform task, so a token signed by one is accepted by the
      # other; `PLATFORM_TOKEN_ALLOW_EPHEMERAL_KEY=false` forbids the per-process fallback.
      PLATFORM_TOKEN_SIGNING_KEY = tls_private_key.platform_signing.private_key_pem
      # Platform stores the client secret as its hex SHA-256, never in clear; Billing authenticates with the secret.
      PLATFORM_SERVICE_CLIENT_SECRET_HASH = sha256(random_password.this["BILLING_REPLICA_CLIENT_SECRET"].result)
      # Notification's own replica client, hashed the same way; the secret is never shared with Billing.
      PLATFORM_NOTIFICATION_SERVICE_CLIENT_SECRET_HASH = sha256(random_password.this["NOTIFICATION_REPLICA_CLIENT_SECRET"].result)
      # Email's own replica client, hashed the same way; never shared with another service's client.
      PLATFORM_MAILROOM_SERVICE_CLIENT_SECRET_HASH = sha256(random_password.this["MAILROOM_REPLICA_CLIENT_SECRET"].result)
      # base64 of 32 bytes, which is what Billing's AES-256 master key expects.
      BILLING_SECRETS_MASTER_KEY = base64encode(random_password.this["BILLING_SECRETS_MASTER_KEY"].result)
      # Same encoding, same reason: Mailer's AesGcmSecretCipher refuses a key that does not decode to exactly 32 bytes.
      MAILER_SECRETS_MASTER_KEY = base64encode(random_password.this["MAILER_SECRETS_MASTER_KEY"].result)
    }
  )
}

resource "random_password" "this" {
  for_each = local.generated_secrets

  length  = 32
  special = false
}

resource "tls_private_key" "platform_signing" {
  algorithm   = "ECDSA"
  ecdsa_curve = "P256"
}

resource "aws_secretsmanager_secret" "this" {
  for_each = local.secret_names

  name = "tenancykit/${var.environment}/${lower(each.key)}"
  # Development: destroying the stack releases the names at once instead of holding them for a recovery window.
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "this" {
  for_each = local.secret_names

  secret_id     = aws_secretsmanager_secret.this[each.key].id
  secret_string = local.secret_values[each.key]
}
