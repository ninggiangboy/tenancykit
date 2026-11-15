locals {
  suite_name  = "tenancykit"
  name_prefix = "${var.environment}-${local.suite_name}"
  realm       = "billing"

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

  billing_public_url = "${local.public_scheme}://${var.public_domain}"
  email_public_url   = "${local.public_scheme}://${var.public_domain}/email"

  # An SES identity exists per account and per region, so the suite's own identity follows the deployment region unless
  # a tfvars file pins another one. Its ownership check is a DKIM record set this repository cannot publish: there is no
  # Route53 zone, so an operator adds the three CNAMEs by hand (see README.md).
  ses_region       = var.ses_region == "" ? var.region : var.ses_region
  ses_from_address = "noreply@${var.ses_domain}"

  log_group_names = {
    keycloak        = "/ecs/${var.environment}/keycloak"
    platform        = "/ecs/${var.environment}/platform"
    billing_api     = "/ecs/${var.environment}/billing-api"
    billing_worker  = "/ecs/${var.environment}/billing-worker"
    billing_migrate = "/ecs/${var.environment}/billing-migrate"
    mailer          = "/ecs/${var.environment}/mailer"
    mailer_migrate  = "/ecs/${var.environment}/mailer-migrate"
    notification          = "/ecs/${var.environment}/notification"
    notification_api      = "/ecs/${var.environment}/notification-api"
    notification_migrate  = "/ecs/${var.environment}/notification-migrate"
    email                 = "/ecs/${var.environment}/email"
    email_api             = "/ecs/${var.environment}/email-api"
    email_migrate         = "/ecs/${var.environment}/email-migrate"
    rds_init        = "/ecs/${var.environment}/rds-init"
    mq_users        = "/ecs/${var.environment}/mq-users"
  }
}
