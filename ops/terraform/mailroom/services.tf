resource "aws_ecs_service" "api" {
  name            = "${var.environment}-mailroom-api"
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
        dns_name = "mailroom"
        port     = 8080
      }
    }
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this.arn
    container_name   = "mailroom-api"
    container_port   = 8080
  }

  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener_rule.path]
}

resource "aws_ecs_service" "worker" {
  name            = "${var.environment}-mailroom-worker"
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

  # No load balancer and no Service Connect: the worker consumes the queue, publishes commands and owns the jobs. The
  # api service is the only address a client resolves.

  lifecycle {
    ignore_changes = [task_definition]
  }
}
