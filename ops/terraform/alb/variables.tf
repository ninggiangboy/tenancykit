variable "name_prefix" {
  description = "Prefix of every resource name, already carrying the environment and the suite name."
  type        = string
}

variable "environment" {
  description = "Deployment environment. Part of the Billing target group name."
  type        = string
}

variable "vpc_id" {
  description = "VPC the target group belongs to."
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnets the internet-facing ALB spans."
  type        = list(string)
}

variable "alb_security_group_id" {
  description = "Security group that admits 80/443 from the internet."
  type        = string
}

variable "certificate_arn" {
  description = "ACM certificate for the HTTPS listener. Empty serves HTTP only."
  type        = string
}
