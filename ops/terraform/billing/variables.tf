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

variable "service_connect_namespace_arn" {
  description = "Cloud Map namespace Service Connect registers in."
  type        = string
}

variable "log_group_names" {
  description = "CloudWatch Log groups for api, worker and migrate."
  type        = map(string)
}

variable "image" {
  description = "Billing image, shared by all three runtimes."
  type        = string
}

variable "api_desired_count" {
  description = "Billing api tasks."
  type        = number
}

variable "worker_desired_count" {
  description = "Billing worker tasks."
  type        = number
}

variable "billing_target_group_arn" {
  description = "Default ALB target group, created in the alb module so the listener's default action can reference it."
  type        = string
}

variable "public_base_url" {
  description = "Public base URL webhook callbacks are built from."
  type        = string
}

variable "base_domain" {
  description = "Public base domain tenants live under."
  type        = string
}

variable "platform_internal_url" {
  description = "Internal Platform base URL, http://platform.<namespace>:8080."
  type        = string
}

variable "platform_internal_jwks" {
  description = "Internal Platform JWKS URL."
  type        = string
}

variable "platform_public_issuer" {
  description = "Platform issuer, the public address it stamps into a suite token."
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

variable "secret_arns" {
  description = "Secret ARNs the containers inject."
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
