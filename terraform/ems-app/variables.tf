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
  description = "Environment name (dev, perf, prod). MUST match the workspace, which MUST match the platform layer's workspace — the app reads the platform's state for the same env."
  type        = string

  validation {
    condition     = contains(["dev", "perf", "prod"], var.env)
    error_message = "env must be one of: dev, perf, prod."
  }
}

# ---------------------------------------------------------------------------
# ECS service
# ---------------------------------------------------------------------------

variable "desired_count" {
  description = "Number of Fargate tasks the service runs. Terraform sets this at create time, then ignores it (CI/CD and autoscaling own it afterward via ignore_changes)."
  type        = number
  default     = 2
}

variable "task_cpu" {
  description = "Fargate CPU units for the bootstrap task definition (256/512/1024/...). The deploy workflow registers the real task def, which may differ."
  type        = string
  default     = "512"
}

variable "task_memory" {
  description = "Fargate memory (MiB) for the bootstrap task definition."
  type        = string
  default     = "1024"
}

variable "container_port" {
  description = "Port the app listens on. Must match the task definition and the ALB target group port (8080)."
  type        = number
  default     = 8080
}

variable "bootstrap_image" {
  description = "Placeholder image used ONLY for the initial Terraform-created task definition, so the service can be created on an empty ECR. The deploy workflow replaces it on first push; Terraform never reverts it (ignore_changes = [task_definition])."
  type        = string
  default     = "public.ecr.aws/docker/library/busybox:latest"
}
