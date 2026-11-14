resource "aws_ecs_service" "this" {
  name            = "${var.environment}-platform"
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  # Two replicas migrate on start. Flyway takes a lock, so the second waits instead of writing the schema twice; the
  # readiness probe simply arrives later. If the contention shows up in practice, Platform gets its own migrate task
  # like Billing.
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
        dns_name = "platform"
        port     = 8080
      }
    }
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this.arn
    container_name   = "platform"
    container_port   = 8080
  }

  depends_on = [aws_lb_listener_rule.path, aws_lb_listener_rule.host]
}
