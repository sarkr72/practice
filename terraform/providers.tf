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
  #   env:/dev/image-uploader/terraform.tfstate
  #   env:/prod/image-uploader/terraform.tfstate
  #
  # Workflow:
  #   terraform init
  #   terraform workspace new dev   # first time only
  #   terraform workspace select dev
  #   terraform apply -var-file=variables/dev.tfvars
  #
  # Pre-flight (one-time, manual): create the bucket + lock table:
  #   aws s3api create-bucket --bucket rinku-tfstate-imageuploader-001 --region us-east-1
  #   aws s3api put-bucket-versioning --bucket rinku-tfstate-imageuploader-001 \
  #     --versioning-configuration Status=Enabled
  #   aws dynamodb create-table --table-name terraform-locks \
  #     --attribute-definitions AttributeName=LockID,AttributeType=S \
  #     --key-schema AttributeName=LockID,KeyType=HASH --billing-mode PAY_PER_REQUEST
  backend "s3" {
    bucket         = "rinku-tfstate-imageuploader-001"
    key            = "image-uploader/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "terraform-locks"
    encrypt        = true
    workspace_key_prefix = "env"
  }
}

provider "aws" {
  region              = var.aws_region
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      project   = "image-uploader"
      env       = var.env
      managedBy = "terraform"
      owner     = "rinku"
    }
  }
}
