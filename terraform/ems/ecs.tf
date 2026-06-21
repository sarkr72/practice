# ---------------------------------------------------------------------------
# ECS cluster + log group.
#
# The cluster is the "empty box". The service that runs in it lives in
# service.tf (Terraform-owned). Task-definition *revisions* are registered by
# the deploy workflow on each push — Terraform only provides the initial
# bootstrap revision so the service can be created on a fresh environment.
# ---------------------------------------------------------------------------

resource "aws_ecs_cluster" "main" {
  name = "${local.app}-${var.env}"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

# One log group per environment. The deploy workflow's task definition points
# its awslogs config at /ecs/ems-<env> (stream prefix 'ecs'); the bootstrap
# task uses the 'bootstrap' prefix in the same group.
resource "aws_cloudwatch_log_group" "service" {
  name              = "/ecs/${local.app}-${var.env}"
  retention_in_days = var.env == "prod" ? 30 : 7
}
