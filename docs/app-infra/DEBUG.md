# App infra — debug guide

Every snag we hit (or might reasonably hit) while applying `terraform/ems`,
with the **why** so you can reason about it instead of just copy-pasting.

---

## Phase 1 — Backend bootstrap

### `BucketAlreadyExists` from `aws s3api create-bucket`
**Why:** S3 names are globally unique. Someone else has the name.

**Fix:** pick a different name. Update three places:
- `terraform/spinnaker/providers.tf` → `backend "s3" { bucket = "..." }`
- `terraform/ems/providers.tf` → same
- `terraform/spinnaker/variables.tf` → `persistence_bucket_name` default

---

### `Table already exists` for `terraform-locks`
**Why:** You're rerunning the bootstrap — the table exists from a previous
run.

**Fix:** ignore. The table is reused safely.

---

## Phase 2 — Terraform apply

### Terraform keeps prompting `Enter a value: var.aws_account_id`
**Why:** `account.auto.tfvars` doesn't exist or is empty. Common cause: your
`$ACCOUNT` variable was blank when you piped it to `Out-File` (you forgot
`aws sts get-caller-identity` first).

**Fix:**
```powershell
$ACCOUNT = (aws sts get-caller-identity --query Account --output text)
echo $ACCOUNT     # MUST show 12 digits
"aws_account_id = `"$ACCOUNT`"" | Out-File -Encoding ascii account.auto.tfvars
Get-Content account.auto.tfvars     # verify the digits are in there
```

Or set it as an env var instead — no file needed:
```powershell
$env:TF_VAR_aws_account_id = $ACCOUNT
```

---

### `UnsupportedAvailabilityZoneException ... us-east-1e` while creating ECS / RDS
**Why:** Same story as for EKS. The default VPC has a subnet in `us-east-1e`
that some AWS services don't support.

**Fix (already in repo):** `terraform/ems/data.tf` filters out `us-east-1e`.
If you use a different region/VPC and hit this for a different zone, exclude
that one.

---

### `Error acquiring the state lock`
**Why:** You Ctrl+C'd a previous `terraform apply` before it released the
DynamoDB lock.

**Fix:** the error prints the lock ID. Run:
```powershell
terraform force-unlock <lock-id>
```
Type `yes`. Safe as long as no other apply is actually running.

---

### Workspace error: `Workspace "dev" doesn't exist`
**Why:** First-time apply for this env — you haven't created the workspace
yet.

**Fix:** `terraform workspace new dev` then `terraform workspace select dev`.
Subsequent applies just need `terraform workspace select dev`.

---

### `Workspace "dev" already exists` on `terraform workspace new dev`
**Why:** You already created it before.

**Fix:** `terraform workspace select dev`. The `new` command in the runbook
has `2>$null` to suppress this exact error so the script is idempotent.

---

### `random_password.db_master` keeps regenerating on every apply
**Why:** This shouldn't happen — the `lifecycle { ignore_changes }` blocks
in `rds.tf` prevent it. If you're seeing this, you've edited that file.

**Fix:** the password is stored in Secrets Manager via
`aws_secretsmanager_secret_version.db_password` and the RDS instance has
`ignore_changes = [password]`. Don't touch those blocks.

---

### `InvalidSubnet: Value (subnet-xxx) for parameter subnets is invalid`
**Why:** The default-VPC subnet got deleted, or you're in a region with no
default VPC.

**Fix:** in a new region, create a default VPC: `aws ec2 create-default-vpc`.
Otherwise, the data source in `data.tf` will repopulate on next apply.

---

### RDS apply hangs at "still creating..."
**Why:** RDS provisioning genuinely takes 5–10 minutes — this is normal.

**Fix:** wait. If it's stuck >15 min, check the AWS console for the actual
RDS state (`creating` → `backing-up` → `available`). If it failed, the
console will show the reason (storage encryption disabled in region, IAM
issue, etc.).

---

## Phase 3 — Verification

### `aws ecs list-services --cluster ems-dev` returns `serviceArns: []`
**Why:** Correct behavior. The cluster is empty by design. The deploy
workflow creates the service on first push.

**Fix:** none needed — this is the expected state of app-infra alone.

---

### `curl $ALB` returns 503 Service Temporarily Unavailable
**Why:** Correct behavior. The ALB is up but has no healthy task registered.

**Fix:** none needed. Push code via `docs/app-deploy/INSTRUCTIONS.md` Phase 2
and the 503 will become 200.

---

### `aws ssm get-parameter --name /dev/ems/DB_HOST` → `ParameterNotFound`
**Why:** `terraform apply` partially failed — `rds.tf` didn't complete, so
`secrets.tf`'s `aws_ssm_parameter.db_host` never got its value
(`aws_db_instance.primary.address`).

**Fix:** re-run `terraform apply -var-file=envs/dev.tfvars`. Terraform reads
state, sees what's missing, completes the rest.

---

### `aws rds describe-db-instances` → `DBInstanceStatus: backing-up` for 30+ min
**Why:** First-time backup runs after creation — also normal, takes a while
for larger storage.

**Fix:** wait. Connections to the DB work fine during `backing-up`.

---

## Cost / leftover-resource issues

### After `terraform destroy`, AWS bill still shows charges
**Why:** Most likely one of:
- The destroy was interrupted, leftover ELBs from spinnaker still running.
- The S3 state bucket (intentionally kept).
- Snapshots auto-created on RDS deletion in prod (`final_snapshot_identifier`).

**Fix:** in the AWS Console:
1. EC2 → Load Balancers → delete any leftover ELBs.
2. RDS → Snapshots → delete `ems-prod-final-...` if you don't need them
   (each one stores the DB at deletion time — costs by GB).
3. S3 → if you're done with the project, empty + delete `rinku-tfstate-001`.

```powershell
# List anything still running:
aws elbv2 describe-load-balancers --query "LoadBalancers[].LoadBalancerName"
aws rds describe-db-instances --query "DBInstances[].DBInstanceIdentifier"
aws ecs list-clusters
aws eks list-clusters
```

If those all return empty arrays, you're not being billed for compute.

---

### `terraform destroy` says "Error: error deleting RDS DB Instance"
**Why:** RDS deletion is blocked by `deletion_protection = true` (prod only)
or because the final snapshot ID has a placeholder issue.

**Fix:**
```powershell
# disable deletion protection first
aws rds modify-db-instance --db-instance-identifier ems-prod-primary `
  --no-deletion-protection --apply-immediately

# then re-run destroy
terraform destroy -var-file=envs\prod.tfvars
```

---

### `terraform destroy` says "DependencyViolation: subnet has dependent objects"
**Why:** Spinnaker (on the EKS cluster) is using the subnets via its NLBs
and security groups. Terraform's `ems` root can't release the subnets while
spinnaker is still attached.

**Fix:** tear down Spinnaker first.
```powershell
kubectl -n spinnaker delete spinnakerservice spinnaker
# wait ~2 min for AWS to fully clean up the NLBs
cd ..\spinnaker
terraform destroy
# THEN tear down ems
cd ..\ems
terraform destroy -var-file=envs\dev.tfvars
```

---

## "Did the Spinnaker subnet tag actually apply?" check

```powershell
$VPC_ID = (aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)
aws ec2 describe-subnets `
  --filters "Name=vpc-id,Values=$VPC_ID" `
            "Name=tag-key,Values=immutable_metadata" `
  --query "Subnets[].{Id:SubnetId,AZ:AvailabilityZone,Tag:Tags[?Key=='immutable_metadata']|[0].Value}" `
  --output table
```

You should see 5 subnets (one per AZ except `1e`), each with the
`immutable_metadata = {"purpose":"ecs-tasks-dev","target":"ec2"}` tag.

If this returns nothing, `terraform/ems/spinnaker_subnet_tags.tf` didn't
apply — re-run `terraform apply`.

---

## One sanity-check command that catches most apply issues

```powershell
terraform plan -var-file=envs\dev.tfvars
```

Run it any time you're unsure of state. `plan` is read-only — it won't
change anything, just shows the diff between your `.tf` files and what's
actually deployed. If `plan` says "No changes", you're in sync.
