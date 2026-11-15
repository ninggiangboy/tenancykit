resource "aws_ecs_service" "this" {
  name            = "${var.environment}-mailer"
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  health_check_grace_period_seconds  = 180
  deployment_minimum_healthy_percent = 50
  deployment_maximum_percent         = 200

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.task_security_group_id]
    assign_public_ip = false
  }

  # No load balancer and no Service Connect: mailer consumes commands and publishes nothing, so no address is
  # registered anywhere and no client resolves it.
  #
  # A task may not run before this revision's schema exists. ECS cannot order services, so the pipeline runs the
  # migrate task to completion first and only then forces this service onto the new revision.
  lifecycle {
    ignore_changes = [task_definition]
  }
}
