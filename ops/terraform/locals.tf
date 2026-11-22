locals {
  suite_name  = "tenancykit"
  name_prefix = "${var.environment}-${local.suite_name}"
  realm       = "tenancykit"

  # The single source of truth for internal addresses. Service Connect registers each service under the DNS name
  # <service>.<namespace>, so billing reaches platform at local.internal_fqdn and Keycloak at local.keycloak_internal_fqdn.
  internal_fqdn          = "platform.${var.service_connect_namespace}"
  keycloak_internal_fqdn = "keycloak.${var.service_connect_namespace}"
  billing_internal_fqdn  = "billing.${var.service_connect_namespace}"

  # The public issuer is what Keycloak and Platform stamp into a token, so it must be the address a user reaches.
  # The internal URLs are server-to-server only (JWKS and replica snapshot).
  public_scheme        = var.certificate_arn == "" ? "http" : "https"
  platform_public_host = "platform.${var.public_domain}"
  keycloak_public_host = "auth.${var.public_domain}"

  keycloak_internal_url  = "http://${local.keycloak_internal_fqdn}:8080"
  keycloak_public_issuer = "${local.public_scheme}://${local.keycloak_public_host}/realms/${local.realm}"
  keycloak_internal_jwks = "${local.keycloak_internal_url}/realms/${local.realm}/protocol/openid-connect/certs"

  platform_internal_url  = "http://${local.internal_fqdn}:8080"
  platform_public_issuer = "${local.public_scheme}://${local.platform_public_host}"
  platform_internal_jwks = "${local.platform_internal_url}/.well-known/jwks.json"

  billing_public_url  = "${local.public_scheme}://${var.public_domain}"
  mailroom_public_url = "${local.public_scheme}://${var.public_domain}/mailroom"
  customer_public_url = "${local.public_scheme}://${var.public_domain}/customer"
  loyalty_public_url  = "${local.public_scheme}://${var.public_domain}/loyalty"
  survey_public_url   = "${local.public_scheme}://${var.public_domain}/survey"

  # Attribution's tracked links and collection calls live at the root of a Tenant's own domain, so its public base is the
  # domain itself and not a prefix under it.
  attribution_public_url = "${local.public_scheme}://${var.public_domain}"

  # Workflow calls an app on the Tenant's own hostname, `{host}` being replaced by the Tenant's domain at call time, with a
  # token it mints at Platform's public token endpoint. Both are public addresses because the engine's destination policy
  # refuses a private one.
  platform_token_url = "${local.platform_public_issuer}/oauth/token"
  workflow_app_urls = {
    billing  = "${local.public_scheme}://{host}"
    mailroom = "${local.public_scheme}://{host}/mailroom"
    customer = "${local.public_scheme}://{host}/customer"
    loyalty  = "${local.public_scheme}://{host}/loyalty"
    survey   = "${local.public_scheme}://{host}/survey"
  }

  # A respondent uploads from a page on the public domain or one of its tenant subdomains.
  survey_upload_origins = [
    "${local.public_scheme}://${var.public_domain}",
    "${local.public_scheme}://*.${var.public_domain}",
  ]

  # An SES identity exists per account and per region, so the suite's own identity follows the deployment region unless
  # a tfvars file pins another one. Its ownership check is a DKIM record set this repository cannot publish: there is no
  # Route53 zone, so an operator adds the three CNAMEs by hand (see README.md).
  ses_region       = var.ses_region == "" ? var.region : var.ses_region
  ses_from_address = "noreply@${var.ses_domain}"

  log_group_names = {
    keycloak             = "/ecs/${var.environment}/keycloak"
    platform             = "/ecs/${var.environment}/platform"
    billing_api          = "/ecs/${var.environment}/billing-api"
    billing_worker       = "/ecs/${var.environment}/billing-worker"
    billing_migrate      = "/ecs/${var.environment}/billing-migrate"
    mailer               = "/ecs/${var.environment}/mailer"
    mailer_migrate       = "/ecs/${var.environment}/mailer-migrate"
    notification         = "/ecs/${var.environment}/notification"
    notification_api     = "/ecs/${var.environment}/notification-api"
    notification_migrate = "/ecs/${var.environment}/notification-migrate"
    mailroom             = "/ecs/${var.environment}/mailroom"
    mailroom_api         = "/ecs/${var.environment}/mailroom-api"
    mailroom_migrate     = "/ecs/${var.environment}/mailroom-migrate"
    customer             = "/ecs/${var.environment}/customer"
    customer_api         = "/ecs/${var.environment}/customer-api"
    customer_migrate     = "/ecs/${var.environment}/customer-migrate"
    loyalty              = "/ecs/${var.environment}/loyalty"
    loyalty_api          = "/ecs/${var.environment}/loyalty-api"
    loyalty_migrate      = "/ecs/${var.environment}/loyalty-migrate"
    survey               = "/ecs/${var.environment}/survey"
    survey_api           = "/ecs/${var.environment}/survey-api"
    survey_migrate       = "/ecs/${var.environment}/survey-migrate"
    booking              = "/ecs/${var.environment}/booking"
    booking_api          = "/ecs/${var.environment}/booking-api"
    booking_migrate      = "/ecs/${var.environment}/booking-migrate"
    workflow             = "/ecs/${var.environment}/workflow"
    workflow_api         = "/ecs/${var.environment}/workflow-api"
    workflow_migrate     = "/ecs/${var.environment}/workflow-migrate"
    attribution          = "/ecs/${var.environment}/attribution"
    attribution_api      = "/ecs/${var.environment}/attribution-api"
    attribution_migrate  = "/ecs/${var.environment}/attribution-migrate"
    rds_init             = "/ecs/${var.environment}/rds-init"
    mq_users             = "/ecs/${var.environment}/mq-users"
  }
}
