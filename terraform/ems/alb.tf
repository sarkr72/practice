# ---------------------------------------------------------------------------
# Load balancer + target groups
#
# Two target groups: stable (always) and canary (used in prod only). Default
# listener routes 100% to stable. The canary TG receives traffic only from
# synthetic probes hitting the canary host-header rule, so canary tasks are
# observable without taking real production traffic during the bake.
#
# Spinnaker pipeline references these target groups by name:
#   ems-<env>-stable
#   ems-<env>-canary
# Don't rename without updating the pipeline JSON.
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

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.stable.arn
  }
}

# Optional: requests to canary.<app>-<env>.example.com hit the canary target
# group. Lets you smoke-test the canary fleet without shifting real traffic.
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
