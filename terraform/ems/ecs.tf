# ---------------------------------------------------------------------------
# ECS cluster. Spinnaker creates the services and task definitions per deploy.
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

# One log group per environment. Spinnaker's task definition awslogs config
# points at /ecs/ems-<env>; canary and stable share the group but use
# different stream prefixes ('stable' / 'canary').
resource "aws_cloudwatch_log_group" "service" {
  name              = "/ecs/${local.app}-${var.env}"
  retention_in_days = var.env == "prod" ? 30 : 7
}
