# Persistence bucket for Spinnaker. front50 (application/pipeline configs)
# and clouddriver (account/cluster cache snapshots) both read/write here.
# Single bucket per Spinnaker install is the standard layout.

resource "aws_s3_bucket" "persistence" {
  bucket        = var.persistence_bucket_name
  force_destroy = false
}

resource "aws_s3_bucket_versioning" "persistence" {
  bucket = aws_s3_bucket.persistence.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "persistence" {
  bucket = aws_s3_bucket.persistence.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "persistence" {
  bucket                  = aws_s3_bucket.persistence.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
