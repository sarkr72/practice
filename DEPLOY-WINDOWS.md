# End-to-end deployment runbook (Windows)

Stand up the infra, install Spinnaker, then get the **push-to-main → auto-deploy**
loop working. Every command is PowerShell. Run them from a regular PowerShell
window (not cmd). Copy blocks one at a time and read the comments.

```
git push origin main
   │
   ▼
GitHub Actions   build + test, Jib pushes image to ECR (auth via OIDC, no keys)
   │  POST webhook
   ▼
Spinnaker (on EKS)   createServerGroup (red/black)
   ▼
ECS Fargate   new task revision goes live behind the ALB
```

**Ownership:** Terraform owns the immutable platform (EKS for Spinnaker, plus
the EMS ALB / ECS cluster / RDS / ECR / IAM / secrets). Spinnaker owns the ECS
**services and task definitions** — it creates them on the first pipeline run.

> **Cost warning.** This runs an EKS cluster (2× t3.large), an RDS instance, an
> ALB, and 2 network load balancers. Ballpark **$10–15/day** left running. Tear
> down with the Phase 9 steps when you're done for the day.

---

## Phase 0 — Install the tools (one time)

Use [winget](https://learn.microsoft.com/windows/package-manager/) (built into
Windows 10/11):

```powershell
winget install --id Hashicorp.Terraform -e
winget install --id Amazon.AWSCLI -e
winget install --id Kubernetes.kubectl -e
winget install --id Helm.Helm -e
winget install --id Git.Git -e
winget install --id Eclipse.Temurin.21.JDK -e   # only needed if you build locally
```

Close and reopen PowerShell so the new tools are on `PATH`, then verify:

```powershell
terraform version
aws --version
kubectl version --client
git --version
```

Configure AWS credentials (an IAM user/SSO profile with admin-ish rights for
the initial bootstrap):

```powershell
aws configure
aws sts get-caller-identity      # confirm account + identity
```

Get your account ID into a variable you'll reuse this session:

```powershell
$ACCOUNT = (aws sts get-caller-identity --query Account --output text)
$REGION  = "us-east-1"
echo $ACCOUNT
```

Clone the repo if you haven't:

```powershell
git clone https://github.com/sarkr72/practice.git
cd practice
```

---

## Phase 1 — Bootstrap the Terraform backend (one time, manual)

Terraform stores its state in S3 with a DynamoDB lock table. These must exist
**before** `terraform init`, so you create them by hand once. Names are already
wired into `terraform/ems/providers.tf` and `terraform/spinnaker/providers.tf`.

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

> The bucket name is globally unique. If `rinku-tfstate-001` is taken, pick your
> own and change the `bucket =` line in both `providers.tf` files plus
> `persistence_bucket_name` in `terraform/spinnaker/variables.tf`.

---

## Phase 2 — Provision the Spinnaker control plane (EKS + S3 + IAM + GitHub OIDC)

```powershell
cd terraform\spinnaker

# Account ID for this root (gitignored — never committed)
"aws_account_id = `"$ACCOUNT`"" | Out-File -Encoding ascii account.auto.tfvars

terraform init
terraform apply        # review the plan, type: yes
```

This creates: the EKS cluster, its OIDC provider, the IRSA role Spinnaker
assumes, the S3 persistence bucket, **and** the GitHub Actions OIDC role
(`github_oidc.tf`). Takes ~15–20 min for EKS.

Capture the outputs you'll need later:

```powershell
$ROLE_ARN   = (terraform output -raw github_actions_role_arn)   # for GitHub
$SPIN_ROLE  = (terraform output -raw spinnaker_role_arn)
$BUCKET     = (terraform output -raw persistence_bucket)
echo "GitHub role:    $ROLE_ARN"
echo "Spinnaker role: $SPIN_ROLE"
echo "Bucket:         $BUCKET"
```

Point kubectl at the new cluster:

```powershell
aws eks update-kubeconfig --name spinnaker --region us-east-1
kubectl get nodes        # should list 2 Ready nodes
```

---

## Phase 3 — Install Spinnaker on the cluster

### 3a. Install the Spinnaker Operator

```powershell
# from terraform\spinnaker
kubectl apply -f manifests\operator\
kubectl -n spinnaker-operator rollout status deploy/spinnaker-operator
```

(If `manifests\operator\` only contains the namespace + a README, follow the
upstream operator install in `manifests\operator\README.md` — it's a single
`kubectl apply -k` against the published operator kustomize bundle.)

### 3b. Render the SpinnakerService manifest (PowerShell — no envsubst needed)

```powershell
cd manifests\spinnaker

# Read the template and substitute the placeholders
$svc = Get-Content spinnakerservice.yaml -Raw
$svc = $svc.Replace('__AWS_ACCOUNT_ID__',     $ACCOUNT)
$svc = $svc.Replace('__AWS_REGION__',          $REGION)
$svc = $svc.Replace('__SPINNAKER_ROLE_ARN__',  $SPIN_ROLE)
$svc = $svc.Replace('__PERSISTENCE_BUCKET__',  $BUCKET)
$svc | Out-File -Encoding ascii spinnakerservice.rendered.yaml

kubectl apply -f spinnakerservice.rendered.yaml
```

### 3c. Wait for it to come up (5–10 min)

```powershell
kubectl -n spinnaker get spinnakerservice spinnaker -w
# Ctrl+C once status settles, then:
kubectl -n spinnaker get pods
```

When `spin-deck` and `spin-gate` pods are `Running`, get their public hostnames
(these are AWS network load balancers):

```powershell
$DECK = kubectl -n spinnaker get svc spin-deck -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
$GATE = kubectl -n spinnaker get svc spin-gate -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
echo "Spinnaker UI:   http://$($DECK):9000"
echo "Spinnaker Gate: http://$($GATE):8084"
```

Open `http://<deck-host>:9000` in a browser. No login (auth is disabled — see
the warning below).

> **Security:** auth is off. Lock down the NLB security groups to **your IP
> only** before doing anything else. In the EC2 console → Load Balancers →
> the two `spin-*` NLBs → their security groups → restrict inbound to your IP.

---

## Phase 4 — Provision the EMS application platform (ALB, ECS, RDS, ECR, secrets)

```powershell
cd ..\..\..\ems      # -> terraform\ems

"aws_account_id = `"$ACCOUNT`"" | Out-File -Encoding ascii account.auto.tfvars

terraform init
terraform workspace new dev          # first time only
terraform apply -var-file=envs\dev.tfvars    # review, type: yes
```

Save the outputs Spinnaker and you will reference:

```powershell
$ALB = (terraform output -raw alb_url)
terraform output            # full list: cluster name, target groups, role names, ECR URI
echo "App will be reachable at: $ALB"
```

Everything the Spinnaker pipeline references by name (target groups, IAM roles,
security group, ECS cluster, log group) is created here. Don't rename one
without the other.

---

## Phase 5 — Configure Spinnaker (one time)

In the Deck UI (`http://<deck-host>:9000`):

1. **Applications → Create Application**
   - Name: `ems`
   - Owner email: your email
   - Cloud Providers: tick **Amazon Web Services** and **Amazon ECS**

Then import the pipeline. Install the `spin` CLI (PowerShell):

```powershell
$spinDir = "$env:USERPROFILE\bin"
New-Item -ItemType Directory -Force -Path $spinDir | Out-Null
$ver = (Invoke-RestMethod "https://storage.googleapis.com/spinnaker-artifacts/spin/latest").Trim()
Invoke-WebRequest "https://storage.googleapis.com/spinnaker-artifacts/spin/$ver/windows/amd64/spin.exe" `
  -OutFile "$spinDir\spin.exe"
$env:PATH = "$spinDir;$env:PATH"

# Tell spin where Gate is
"gate:`n  endpoint: http://$($GATE):8084" | Out-File -Encoding ascii "$env:USERPROFILE\.spin\config"

# from the repo root:
cd ..\..       # -> repo root
spin pipeline save --file spinnaker\pipelines\ems-deploy-cicd.json
```

Refresh the UI → **ems → Pipelines** → you should see `ems-deploy-cicd`.

---

## Phase 6 — Wire GitHub Actions (the auto-deploy trigger)

The workflow `.github/workflows/deploy.yml` is already in the repo. It needs
three **variables** and one **secret** on GitHub.

Go to **GitHub → your repo → Settings → Secrets and variables → Actions**:

**Variables tab → New repository variable** (×3):

| Name                | Value                                              |
|---------------------|----------------------------------------------------|
| `AWS_ROLE_ARN`      | the `$ROLE_ARN` from Phase 2                        |
| `AWS_REGION`        | `us-east-1`                                         |
| `SPINNAKER_GATE_URL`| `http://<gate-host>:8084`  (the `$GATE` from 3c, **no trailing slash**) |

**Secrets tab → New repository secret** (×1):

| Name                      | Value                                        |
|---------------------------|----------------------------------------------|
| `SPINNAKER_WEBHOOK_TOKEN` | any string, e.g. a GUID. (Not enforced while `webhooks.trust.enabled: false`, but the workflow sends it.) |

> The Gate LoadBalancer SG must allow inbound from GitHub's runners for the
> webhook to land. For a locked-down learning setup, either (a) temporarily
> widen the Gate NLB SG to `0.0.0.0/0` on port 8084 only while testing, or
> (b) trigger the Spinnaker pipeline manually from the UI and skip the webhook.
> GitHub publishes its runner IP ranges at `https://api.github.com/meta` if you
> want to scope it tightly.

---

## Phase 7 — Deploy by pushing to main

From your laptop:

```powershell
cd practice
# make a change, e.g. edit README, then:
git add .
git commit -m "test: trigger first auto-deploy"
git push origin main
```

Watch the loop:

1. **GitHub → Actions tab** — the `deploy` workflow runs: test → Jib build/push
   → "Trigger Spinnaker pipeline". ~3–6 min.
2. **Spinnaker UI → ems → Pipelines** — an execution starts. For a `main` push
   it runs Deploy Dev → Smoke Dev (and the prod stages gate on Manual Judgment).
3. **AWS Console → ECS → `ems-dev` cluster** — a new service/task revision
   appears and reaches `RUNNING`.
4. **Verify it's live:**
   ```powershell
   curl "$ALB/actuator/health"      # -> {"status":"UP"}
   ```

That's the full CI/CD loop. Every subsequent `git push origin main` repeats
steps 1–4 automatically.

---

## Phase 8 — Rollback

In the Spinnaker UI → **ems → Clusters**, select the previous server group →
**Enable**, then disable the bad one. Red/black keeps the prior version around
(`maxRemainingAsgs=2`) specifically for this one-click rollback. Nothing to
script.

---

## Phase 9 — Tear down (stop the meter)

Reverse order. App platform first, then the control plane.

```powershell
# EMS platform
cd terraform\ems
terraform destroy -var-file=envs\dev.tfvars     # type: yes

# Spinnaker (delete the CR first so its NLBs are removed cleanly)
kubectl -n spinnaker delete spinnakerservice spinnaker
cd ..\spinnaker
terraform destroy                                # type: yes
```

> If `terraform destroy` on the spinnaker root hangs on the VPC/subnets, it's
> usually leftover ELBs from the SpinnakerService — make sure the `kubectl
> delete spinnakerservice` above completed and the `spin-*` load balancers are
> gone from the EC2 console, then re-run destroy.

The S3 state bucket and DynamoDB lock table from Phase 1 are intentionally not
destroyed — they're cheap and reusable. Delete them by hand if you're truly done.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Actions step "Configure AWS credentials" fails with `Not authorized to perform sts:AssumeRoleWithWebIdentity` | `AWS_ROLE_ARN` variable wrong, or you pushed from a branch other than `main` (trust is scoped to `main`). |
| Jib push fails `denied: not authorized` | The `github-actions-ems` role's ECR policy is in `terraform/spinnaker` — make sure Phase 2 applied cleanly. |
| Spinnaker execution never starts after Actions succeeds | `SPINNAKER_GATE_URL` wrong, or the Gate NLB SG is blocking GitHub runners (see Phase 6 note). Test the webhook reachability or trigger manually in the UI. |
| ECS task stuck `PENDING` then dies | Check **CloudWatch → /ecs/ems-dev** logs. Usually a DB connection failure — confirm the RDS SG allows the tasks SG and the `DB_*` SSM params resolved. |
| `terraform apply` errors `bucket already exists` | Someone has that global S3 name. Rename per the Phase 1 note. |
