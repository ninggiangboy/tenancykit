variable "name_prefix" {
  description = "Prefix of every resource name, already carrying the environment and the suite name."
  type        = string
}

variable "environment" {
  description = "Deployment environment. Part of the broker-users task family name."
  type        = string
}

variable "region" {
  description = "Region for the log driver."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnets. A SINGLE_INSTANCE broker takes one."
  type        = list(string)
}

variable "data_security_group_id" {
  description = "Security group that admits 5671 and 443 from the tasks."
  type        = string
}

variable "task_security_group_id" {
  description = "Security group the one-shot broker-users task attaches."
  type        = string
}

variable "cluster_arn" {
  description = "ECS cluster the one-shot broker-users task runs in."
  type        = string
}

variable "execution_role_arn" {
  description = "Execution role that reads the secrets the task injects."
  type        = string
}

variable "log_group_name" {
  description = "CloudWatch Log group of the broker-users task."
  type        = string
}

variable "instance_type" {
  description = "Amazon MQ host instance type."
  type        = string
}

variable "engine_version" {
  description = "RabbitMQ engine version."
  type        = string
}

variable "admin_password" {
  description = "Password of the broker's admin user, generated in the secrets module."
  type        = string
  sensitive   = true
}

variable "platform_password" {
  description = "Password of the platform broker user."
  type        = string
  sensitive   = true
}

variable "billing_password" {
  description = "Password of the billing broker user."
  type        = string
  sensitive   = true
}

variable "mailer_password" {
  description = "Password of the mailer broker user."
  type        = string
  sensitive   = true
}

variable "notification_password" {
  description = "Notification broker user password."
  type        = string
  sensitive   = true
}

variable "mailroom_password" {
  description = "Mailroom broker user password."
  type        = string
  sensitive   = true
}

variable "secret_arns" {
  description = "Secret ARNs the broker-users task injects."
  type        = map(string)
}

variable "users_image" {
  description = "Image with curl for the one-shot broker-users task."
  type        = string
  default     = "curlimages/curl:8.11.1"
}
