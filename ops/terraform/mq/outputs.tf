output "amqp_host" {
  value = local.amqp_host
}

output "amqp_port" {
  value = local.amqp_port
}

output "management_endpoint" {
  value = local.management_url
}

output "broker_arn" {
  value = aws_mq_broker.this.arn
}

output "users_task_definition_arn" {
  value = aws_ecs_task_definition.users.arn
}
