output "cluster_arn" {
  value = aws_ecs_cluster.this.arn
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "execution_role_arn" {
  value = aws_iam_role.execution.arn
}

output "task_role_arn" {
  value = aws_iam_role.task.arn
}

output "ecr_repository_urls" {
  value = { for name, repository in aws_ecr_repository.this : name => repository.repository_url }
}

output "log_group_names" {
  value = var.log_group_names
}

output "log_group_arns" {
  value = { for name, group in aws_cloudwatch_log_group.this : name => group.arn }
}
