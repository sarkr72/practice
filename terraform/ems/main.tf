# EMS platform layer root (the durable, data-bearing floor).
#
# This layer owns everything that rarely changes and is expensive to lose: the
# ALB, target groups, ECS cluster, IAM, RDS, ECR, secrets, security groups and
# log group. The ECS *service* lives in a SEPARATE layer/state at ../ems-app, so
# it can be torn down on its own without ever touching the database here.
#
#   Apply order:   platform (here)  ->  app (../ems-app)
#   Destroy order: app (../ems-app) ->  platform (here)
#
# The deploy workflow (.github/workflows/deploy.yml) owns what runs inside the
# service (task-def revisions); this layer just exposes cluster / ALB / IAM /
# subnets / SG / log group as outputs the app layer reads via terraform_remote_state.
#
# Layout:
#   network.tf  security groups
#   alb.tf      ALB, target groups, listener + canary host-header rule
#   ecs.tf      ECS cluster + log group
#   iam.tf      task and task-execution roles
#   ecr.tf      image registry
#   rds.tf      MySQL primary + optional replica
#   secrets.tf  SSM parameters + Secrets Manager + read-policy
#   outputs.tf  values consumed by the app layer + deploy workflow + Spinnaker

locals {
  app = "ems"
}
