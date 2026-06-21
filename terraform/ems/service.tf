# ---------------------------------------------------------------------------
# ECS service (Fargate) — Terraform-owned, CI/CD-updated.
#
# WHY Terraform owns the service now (it used to be created out-of-band by the
# deploy workflow): putting the service in the Terraform dependency graph is
# what makes `terraform destroy` deterministic and error-free. On destroy the
# AWS provider drains THIS service's tasks first, waits for the Fargate ENIs to
# detach, then lets the dependents go, in this order:
#
#   aws_ecs_service.app           (drain tasks, deregister from ALB)
#     -> aws_security_group.tasks  (provider retries DependencyViolation until
#                                   the task ENIs finish detaching — no race)
#     -> aws_lb_target_group.*      / aws_lb_listener.http
#     -> aws_ecs_cluster.main       (no ClusterContainsServicesException — the
#                                   service is already gone)
#
# That is why teardown.tf (a null_resource destroy-time provisioner) was deleted:
# the graph now does the job the provisioner used to fake, with real waiters.
#
# HOW it coexists with continuous deployment (the canonical pattern):
#   lifecycle { ignore_changes = [task_definition, desired_count] }
# Terraform creates the service pinned to the bootstrap task definition and
# var.desired_count, then NEVER touches those two fields again. The deploy
# workflow registers new task-definition revisions and calls `update-service`;
# autoscaling (if you add it) owns the count. Terraform owns the *shape* of the
# service (networking, ALB wiring, health grace, rollout policy); CD owns
# *what's running inside it*. The two never fight.
# ---------------------------------------------------------------------------

# Bootstrap task definition.
#
# The service can't be created without a task definition, and on a fresh
# environment the ECR repo is empty — so we register a tiny placeholder revision
# here purely so the service can come up. The deploy workflow immediately
# registers the real revision (same family, `ems-<env>`) and rolls onto it;
# Terraform never reverts it because the service ignores task_definition.
#
# Expect the bootstrap tasks to sit UNHEALTHY behind the ALB until the first
# deploy — that's normal and harmless. They serve nothing; they just hold the
# service open.
resource "aws_ecs_task_definition" "bootstrap" {
  family                   = "${local.app}-${var.env}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name      = local.app
      image     = var.bootstrap_image
      essential = true
      # Keep the placeholder alive so the service settles; the real app replaces
      # it on first deploy.
      command = ["sh", "-c", "echo 'ems bootstrap placeholder - replaced on first deploy'; while true; do sleep 3600; done"]
      portMappings = [
        { containerPort = var.container_port, protocol = "tcp" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.service.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "bootstrap"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "app" {
  name            = "${local.app}-${var.env}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.bootstrap.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  # Spring Boot needs time to start before failed ALB health checks count
  # against the task.
  health_check_grace_period_seconds = 60

  # Rolling deploy: stay at 100% healthy, allow 200% during a rollout.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  # Auto-roll-back a deployment that never reaches steady state to the last
  # good task definition.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets         = data.aws_subnets.default.ids
    security_groups = [aws_security_group.tasks.id]
    # Default-VPC subnets are public; tasks need a public IP to reach ECR and
    # Secrets Manager without a NAT gateway.
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.stable.arn
    container_name   = local.app
    container_port   = var.container_port
  }

  # NOTE: we intentionally do NOT set wait_for_steady_state. The bootstrap tasks
  # never pass health checks, so waiting would hang `terraform apply`. Steady
  # state is the deploy workflow's concern (it runs `aws ecs wait
  # services-stable` after rolling out the real image).

  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  # The listener must exist before the service registers targets, and must be
  # torn down only after the service has deregistered them.
  depends_on = [aws_lb_listener.http]
}
