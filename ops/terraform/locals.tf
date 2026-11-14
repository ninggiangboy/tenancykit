locals {
  name_prefix = "${var.environment}-core-billing"
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

  log_group_names = {
    keycloak        = "/ecs/${var.environment}/keycloak"
    platform        = "/ecs/${var.environment}/platform"
    billing_api     = "/ecs/${var.environment}/billing-api"
    billing_worker  = "/ecs/${var.environment}/billing-worker"
    billing_migrate = "/ecs/${var.environment}/billing-migrate"
    rds_init        = "/ecs/${var.environment}/rds-init"
    mq_users        = "/ecs/${var.environment}/mq-users"
  }
}
