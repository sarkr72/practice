# Spinnaker infra — instructions

Stand up the Spinnaker control plane on EKS, from a Windows machine. Every
command is PowerShell. Read the comment on each block before running it.

> If something fails partway, **do not panic** — see `DEBUG.md` in this
> folder. Every error we hit during the live build is documented there with
> the actual fix.

---

## Phase 0 — Install tools (one time, on your laptop)

```powershell
winget install --id Hashicorp.Terraform -e
winget install --id Amazon.AWSCLI -e
winget install --id Kubernetes.kubectl -e
winget install --id Git.Git -e

# close + reopen PowerShell so the new tools are on PATH
terraform version
aws --version
kubectl version --client
git --version
```

Configure AWS (an IAM user with admin perms for the initial bootstrap):

```powershell
aws configure
aws sts get-caller-identity      # should print your account
```

Get your account ID into a session variable you'll reuse:

```powershell
$ACCOUNT = (aws sts get-caller-identity --query Account --output text)
$REGION  = "us-east-1"
echo "Account: $ACCOUNT  Region: $REGION"
```

Clone the repo if you haven't:

```powershell
git clone https://github.com/sarkr72/practice.git
cd practice
```

---

## Phase 1 — Bootstrap the Terraform backend (one time, manual)

Terraform's state lives in S3 with a DynamoDB lock table. Both must exist
**before** `terraform init`, so you create them by hand once.

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

> If `rinku-tfstate-001` is taken (S3 bucket names are global), pick your own
> and change the `bucket = ...` line in both `providers.tf` files plus
> `persistence_bucket_name` in `terraform/spinnaker/variables.tf`.

---

## Phase 2 — Provision the Spinnaker control plane

```powershell
cd terraform\spinnaker

# Account ID for this root (gitignored — never committed)
"aws_account_id = `"$ACCOUNT`"" | Out-File -Encoding ascii account.auto.tfvars

terraform init
terraform apply        # review the plan, type: yes
```

Wait ~15–20 min (EKS takes the longest). When done, capture the outputs:

```powershell
$ROLE_ARN   = (terraform output -raw github_actions_role_arn)
$SPIN_ROLE  = (terraform output -raw spinnaker_role_arn)
$BUCKET     = (terraform output -raw persistence_bucket)
echo "GitHub role:        $ROLE_ARN"
echo "Spinnaker IRSA role: $SPIN_ROLE"
echo "Persistence bucket:  $BUCKET"
```

> All three must print real values. If `$ROLE_ARN` or `$SPIN_ROLE` is blank,
> the apply failed — see `DEBUG.md → Phase 2 — Terraform`.

Point kubectl at the new cluster:

```powershell
aws eks update-kubeconfig --name spinnaker --region us-east-1
kubectl get nodes        # should list 2 Ready nodes
```

---

## Phase 3 — Install Spinnaker onto the cluster

> **Big picture:** two installs in order.
> 1. The **Operator** — a robot that knows how to install/run Spinnaker.
> 2. The **SpinnakerService** — the "order form" you hand the robot.
>
> Installing the Operator alone does NOT give you Spinnaker. You must also
> apply the SpinnakerService (3c). Skipping 3c is the #1 mistake.

### 3a — Install the Spinnaker Operator

```powershell
cd manifests\operator

# 1. create the namespace (room) the operator lives in
kubectl apply -f 00-namespace.yaml

# 2. download the operator release. Use curl.exe (NOT plain "curl" — in
#    PowerShell that's a different alias).
$OPERATOR_VERSION = "1.4.0"
curl.exe -L "https://github.com/armory/spinnaker-operator/releases/download/v$OPERATOR_VERSION/manifests.tgz" -o manifests.tgz
tar -xzf manifests.tgz        # extracts a deploy\ folder

# 3. install: CRDs first, then the operator itself. Use "cluster" (NOT
#    "kubernetes" — that folder doesn't exist) so the operator can watch
#    the separate "spinnaker" namespace.
kubectl apply -f deploy\crds\
kubectl apply -n spinnaker-operator -f deploy\operator\cluster\

# 4. wait until the operator pod is 2/2 Running before continuing
kubectl -n spinnaker-operator rollout status deploy/spinnaker-operator
kubectl -n spinnaker-operator get pods
```

### 3b — Create the Spinnaker namespace + render values

```powershell
kubectl create namespace spinnaker

# back to terraform\spinnaker to read terraform outputs
cd ..\..

$ACCOUNT   = (aws sts get-caller-identity --query Account --output text)
$REGION    = "us-east-1"
$SPIN_ROLE = (terraform output -raw spinnaker_role_arn)
$BUCKET    = (terraform output -raw persistence_bucket)

# SANITY CHECK — none of these may be blank
echo "ACCOUNT=$ACCOUNT  REGION=$REGION"
echo "ROLE=$SPIN_ROLE"
echo "BUCKET=$BUCKET"
```

### 3c — Render + apply the SpinnakerService

```powershell
cd manifests\spinnaker

# PowerShell substitution (no envsubst on Windows)
$svc = Get-Content spinnakerservice.yaml -Raw
$svc = $svc.Replace('__AWS_ACCOUNT_ID__',     $ACCOUNT)
$svc = $svc.Replace('__AWS_REGION__',          $REGION)
$svc = $svc.Replace('__SPINNAKER_ROLE_ARN__',  $SPIN_ROLE)
$svc = $svc.Replace('__PERSISTENCE_BUCKET__',  $BUCKET)
$svc | Out-File -Encoding ascii spinnakerservice.rendered.yaml

kubectl apply -f spinnakerservice.rendered.yaml
```

Expected: `spinnakerservice.spinnaker.io/spinnaker created`.

> Got `failed calling webhook ... context deadline exceeded`? That's the EKS
> control-plane → node webhook reachability issue. See
> `DEBUG.md → Phase 3c — webhook timeout`.

### 3d — Wait for the pods (5–10 min)

```powershell
kubectl -n spinnaker get pods -w
```

Wait until **`spin-deck`** and **`spin-gate`** show **`1/1 Running`**.
Press Ctrl+C to stop watching.

> Empty namespace after a few minutes? The Operator hit a Halyard parse
> error. See `DEBUG.md → Phase 3d — Halyard 500 errors`.

### 3e — Get the URLs

```powershell
$DECK = kubectl -n spinnaker get svc spin-deck -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
$GATE = kubectl -n spinnaker get svc spin-gate -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
echo "Spinnaker UI:   http://$DECK"
echo "Spinnaker Gate: http://$GATE"
```

> **Note: the NLB exposes deck/gate on PORT 80, not 9000/8084.** Use bare
> hostnames; do NOT add `:9000` / `:8084`. See `DEBUG.md → Phase 3e — port`.
>
> URL prints blank? Either the pods aren't `Running` yet, or AWS is still
> creating the LB. Wait 2–3 min and re-run.

Open the Deck URL in a browser → empty Spinnaker UI, no login.

> **Security:** auth is off. Lock down the NLBs to your IP only before
> exposing further — see `DEBUG.md → Phase 3e — locking down access`.

---

## Phase 4 (Optional) — Configure Spinnaker for the EMS app

This phase only matters if you want to also see deploys run through
Spinnaker for learning. The real app deploy goes through GitHub Actions
(see `docs/app-deploy/`). Skip if you don't care.

### 4a — Create the application

In Deck UI:
1. **Applications → Create Application**
2. Name: `ems` (lowercase)
3. Owner email: your email
4. Cloud Providers: tick **Amazon Web Services** + **Amazon ECS**

### 4b — Install the spin CLI

```powershell
$spinDir = "$env:USERPROFILE\bin"
New-Item -ItemType Directory -Force -Path $spinDir | Out-Null
$ver = (Invoke-RestMethod "https://storage.googleapis.com/spinnaker-artifacts/spin/latest").Trim()
Invoke-WebRequest "https://storage.googleapis.com/spinnaker-artifacts/spin/$ver/windows/amd64/spin.exe" `
  -OutFile "$spinDir\spin.exe"
$env:PATH = "$spinDir;$env:PATH"

# Tell spin where Gate is — use $GATE (the value), NOT the literal placeholder
New-Item -ItemType Directory -Force "$env:USERPROFILE\.spin" | Out-Null
"gate:`n  endpoint: http://$GATE" | Out-File -Encoding ascii "$env:USERPROFILE\.spin\config"

# VERIFY it has the REAL hostname, not "<gate-hostname>" literal:
Get-Content "$env:USERPROFILE\.spin\config"
```

### 4c — Import the pipeline

> Prerequisite: `terraform/ems` must already be applied (see
> `docs/app-infra/INSTRUCTIONS.md`) so the subnet `immutable_metadata` tag
> exists. Without it, the pipeline runs but the SecurityGroupSelector NPEs.

```powershell
cd ..\..\..   # back to repo root
spin pipeline save --file spinnaker\pipelines\ems-deploy-dev-only.json
```

In Deck: **ems → Pipelines** → you should see `ems-deploy-dev-only`.

---

## Tearing it all down (end of day)

```powershell
# Delete the SpinnakerService first so its NLBs clean up
kubectl -n spinnaker delete spinnakerservice spinnaker

# Then the operator
kubectl -n spinnaker-operator delete deployment spinnaker-operator

# Finally, destroy the AWS resources
cd E:\projects\practice\terraform\spinnaker
terraform destroy        # type yes
```

> Phase 1's S3 bucket + DynamoDB table are intentionally not destroyed —
> they're reusable. Delete by hand only if you're truly done with the project.

---

## Quick "redo from scratch" checklist

1. Phase 1 — S3 + DynamoDB. *(Skip if they still exist.)*
2. Phase 2 — `terraform apply` here.
3. Phase 3a–c — kubectl operator + SpinnakerService.
4. Phase 3d — wait for pods.
5. Phase 3e — grab URLs.
6. *(Optional Phase 4)* — Spinnaker app + pipeline.

That's it. The 9 PRs of fixes are all in `main`, so a fresh clone should
go end-to-end without the snags we hit live.
