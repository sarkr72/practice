locals {
  app = "image-uploader"
}

# ---------------------------------------------------------------------------
# Security groups
# ---------------------------------------------------------------------------

resource "aws_security_group" "alb" {
  name        = "${local.app}-${var.env}-alb"
  description = "ALB ingress from internet"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "HTTP from anywhere"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "All egress"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# Tasks SG. Name is referenced verbatim in the Spinnaker pipeline JSON
# (`securityGroups: ["image-uploader-<env>-tasks"]`). Don't rename without
# also updating the pipeline.
resource "aws_security_group" "tasks" {
  name        = "${local.app}-${var.env}-tasks"
  description = "Allow ALB to reach Fargate tasks"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "App port from ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "All egress (ECR pulls, Secrets Manager, etc.)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# ---------------------------------------------------------------------------
# Load balancer + target groups
#
# Two target groups: stable (always) and canary (used in prod only). Listener
# routes 100% to stable. The canary TG receives traffic only from synthetic
# probes hitting the canary host header rule, so canary tasks are observable
# without taking real production traffic during the bake.
#
# Spinnaker pipeline references these target groups by name:
#   image-uploader-<env>-stable
#   image-uploader-<env>-canary
# Don't rename without updating the pipeline.
# ---------------------------------------------------------------------------

resource "aws_lb" "main" {
  name               = "${local.app}-${var.env}"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = data.aws_subnets.default.ids
}

resource "aws_lb_target_group" "stable" {
  name        = "${local.app}-${var.env}-stable"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = data.aws_vpc.default.id

  health_check {
    path                = "/actuator/health"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }

  deregistration_delay = 30
}

resource "aws_lb_target_group" "canary" {
  name        = "${local.app}-${var.env}-canary"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = data.aws_vpc.default.id

  health_check {
    path                = "/actuator/health"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }

  deregistration_delay = 30
}

# Default listener routes to stable.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.stable.arn
  }
}

# Optional canary listener rule: requests to canary.<app>.<env>.example.com hit
# the canary target group. Lets you smoke-test the canary fleet without
# shifting real traffic. Comment out if you don't want it.
resource "aws_lb_listener_rule" "canary" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 100

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.canary.arn
  }

  condition {
    host_header {
      values = ["canary.${local.app}-${var.env}.example.com"]
    }
  }
}

# ---------------------------------------------------------------------------
# ECS cluster (Spinnaker creates the services + task definitions)
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

# ---------------------------------------------------------------------------
# IAM
#
# Two roles, both referenced by name from the Spinnaker pipeline JSON:
#   image-uploader-<env>-task-exec  -> ECS pulls image, fetches secrets, ships logs
#   image-uploader-<env>-task       -> the app's runtime role (S3 access, etc.)
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "ecs_task_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "task_execution" {
  name               = "${local.app}-${var.env}-task-exec"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume.json
}

resource "aws_iam_role_policy_attachment" "task_execution_managed" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Empty for now — add S3/SQS/etc. permissions here as the app grows.
resource "aws_iam_role" "task" {
  name               = "${local.app}-${var.env}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume.json
}

# ---------------------------------------------------------------------------
# CloudWatch logs
#
# One log group per environment. Spinnaker's task definition awslogs config
# in the pipeline JSON points at /ecs/image-uploader-<env>; canary stable
# share the same group but use different stream prefixes ('stable' / 'canary').
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "service" {
  name              = "/ecs/${local.app}-${var.env}"
  retention_in_days = var.env == "prod" ? 30 : 7
}
