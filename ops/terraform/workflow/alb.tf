resource "aws_lb_target_group" "this" {
  name                 = "${var.environment}-workflow-tg"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    enabled             = true
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# Mirrors Caddy's /workflow/* route. ALB cannot strip a path prefix, so this rule forwards the path unchanged: the same
# known gap mailroom's rule carries and the README records. The signed app hooks arrive at /workflow/hooks/..., a
# spelling the application accepts as well as the stripped one.
resource "aws_lb_listener_rule" "path" {
  listener_arn = var.listener_arn
  priority     = 100

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }

  condition {
    path_pattern {
      values = ["/workflow/*"]
    }
  }
}
