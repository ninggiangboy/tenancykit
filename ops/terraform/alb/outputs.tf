output "alb_dns_name" {
  value = aws_lb.this.dns_name
}

output "public_url" {
  value = "${var.certificate_arn == "" ? "http" : "https"}://${aws_lb.this.dns_name}"
}

output "listener_arn" {
  description = "Listener the workload modules attach their rules to."
  value       = var.certificate_arn == "" ? aws_lb_listener.http.arn : aws_lb_listener.https[0].arn
}

output "billing_target_group_arn" {
  description = "Default target group, consumed by the Billing api service."
  value       = aws_lb_target_group.billing.arn
}
