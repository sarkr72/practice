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

variable "cluster_name" {
  description = "EKS cluster name that hosts Spinnaker."
  type        = string
  default     = "spinnaker"
}

variable "cluster_version" {
  description = "EKS Kubernetes version."
  type        = string
  default     = "1.31"
}

variable "node_instance_type" {
  description = "EC2 instance type for Spinnaker nodes. Spinnaker needs ~8GB RAM across its services."
  type        = string
  default     = "t3.large"
}

variable "node_desired_size" {
  description = "Desired number of worker nodes."
  type        = number
  default     = 2
}

variable "persistence_bucket_name" {
  description = "S3 bucket Spinnaker uses for front50 / clouddriver persistence. Must be globally unique."
  type        = string
  default     = "rinku-spinnaker-persistence-001"
}
