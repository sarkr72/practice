# Workload (EMS) infrastructure root.
#
# Terraform owns the immutable platform: ALB, target groups, ECS cluster,
# IAM, RDS, ECR, secrets, log groups. Spinnaker owns ECS services and task
# definitions (created on first pipeline run, versioned per deploy).
#
# Layout:
#   network.tf  security groups
#   alb.tf      ALB, target groups, listener + canary host-header rule
#   ecs.tf      ECS cluster + log group
#   iam.tf      task and task-execution roles
#   ecr.tf      image registry
#   rds.tf      MySQL primary + optional replica
#   secrets.tf  SSM parameters + Secrets Manager + read-policy
#   outputs.tf  values consumed by the Spinnaker pipeline JSON

locals {
  app = "ems"
}
