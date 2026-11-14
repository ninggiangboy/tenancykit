# Service Connect accepts only Cloud Map HTTP namespaces, so this is the namespace every ECS service registers in.
# ECS creates a VPC DNS record per registered service, which is why Billing reaches Platform at
# platform.<namespace> and Keycloak at keycloak.<namespace>. The namespace name is never hardcoded outside the root
# locals.
resource "aws_service_discovery_http_namespace" "this" {
  name        = var.namespace
  description = "Service Connect namespace for the tenancykit suite"
}
