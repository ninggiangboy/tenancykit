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
  description = "Keycloak image with the realm export baked in."
  type        = string
}

variable "desired_count" {
  description = "Keycloak tasks."
  type        = number
}

variable "listener_arn" {
  description = "ALB listener the host rule attaches to."
  type        = string
}

variable "public_host" {
  description = "Public host the ALB routes to Keycloak, e.g. auth.example.com."
  type        = string
}

variable "public_url" {
  description = "Public base URL, including scheme. Keycloak uses it as KC_HOSTNAME so the issuer is stable."
  type        = string
}

variable "internal_url" {
  description = "Internal URL other tasks use, http://keycloak.<namespace>:8080."
  type        = string
}

variable "realm" {
  description = "Realm imported at start."
  type        = string
}

variable "public_issuer" {
  description = "Issuer Keycloak stamps into a token; the public address, never the internal one."
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

variable "secret_arns" {
  description = "Secret ARNs the container injects."
  type        = map(string)
}

variable "cpu" {
  description = "Task CPU units."
  type        = number
  default     = 512
}

variable "memory" {
  description = "Task memory in MiB."
  type        = number
  default     = 1024
}
