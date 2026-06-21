# Read the platform layer's outputs (cluster, ALB target group, IAM roles,
# tasks SG, subnets, log group) instead of creating any of that here. This is
# the seam between the two layers: the app depends on the platform, never the
# reverse — which is exactly why you destroy app first, platform second.
#
# The key is built explicitly from the current workspace so dev reads dev's
# platform state and prod reads prod's. The platform layer uses
# workspace_key_prefix = "env" with key "ems/terraform.tfstate", so a named
# workspace's object lands at: env/<workspace>/ems/terraform.tfstate.
data "terraform_remote_state" "platform" {
  backend = "s3"

  config = {
    bucket = "rinku-tfstate-001"
    key    = "env/${terraform.workspace}/ems/terraform.tfstate"
    region = "us-east-1"
  }
}
