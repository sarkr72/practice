# EMS app layer root.
#
# This layer owns ONLY the ECS service and its bootstrap task definition. The
# durable, data-bearing resources (ECS cluster, ALB, IAM, RDS, ECR, secrets,
# security groups, log group) live in the platform layer at ../ems (state key
# ems/terraform.tfstate).
#
# Apply order:   platform (../ems)  ->  app (here)
# Destroy order: app (here)         ->  platform (../ems)
#
# Tearing down this layer stops the bill for the running app and frees the task
# ENIs, while the cluster, ALB, and — critically — the database are untouched
# in the platform layer's separate state. That is the whole reason for the split.

locals {
  app      = "ems"
  platform = data.terraform_remote_state.platform.outputs
}
