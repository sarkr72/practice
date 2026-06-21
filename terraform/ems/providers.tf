terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Backend uses workspace_key_prefix so dev and prod live at separate keys:
  #   env:/dev/ems/terraform.tfstate
  #   env:/prod/ems/terraform.tfstate
  #
  # Workflow:
  #   terraform init
  #   terraform workspace new dev   # first time only
  #   terraform workspace select dev
  #   terraform apply -var-file=envs/dev.tfvars
  #
  # Teardown is a single command — the ECS service is a Terraform resource
  # (service.tf), so destroy is graph-ordered with no manual pre-steps:
  #   terraform destroy -var-file=envs/dev.tfvars
  #
  # Pre-flight (one-time, manual): create the shared state bucket + lock table.
  # The same bucket also holds terraform/spinnaker state under a different key.
  backend "s3" {
    bucket               = "rinku-tfstate-001"
    key                  = "ems/terraform.tfstate"
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
      layer     = "platform"
    }
  }
}
