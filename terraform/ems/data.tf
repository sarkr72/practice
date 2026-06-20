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

  # Match the spinnaker root: exclude us-east-1e. ECS/ALB/RDS tolerate it, but
  # keeping both roots on the same AZ set avoids surprises and stays consistent.
  filter {
    name   = "availability-zone"
    values = ["us-east-1a", "us-east-1b", "us-east-1c", "us-east-1d", "us-east-1f"]
  }
}

# Sanity check: confirm CLI credentials match the expected account.
data "aws_caller_identity" "current" {}