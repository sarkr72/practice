# Use the default VPC for simplicity. Replace with a dedicated VPC module
# when you outgrow learning mode.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Sanity check: confirm CLI credentials match the expected account.
data "aws_caller_identity" "current" {}