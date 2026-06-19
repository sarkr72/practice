terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # Shares the rinku-tfstate-001 bucket with terraform/ems but under a
  # different key. Pre-flight (one-time, if not already done for ems):
  #   aws s3api create-bucket --bucket rinku-tfstate-001 --region us-east-1
  #   aws s3api put-bucket-versioning --bucket rinku-tfstate-001 \
  #     --versioning-configuration Status=Enabled
  #   aws dynamodb create-table --table-name terraform-locks \
  #     --attribute-definitions AttributeName=LockID,AttributeType=S \
  #     --key-schema AttributeName=LockID,KeyType=HASH --billing-mode PAY_PER_REQUEST
  backend "s3" {
    bucket         = "rinku-tfstate-001"
    key            = "spinnaker/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "terraform-locks"
    encrypt        = true
  }
}

provider "aws" {
  region              = var.aws_region
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      project   = "spinnaker"
      managedBy = "terraform"
      owner     = "rinku"
    }
  }
}
