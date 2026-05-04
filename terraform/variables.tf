variable "aws_account_id" {
  description = "AWS account ID. Supplied via account.auto.tfvars (gitignored) or TF_VAR_aws_account_id."
  type        = string
  sensitive   = true

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id must be a 12-digit AWS account number."
  }
}

variable "aws_region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "env" {
  description = "Environment name (dev, prod)."
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.env)
    error_message = "env must be one of: dev, prod."
  }
}

# ---------------------------------------------------------------------------
# RDS MySQL
# ---------------------------------------------------------------------------

variable "db_name" {
  description = "MySQL database name."
  type        = string
  default     = "ems"
}

variable "db_username" {
  description = "Master username for RDS."
  type        = string
  default     = "ems_admin"
}

variable "db_instance_class" {
  description = "RDS instance size."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "Storage in GB."
  type        = number
  default     = 20
}

variable "db_engine_version" {
  description = "MySQL minor version. 8.0.45 is current 8.0 minor as of 2026; 8.4.x is the LTS major."
  type        = string
  default     = "8.0.45"
}

variable "create_read_replica" {
  description = "Whether to create a read replica. Off by default to save cost."
  type        = bool
  default     = false
}
