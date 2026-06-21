terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # App layer state — separate key from the platform layer (ems/terraform.tfstate).
  # This is the whole point of the split: `terraform destroy` here removes ONLY
  # the ECS service, never the cluster / ALB / RDS that live in the platform
  # layer. Same bucket + lock table + workspace_key_prefix as the platform so
  # dev/perf/prod stay isolated:
  #   env/dev/ems/app/terraform.tfstate
  #   env/prod/ems/app/terraform.tfstate
  backend "s3" {
    bucket               = "rinku-tfstate-001"
    key                  = "ems/app/terraform.tfstate"
    region               = "us-east-1"
    dynamodb_table       = "terraform-locks"
    encrypt              = true
    workspace_key_prefix = "env"
  }
}

provider "aws" {
  region              = var.aws_region
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      project   = "ems"
      env       = var.env
      managedBy = "terraform"
      owner     = "rinku"
      layer     = "app"
    }
  }
}
