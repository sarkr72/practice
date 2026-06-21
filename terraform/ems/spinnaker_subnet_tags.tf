# ---------------------------------------------------------------------------
# Spinnaker subnet tagging.
#
# Spinnaker's ECS/AWS clouddriver resolves `subnetType` by reading a tag
# called `immutable_metadata` (Netflix convention) and parsing its JSON value
# for a `purpose` field that must equal the `subnetType` string in the
# pipeline. Without these tags the resolver returns null, the VPC can't be
# derived, the security-group cache is keyed by a null VPC, and
# `SecurityGroupSelector` NPEs with "Cannot invoke Collection.size()".
#
# We tag each default-VPC subnet (already filtered to EKS-supported AZs in
# data.tf) so Spinnaker's pipeline can use `subnetType: "ecs-tasks-dev"` and
# `securityGroups: ["ems-dev-tasks"]` as Spinnaker intends, without the
# GitHub-Actions-side ID lookup workaround.
#
# These tags are added BY terraform but live ON resources terraform doesn't
# own (default VPC subnets). aws_ec2_tag is the right tool — it adds/removes
# only the named tag and never touches anything else on the subnet.
# ---------------------------------------------------------------------------

resource "aws_ec2_tag" "spinnaker_subnet_purpose" {
  for_each = toset(data.aws_subnets.default.ids)

  resource_id = each.value
  key         = "immutable_metadata"
  value = jsonencode({
    purpose = "ecs-tasks-${var.env}"
    target  = "ec2"
  })
}
