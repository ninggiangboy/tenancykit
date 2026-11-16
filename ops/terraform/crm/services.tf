resource "aws_ecs_service" "api" {
  name            = "${var.environment}-crm-api"
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
        dns_name = "crm"
        port     = 8080
      }
    }
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this.arn
    container_name   = "crm-api"
    container_port   = 8080
  }

  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener_rule.path]
}

resource "aws_ecs_service" "worker" {
  name            = "${var.environment}-crm-worker"
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

  # No load balancer and no Service Connect: the worker consumes the queue, relays the outbox and delivers the webhooks.
  # The api service is the only address a client resolves, and nothing in the suite calls crm at all.

  lifecycle {
    ignore_changes = [task_definition]
  }
}
