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

variable "task_role_arn" {
  description = "Task role the runtime sends through SES with, and assumes a tenant's role with in TENANT mode."
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

variable "log_group_names" {
  description = "CloudWatch Log groups for the worker and the one-shot migrate."
  type        = map(string)
}

variable "image" {
  description = "Mailer image, shared by both runtimes."
  type        = string
}

variable "desired_count" {
  description = "Mailer worker tasks."
  type        = number
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

variable "ses_region" {
  description = "Region of the suite's SES identity, reported as MAILER_SES_REGION."
  type        = string
}

variable "default_from_address" {
  description = "Address SHARED-mode sends come from, reported as MAILER_DEFAULT_FROM_ADDRESS."
  type        = string
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
