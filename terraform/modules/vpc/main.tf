# Thin wrapper around terraform-aws-modules/vpc/aws.
# Building your own VPC module from scratch is a common rookie mistake —
# the community module handles dozens of edge cases (NAT gateways, route tables,
# flow logs, VPN endpoints) that you don't want to maintain.

terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.60" }
  }
}

variable "name"               { type = string }
variable "cidr_block"          { type = string }
variable "availability_zones" { type = list(string) }

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.13"

  name = var.name
  cidr = var.cidr_block
  azs  = var.availability_zones

  # /20 subnets give ~4k IPs each — plenty of room for EKS pods
  private_subnets = [for i, _ in var.availability_zones : cidrsubnet(var.cidr_block, 4, i)]
  public_subnets  = [for i, _ in var.availability_zones : cidrsubnet(var.cidr_block, 4, i + 8)]

  enable_nat_gateway     = true
  single_nat_gateway     = true   # dev only — prod should have one per AZ
  enable_dns_hostnames   = true
  enable_dns_support     = true

  # EKS tagging convention — load balancer controller needs these to place ELBs correctly
  public_subnet_tags = {
    "kubernetes.io/role/elb" = 1
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = 1
  }
}

output "vpc_id"             { value = module.vpc.vpc_id }
output "private_subnet_ids" { value = module.vpc.private_subnets }
output "public_subnet_ids"  { value = module.vpc.public_subnets }
