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

variable "log_group_names" {
  description = "CloudWatch Log groups for api, worker and the one-shot migrate."
  type        = map(string)
}

variable "image" {
  description = "crm image, shared by all three runtimes."
  type        = string
}

variable "api_desired_count" {
  description = "crm api tasks."
  type        = number
}

variable "worker_desired_count" {
  description = "crm worker tasks."
  type        = number
}

variable "listener_arn" {
  description = "ALB listener the /crm/* rule attaches to."
  type        = string
}

variable "public_base_url" {
  description = "Public base URL a Tenant's system reaches /crm/... from."
  type        = string
}

variable "platform_internal_url" {
  description = "Platform address the replica snapshot is fetched from."
  type        = string
}

variable "platform_internal_jwks" {
  description = "Platform JWKS, fetched server-to-server."
  type        = string
}

variable "platform_public_issuer" {
  description = "Issuer Platform stamps into a suite token."
  type        = string
}

variable "keycloak_public_issuer" {
  description = "Keycloak issuer, the public address users sign in at."
  type        = string
}

variable "keycloak_internal_jwks" {
  description = "Keycloak JWKS, fetched server-to-server."
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
  description = "Secret ARNs the containers inject and the module's execution role reads."
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
