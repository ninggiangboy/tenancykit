variable "environment" {
  description = "Deployment environment. Prefixes every resource name."
  type        = string
}

variable "region" {
  description = "Region for the log driver."
  type        = string
}

variable "cluster_arn" {
  description = "ECS cluster."
  type        = string
}

variable "execution_role_arn" {
  description = "Execution role that pulls the image and reads the secrets."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnets the tasks run in."
  type        = list(string)
}

variable "task_security_group_id" {
  description = "Security group of the tasks."
  type        = string
}

variable "vpc_id" {
  description = "VPC the target group belongs to."
  type        = string
}

variable "service_connect_namespace_arn" {
  description = "Cloud Map namespace Service Connect registers in."
  type        = string
}

variable "log_group_name" {
  description = "CloudWatch Log group."
  type        = string
}

variable "image" {
  description = "Platform image."
  type        = string
}

variable "desired_count" {
  description = "Platform tasks."
  type        = number
}

variable "listener_arn" {
  description = "ALB listener the path and host rules attach to."
  type        = string
}

variable "public_host" {
  description = "Public admin host, platform.<domain>."
  type        = string
}

variable "base_domain" {
  description = "Public base domain tenants live under."
  type        = string
}

variable "internal_hostname" {
  description = "Internal FQDN Billing calls, platform.<namespace>."
  type        = string
}

variable "keycloak_internal_url" {
  description = "Keycloak address for server-to-server admin calls."
  type        = string
}

variable "keycloak_public_issuer" {
  description = "Keycloak issuer, the public address users sign in at."
  type        = string
}

variable "keycloak_internal_jwks" {
  description = "Keycloak JWKS, fetched server-to-server over the internal address."
  type        = string
}

variable "public_issuer" {
  description = "Issuer Platform stamps into a suite token: the public address, never the internal one."
  type        = string
}

variable "db_host" {
  description = "RDS address."
  type        = string
}

variable "db_port" {
  description = "RDS port."
  type        = number
}

variable "broker_host" {
  description = "Amazon MQ AMQPS host."
  type        = string
}

variable "broker_port" {
  description = "Amazon MQ AMQPS port."
  type        = number
}

variable "admin_email" {
  description = "First Platform administrator created on start. Empty skips the bootstrap."
  type        = string
}

variable "secret_arns" {
  description = "Secret ARNs the container injects."
  type        = map(string)
}

variable "cpu" {
  description = "Task CPU units. Fargate pairs 512 units with at least 1024 MiB."
  type        = number
  default     = 512
}

variable "memory" {
  description = "Task memory in MiB."
  type        = number
  default     = 1024
}
