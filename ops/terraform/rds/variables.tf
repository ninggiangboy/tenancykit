variable "name_prefix" {
  description = "Prefix of every resource name, already carrying the environment and the suite name."
  type        = string
}

variable "environment" {
  description = "Deployment environment. Part of the init task family name."
  type        = string
}

variable "region" {
  description = "Region for the log driver."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnets the database and the one-shot init task use."
  type        = list(string)
}

variable "data_security_group_id" {
  description = "Security group that admits 5432 from the tasks."
  type        = string
}

variable "task_security_group_id" {
  description = "Security group the one-shot init task attaches."
  type        = string
}

variable "cluster_arn" {
  description = "ECS cluster the one-shot init task runs in."
  type        = string
}

variable "execution_role_arn" {
  description = "Execution role that reads the secrets the init task injects."
  type        = string
}

variable "log_group_name" {
  description = "CloudWatch Log group of the init task."
  type        = string
}

variable "instance_class" {
  description = "RDS instance class."
  type        = string
}

variable "engine_version" {
  description = "PostgreSQL engine version."
  type        = string
}

variable "allocated_storage" {
  description = "Allocated storage in GiB."
  type        = number
}

variable "master_password" {
  description = "Master password, generated in the secrets module."
  type        = string
  sensitive   = true
}

variable "secret_arns" {
  description = "Secret ARNs the init task injects as environment variables."
  type        = map(string)
}

variable "init_image" {
  description = "psql client image for the init task."
  type        = string
  default     = "postgres:18"
}
