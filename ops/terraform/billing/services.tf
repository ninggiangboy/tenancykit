resource "aws_ecs_service" "api" {
  name            = "${var.environment}-billing-api"
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_desired_count
  launch_type     = "FARGATE"

  health_check_grace_period_seconds  = 180
  deployment_minimum_healthy_percent = 50
  deployment_maximum_percent         = 200

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.task_security_group_id]
    assign_public_ip = false
  }

  service_connect_configuration {
    enabled   = true
    namespace = var.service_connect_namespace_arn

    service {
      port_name = "http"

      client_alias {
        dns_name = "billing"
        port     = 8080
      }
    }
  }

  load_balancer {
    target_group_arn = var.billing_target_group_arn
    container_name   = "billing"
    container_port   = 8080
  }

  # A task may not run before this revision's schema exists. ECS cannot order services, so the pipeline runs the
  # migrate task to completion first and only then forces this service onto the new revision.
  lifecycle {
    ignore_changes = [task_definition]
  }
}

resource "aws_ecs_service" "worker" {
  name            = "${var.environment}-billing-worker"
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.worker_desired_count
  launch_type     = "FARGATE"

  health_check_grace_period_seconds  = 180
  deployment_minimum_healthy_percent = 50
  deployment_maximum_percent         = 200

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.task_security_group_id]
    assign_public_ip = false
  }

  lifecycle {
    ignore_changes = [task_definition]
  }
}
