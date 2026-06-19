# ---------------------------------------------------------------------------
# Security groups
#
# Two-tier model: ALB SG accepts public HTTP, tasks SG accepts ALB only.
# The tasks SG name is referenced verbatim from the Spinnaker pipeline JSON
# (`securityGroups: ["ems-<env>-tasks"]`) — don't rename without updating it.
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
