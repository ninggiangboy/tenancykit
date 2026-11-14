output "task_definition_arn" {
  description = "Keycloak task definition."
  value       = aws_ecs_task_definition.this.arn
}

output "service_name" {
  value = aws_ecs_service.this.name
}

output "target_group_arn" {
  value = aws_lb_target_group.this.arn
}

output "internal_url" {
  description = "The address Platform and Billing use for server-to-server calls."
  value       = var.internal_url
}

output "public_issuer" {
  description = "The issuer Keycloak stamps into a token."
  value       = var.public_issuer
}
