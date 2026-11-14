output "address" {
  value = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "init_task_definition_arn" {
  value = aws_ecs_task_definition.init.arn
}
