# Workload (EMS) infrastructure root.
#
# Terraform owns the platform AND the ECS service's shape: ALB, target groups,
# ECS cluster + service, IAM, RDS, ECR, secrets, log groups. The deploy workflow
# (.github/workflows/deploy.yml) owns what runs *inside* the service — it
# registers new task-definition revisions and rolls them out. Terraform ignores
# the service's task_definition + desired_count so the two never fight, while
# still tearing the service down in graph order on `terraform destroy`.
#
# Layout:
#   network.tf  security groups
#   alb.tf      ALB, target groups, listener + canary host-header rule
#   ecs.tf      ECS cluster + log group
#   service.tf  ECS service + bootstrap task definition
#   iam.tf      task and task-execution roles
#   ecr.tf      image registry
#   rds.tf      MySQL primary + optional replica
#   secrets.tf  SSM parameters + Secrets Manager + read-policy
#   outputs.tf  values consumed by the deploy workflow + Spinnaker pipeline

locals {
  app = "ems"
}
