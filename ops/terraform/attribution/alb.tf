resource "aws_lb_target_group" "this" {
  name                 = "${var.environment}-attribution-tg"
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

# Mirrors Caddy's /attribution/* route and its two public surfaces. ALB cannot strip a path prefix, so /attribution/* is
# forwarded unchanged: the same known gap mailroom's rule carries and the README records. /go/* (a tracked link) and
# /t/* (a browser's collection call) live at the root of a Tenant's own domain exactly as they do behind Caddy, so they
# need no rewrite; the signed click token in the path is their proof.
resource "aws_lb_listener_rule" "path" {
  listener_arn = var.listener_arn
  priority     = 110

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }

  condition {
    path_pattern {
      values = ["/attribution/*", "/go/*", "/t/*"]
    }
  }
}
