output "worker_task_definition_arn" {
  value = aws_ecs_task_definition.worker.arn
}

output "migrate_task_definition_arn" {
  value = aws_ecs_task_definition.migrate.arn
}

output "service_name" {
  value = aws_ecs_service.this.name
}
