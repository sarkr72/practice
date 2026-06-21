# App infra — instructions

Provision the EMS application's AWS platform: ALB, ECS cluster, RDS, ECR,
IAM, secrets. Idempotent — re-running just updates the diff.

> Prerequisite: Phase 1 from `docs/spinnaker-infra/INSTRUCTIONS.md` (the S3
> state bucket + DynamoDB lock table) must already exist. If it doesn't, do
> that one phase first — both Terraform roots share the same backend.

---

## Phase 1 — (Already done if you set up spinnaker-infra)

Skip if `aws s3api list-buckets | findstr rinku-tfstate-001` finds it.
Otherwise:

```powershell
aws s3api create-bucket --bucket rinku-tfstate-001 --region us-east-1
aws s3api put-bucket-versioning --bucket rinku-tfstate-001 `
  --versioning-configuration Status=Enabled
aws s3api put-public-access-block --bucket rinku-tfstate-001 `
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws dynamodb create-table --table-name terraform-locks `
  --attribute-definitions AttributeName=LockID,AttributeType=S `
  --key-schema AttributeName=LockID,KeyType=HASH `
  --billing-mode PAY_PER_REQUEST --region us-east-1
```

---

## Phase 2 — Apply the EMS platform

```powershell
$ACCOUNT = (aws sts get-caller-identity --query Account --output text)
echo "Account: $ACCOUNT"     # MUST be 12 digits, not blank

cd E:\projects\practice\terraform\ems

# write the account into a gitignored tfvars file
"aws_account_id = `"$ACCOUNT`"" | Out-File -Encoding ascii account.auto.tfvars
Get-Content account.auto.tfvars     # verify it has the digits

terraform init

# Workspaces let dev / perf / prod state files live separately.
# First time you ever apply, "new"; every other time, "select".
terraform workspace new dev 2>$null
terraform workspace select dev

terraform apply -var-file=envs\dev.tfvars        # review the plan, type: yes
```

Wait ~5–10 min (RDS takes the longest). At the end you'll see the outputs:

```powershell
terraform output             # full list

# the ones you'll actually use:
$ALB           = (terraform output -raw alb_url)
$ECS_CLUSTER   = (terraform output -raw ecs_cluster)
$ECR_URI       = (terraform output -raw ecr_repository_uri)
$DB_ENDPOINT   = (terraform output -raw db_primary_endpoint)
$SG_TASKS      = (terraform output -raw tasks_security_group_id)
$TG_STABLE_ARN = (terraform output -raw target_group_stable_arn)

echo "App will be reachable at:  $ALB"
echo "ECS cluster:               $ECS_CLUSTER"
echo "ECR repo URI:              $ECR_URI"
echo "DB endpoint:               $DB_ENDPOINT"
```

> If `terraform output -raw alb_url` returns blank, the apply didn't
> complete. See `DEBUG.md → Phase 2 — Terraform`.

---

## Phase 3 — Verify the platform

The ECS cluster is *empty* on purpose (the deploy workflow creates the
service). But everything else should be live. Quick sanity checks:

```powershell
# 1. ALB exists and responds (will be 503 — nothing's deployed yet)
curl "$ALB"

# 2. ECS cluster exists and is empty
aws ecs list-services --cluster ems-dev
# expect:  { "serviceArns": [] }

# 3. RDS is ready
aws rds describe-db-instances --query "DBInstances[?DBInstanceIdentifier=='ems-dev-primary'].DBInstanceStatus" --output text
# expect:  available

# 4. ECR is ready
aws ecr describe-repositories --repository-names ems --query "repositories[0].repositoryUri" --output text
# expect:  <account>.dkr.ecr.us-east-1.amazonaws.com/ems

# 5. Secrets actually got populated
aws ssm get-parameter --name /dev/ems/DB_HOST --query "Parameter.Value" --output text
aws secretsmanager describe-secret --secret-id /dev/ems/DB_PASSWORD --query "Name" --output text
```

All five should return real values. If any fails, see `DEBUG.md`.

---

## Phase 4 (optional) — Apply additional environments

```powershell
# perf
terraform workspace new perf
terraform workspace select perf
terraform apply -var-file=envs\perf.tfvars

# prod
terraform workspace new prod
terraform workspace select prod
terraform apply -var-file=envs\prod.tfvars
```

Each env gets:
- Its own state file at `s3://rinku-tfstate-001/env:/<env>/ems/...`.
- Its own ALB / cluster / RDS / etc. named `ems-<env>-...`.
- Its own SSM paths `/<env>/ems/...`.

---

## Phase 5 — Tear down (end of day)

Teardown is **two layers, app first** (the app layer holds the service; the
platform layer holds the cluster/ALB/RDS it depends on). The helper script does
both in order:

```powershell
cd E:\projects\practice
bash scripts/deploy.sh dev destroy        # destroys ems-app, then ems
# or to keep the database and just stop the app:
bash scripts/deploy.sh dev destroy-app    # destroys ONLY ems-app
```

By hand it's the reverse of apply:

```powershell
cd terraform\ems-app
terraform workspace select dev
terraform destroy -var-file=envs\dev.tfvars   # app first

cd ..\ems
terraform workspace select dev
terraform destroy -var-file=envs\dev.tfvars   # then platform
```

Why this is clean, no manual pre-steps:

- **The ECS service** (`ems-dev`) is a Terraform resource in the `ems-app`
  layer. Destroying that layer drains its tasks first and waits for the Fargate
  ENIs to detach. Then the platform destroy drops the tasks security group and
  cluster with no `ClusterContainsServicesException` / `DependencyViolation` —
  and no destroy-time provisioner. (CD-registered task-def revisions just go
  INACTIVE; they block nothing and cost $0.)
- **"Stop the app, keep the data"** is now a first-class operation:
  `destroy-app` removes only the `ems-app` layer; the RDS database in the
  platform layer's separate state is untouched.
- **The ECR repo** still holds images at teardown. `ecr.tf` sets
  `force_delete` for non-prod (`var.env != "prod"`) so destroy removes it
  cleanly; prod keeps `force_delete = false` so a stray destroy can't wipe
  release images (empty it by hand first if you really mean it).
- **In prod**, the platform destroy intentionally is NOT one command: RDS
  (`deletion_protection`) and the ALB (`enable_deletion_protection`) are
  protected. Flip both to `false` and apply before you can destroy the prod
  platform — the guardrail is the point. (The `ems-app` layer still destroys
  cleanly in prod.)

Order matters if Spinnaker is also running: tear down `terraform/ems`
*before* `terraform/spinnaker`, because spinnaker references the ems
networking via the GitHub OIDC role's IAM policy. (In practice it works
either way — terraform just may take longer to settle dependencies.)

> The S3 state bucket + DynamoDB lock table from Phase 1 are NOT destroyed —
> they're reusable + ~free. Delete by hand only if you're truly done with
> the project.

---

## Quick "redo from scratch" checklist

1. *(If first time)* Phase 1 — S3 + DynamoDB.
2. Phase 2 — `terraform apply -var-file=envs/dev.tfvars`.
3. Phase 3 — verify ALB / RDS / ECR.
4. Continue to `docs/app-deploy/INSTRUCTIONS.md` to put a running app on top.

That's the whole platform layer. The deploy is in the next folder.
