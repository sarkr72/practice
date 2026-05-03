# ECR repository for the application's container images.
# `force_delete = true` in non-prod so `terraform destroy` doesn't fail
# when the repo still has images. Prod keeps it false so a stray destroy
# can't wipe production images.

resource "aws_ecr_repository" "app" {
  name                 = local.app
  image_tag_mutability = "MUTABLE"
  force_delete         = var.env != "prod"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# Lifecycle policy — same as ecr-lifecycle.json in the repo root.
# Keeps the last 10 tagged images, expires untagged after 7 days.
resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after 7 days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 7
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep only the last 10 tagged images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 10
        }
        action = { type = "expire" }
      },
    ]
  })
}
