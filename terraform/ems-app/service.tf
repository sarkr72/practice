# ---------------------------------------------------------------------------
# ECS service (Fargate) — the app layer.
#
# Identical service to before, but it now lives in its OWN state and pulls all
# its dependencies (cluster, tasks SG, subnets, ALB target group, IAM roles,
# log group) from the platform layer via terraform_remote_state. Nothing here
# creates platform infrastructure; it only consumes it.
#
# Destroy story (per layer):
#   terraform destroy (here) -> drains tasks, waits for the Fargate ENIs to
#   detach, deletes the service. The platform layer's cluster / ALB / RDS are
#   in a different state file and are never touched.
#
# CI/CD coexistence (unchanged): lifecycle ignore_changes = [task_definition,
# desired_count]. The deploy workflow registers new task-def revisions and runs
# `update-service`; this layer never reverts them.
# ---------------------------------------------------------------------------

# Bootstrap task definition — placeholder so the service can be created on an
# empty ECR. The deploy workflow registers the real revision (same family) and
# rolls onto it; the service ignores task_definition, so this is never reverted.
# Bootstrap tasks sit UNHEALTHY behind the ALB until the first deploy — expected.
resource "aws_ecs_task_definition" "bootstrap" {
  family                   = "${local.app}-${var.env}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = local.platform.task_execution_role_arn
  task_role_arn            = local.platform.task_role_arn

  container_definitions = jsonencode([
    {
      name      = local.app
      image     = var.bootstrap_image
      essential = true
      command   = ["sh", "-c", "echo 'ems bootstrap placeholder - replaced on first deploy'; while true; do sleep 3600; done"]
      portMappings = [
        { containerPort = var.container_port, protocol = "tcp" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = local.platform.log_group
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "bootstrap"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "app" {
  name            = "${local.app}-${var.env}"
  cluster         = local.platform.ecs_cluster_arn
  task_definition = aws_ecs_task_definition.bootstrap.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  health_check_grace_period_seconds = 60

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = local.platform.subnet_ids
    security_groups  = [local.platform.tasks_security_group_id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = local.platform.target_group_stable_arn
    container_name   = local.app
    container_port   = var.container_port
  }

  # No wait_for_steady_state: the bootstrap tasks never pass health checks, so
  # waiting would hang apply. Steady state is the deploy workflow's concern.
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }
}
