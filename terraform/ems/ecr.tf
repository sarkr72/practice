
# force_delete lets `terraform destroy` remove the repo even when it still
# holds images. The deploy workflow pushes an image on every merge, so a
# non-prod repo is essentially never empty at teardown time — without this,
# destroy fails with RepositoryNotEmptyException. Kept false in prod so a
# stray destroy can't wipe release images.
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
