variable "name_prefix" {
  description = "Prefix of every resource name, already carrying the environment and the suite name."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC."
  type        = string
}

variable "availability_zone_count" {
  description = "Availability zones to spread the public and private subnets across."
  type        = number
  default     = 2
}
