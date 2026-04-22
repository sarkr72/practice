###############################################################################
# EMS prod environment — AWS
#
# Same modules as dev, sized and configured for real traffic:
#   - Multi-AZ NAT
#   - 3-broker MSK (so acks=all with min.insync.replicas=2 works)
#   - Multi-AZ RDS
#   - Replicated Redis
###############################################################################

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }

  # backend "s3" {
  #   bucket         = "ems-tfstate-123456789012"
  #   key            = "prod/terraform.tfstate"
  #   region         = "us-east-1"
  #   dynamodb_table = "ems-tfstate-locks"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "ems"
      Environment = var.environment
      ManagedBy   = "terraform"
      Owner       = var.owner
      Compliance  = "pci"   # example — shape your tags to your org's policy
    }
  }
}

locals {
  name = "ems-${var.environment}"
}

module "vpc" {
  source             = "../../modules/vpc"
  name               = local.name
  cidr_block         = var.vpc_cidr
  availability_zones = var.availability_zones
}

module "ecr" {
  source = "../../modules/ecr"
  name   = "ems"
}

module "eks" {
  source          = "../../modules/eks"
  name            = local.name
  cluster_version = "1.30"
  vpc_id          = module.vpc.vpc_id
  subnet_ids      = module.vpc.private_subnet_ids
  node_group_size = var.eks_node_size
}

module "rds" {
  source                  = "../../modules/rds"
  name                    = local.name
  vpc_id                  = module.vpc.vpc_id
  subnet_ids              = module.vpc.private_subnet_ids
  allowed_security_groups = [module.eks.node_security_group_id]
  instance_class          = var.rds_instance_class
  allocated_storage       = 100
  database_name           = "ems"
  master_username         = "ems"
}

module "redis" {
  source                  = "../../modules/elasticache"
  name                    = local.name
  vpc_id                  = module.vpc.vpc_id
  subnet_ids              = module.vpc.private_subnet_ids
  allowed_security_groups = [module.eks.node_security_group_id]
  node_type               = var.redis_node_type
  num_cache_nodes         = 2
}

module "kafka" {
  source                  = "../../modules/msk"
  name                    = local.name
  vpc_id                  = module.vpc.vpc_id
  subnet_ids              = module.vpc.private_subnet_ids
  allowed_security_groups = [module.eks.node_security_group_id]
  instance_type           = var.msk_instance_type
  number_of_broker_nodes  = 3
  kafka_version           = "3.7.x"
}
