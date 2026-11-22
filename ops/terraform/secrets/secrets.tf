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
    "KEYCLOAK_RABBITMQ_PASSWORD",
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
    "CUSTOMER_MIGRATION_DB_PASSWORD",
    "CUSTOMER_DB_PASSWORD",
    "CUSTOMER_RABBITMQ_PASSWORD",
    "CUSTOMER_REPLICA_CLIENT_SECRET",
    "CUSTOMER_SECRETS_MASTER_KEY",
    "LOYALTY_MIGRATION_DB_PASSWORD",
    "LOYALTY_DB_PASSWORD",
    "LOYALTY_RABBITMQ_PASSWORD",
    "LOYALTY_REPLICA_CLIENT_SECRET",
    "LOYALTY_SECRETS_MASTER_KEY",
    "SURVEY_MIGRATION_DB_PASSWORD",
    "SURVEY_DB_PASSWORD",
    "SURVEY_RABBITMQ_PASSWORD",
    "SURVEY_REPLICA_CLIENT_SECRET",
    "SURVEY_SECRETS_MASTER_KEY",
    "SURVEY_LINK_SECRET",
    "BOOKING_MIGRATION_DB_PASSWORD",
    "BOOKING_DB_PASSWORD",
    "BOOKING_RABBITMQ_PASSWORD",
    "BOOKING_REPLICA_CLIENT_SECRET",
    "BOOKING_SECRETS_MASTER_KEY",
    "BOOKING_TOKEN_SECRET",
    "WORKFLOW_MIGRATION_DB_PASSWORD",
    "WORKFLOW_DB_PASSWORD",
    "WORKFLOW_RABBITMQ_PASSWORD",
    "WORKFLOW_REPLICA_CLIENT_SECRET",
    "WORKFLOW_SECRET_KEY",
    "ATTRIBUTION_MIGRATION_DB_PASSWORD",
    "ATTRIBUTION_DB_PASSWORD",
    "ATTRIBUTION_RABBITMQ_PASSWORD",
    "ATTRIBUTION_REPLICA_CLIENT_SECRET",
    "ATTRIBUTION_CLICK_SECRET",
    "ATTRIBUTION_VISITOR_HASH_KEY",
  ])

  derived_secrets = toset([
    "PLATFORM_TOKEN_SIGNING_KEY",
    "PLATFORM_SERVICE_CLIENT_SECRET_HASH",
    "PLATFORM_NOTIFICATION_SERVICE_CLIENT_SECRET_HASH",
    "PLATFORM_MAILROOM_SERVICE_CLIENT_SECRET_HASH",
    "PLATFORM_CUSTOMER_SERVICE_CLIENT_SECRET_HASH",
    "PLATFORM_LOYALTY_SERVICE_CLIENT_SECRET_HASH",
    "PLATFORM_SURVEY_SERVICE_CLIENT_SECRET_HASH",
    "PLATFORM_BOOKING_SERVICE_CLIENT_SECRET_HASH",
    "PLATFORM_WORKFLOW_SERVICE_CLIENT_SECRET_HASH",
    "PLATFORM_ATTRIBUTION_SERVICE_CLIENT_SECRET_HASH",
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
      # Customer's and Loyalty's own replica clients, hashed the same way; neither is shared with another app's client.
      PLATFORM_CUSTOMER_SERVICE_CLIENT_SECRET_HASH = sha256(random_password.this["CUSTOMER_REPLICA_CLIENT_SECRET"].result)
      PLATFORM_LOYALTY_SERVICE_CLIENT_SECRET_HASH  = sha256(random_password.this["LOYALTY_REPLICA_CLIENT_SECRET"].result)
      # Survey's, Booking's, Workflow's and Attribution's own replica clients, hashed the same way; none is shared with
      # another app's client.
      PLATFORM_SURVEY_SERVICE_CLIENT_SECRET_HASH      = sha256(random_password.this["SURVEY_REPLICA_CLIENT_SECRET"].result)
      PLATFORM_BOOKING_SERVICE_CLIENT_SECRET_HASH     = sha256(random_password.this["BOOKING_REPLICA_CLIENT_SECRET"].result)
      PLATFORM_WORKFLOW_SERVICE_CLIENT_SECRET_HASH    = sha256(random_password.this["WORKFLOW_REPLICA_CLIENT_SECRET"].result)
      PLATFORM_ATTRIBUTION_SERVICE_CLIENT_SECRET_HASH = sha256(random_password.this["ATTRIBUTION_REPLICA_CLIENT_SECRET"].result)
      # base64 of 32 bytes, which is what Billing's AES-256 master key expects.
      BILLING_SECRETS_MASTER_KEY = base64encode(random_password.this["BILLING_SECRETS_MASTER_KEY"].result)
      # Same encoding, same reason: Mailer's AesGcmSecretCipher refuses a key that does not decode to exactly 32 bytes.
      MAILER_SECRETS_MASTER_KEY = base64encode(random_password.this["MAILER_SECRETS_MASTER_KEY"].result)
      # customer and loyalty wrap their webhook signing secrets with the same kind of key and the same length check.
      CUSTOMER_SECRETS_MASTER_KEY = base64encode(random_password.this["CUSTOMER_SECRETS_MASTER_KEY"].result)
      LOYALTY_SECRETS_MASTER_KEY  = base64encode(random_password.this["LOYALTY_SECRETS_MASTER_KEY"].result)
      # Survey, Booking and Workflow encrypt their at-rest secrets under the same kind of key: Workflow's connection and
      # Platform-credential secrets, the other two their webhook signing secrets.
      SURVEY_SECRETS_MASTER_KEY  = base64encode(random_password.this["SURVEY_SECRETS_MASTER_KEY"].result)
      BOOKING_SECRETS_MASTER_KEY = base64encode(random_password.this["BOOKING_SECRETS_MASTER_KEY"].result)
      WORKFLOW_SECRET_KEY        = base64encode(random_password.this["WORKFLOW_SECRET_KEY"].result)
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
