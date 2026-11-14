resource "aws_lb" "this" {
  name               = var.name_prefix
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.alb_security_group_id]
  subnets            = var.public_subnet_ids
  idle_timeout       = 60

  # Tenant resolution reads the Host header, so a malformed one is rejected at the edge instead of reaching a task.
  drop_invalid_header_fields = true
  enable_deletion_protection = false
}

# Billing is the default destination: everything that is neither Platform nor Keycloak, including every tenant host.
# The target group lives here because the listener's default action must reference it; the workload module receives
# its ARN instead of creating it, which keeps the module graph acyclic.
resource "aws_lb_target_group" "billing" {
  name                 = "${var.environment}-billing-tg"
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

# Rule priorities across the whole ALB: 10 path /platform/*, 20 host platform.<domain>, 30 host auth.<domain>. Both
# listeners (HTTP and HTTPS) use the same rules, so a request reaches the same service either way.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = var.certificate_arn == "" ? "forward" : "redirect"

    target_group_arn = var.certificate_arn == "" ? aws_lb_target_group.billing.arn : null

    dynamic "redirect" {
      for_each = var.certificate_arn == "" ? [] : [true]

      content {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }
}

resource "aws_lb_listener" "https" {
  count = var.certificate_arn == "" ? 0 : 1

  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = var.certificate_arn
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.billing.arn
  }
}
