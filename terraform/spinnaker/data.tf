# Reuse the default VPC for simplicity. Spinnaker on its own VPC is the
# right call for production; out of scope for this learning setup.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_caller_identity" "current" {}
