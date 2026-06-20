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

> **Big picture:** two things get installed, in order.
> 1. The **Operator** = a robot that knows how to install/run Spinnaker.
> 2. The **SpinnakerService** = the "order form" you hand the robot. The robot
>    reads it and builds all the Spinnaker pods.
>
> Installing the Operator alone does NOT give you Spinnaker — you must also
> apply the SpinnakerService (3c). Skipping 3c is the #1 mistake: the
> `spinnaker` namespace stays empty and the UI URL comes back blank.

### 3a. Install the Spinnaker Operator

The `manifests\operator\` folder only holds a namespace file — the actual
operator is downloaded from its GitHub release. Run from `terraform\spinnaker`:

```powershell
cd manifests\operator

# 1. create the room (namespace) the operator lives in
kubectl apply -f 00-namespace.yaml

# 2. download the operator release. Windows 10/11 has curl.exe and tar built in.
#    Use curl.exe (NOT plain "curl" — in PowerShell that's a different alias).
$OPERATOR_VERSION = "1.4.0"
curl.exe -L "https://github.com/armory/spinnaker-operator/releases/download/v$OPERATOR_VERSION/manifests.tgz" -o manifests.tgz
tar -xzf manifests.tgz        # extracts a deploy\ folder here

# 3. install: CRDs first, then the operator itself.
#    Use the "cluster" flavor (NOT "kubernetes" — that folder doesn't exist)
#    so the operator can watch the separate "spinnaker" namespace.
kubectl apply -f deploy\crds\
kubectl apply -n spinnaker-operator -f deploy\operator\cluster\

# 4. wait until the operator pod is 2/2 Running before continuing
kubectl -n spinnaker-operator rollout status deploy/spinnaker-operator
kubectl -n spinnaker-operator get pods
```

### 3b. Create the Spinnaker namespace + grab the values the manifest needs

The SpinnakerService deploys into a `spinnaker` namespace that does NOT exist
yet (the one you made in 3a was `spinnaker-operator` — a different room).

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

If `ROLE` or `BUCKET` print blank, stop — `terraform output` didn't return
(wrong folder or state). Applying a half-filled manifest deploys a broken
Spinnaker.

### 3c. Render + apply the SpinnakerService (the order form)

```powershell
cd manifests\spinnaker

# fill the __PLACEHOLDERS__ with the values from 3b (PowerShell — no envsubst)
$svc = Get-Content spinnakerservice.yaml -Raw
$svc = $svc.Replace('__AWS_ACCOUNT_ID__',     $ACCOUNT)
$svc = $svc.Replace('__AWS_REGION__',          $REGION)
$svc = $svc.Replace('__SPINNAKER_ROLE_ARN__',  $SPIN_ROLE)
$svc = $svc.Replace('__PERSISTENCE_BUCKET__',  $BUCKET)
$svc | Out-File -Encoding ascii spinnakerservice.rendered.yaml

kubectl apply -f spinnakerservice.rendered.yaml
```

Expected: `spinnakerservice.spinnaker.io/spinnaker created`.

> **If `failed calling webhook ... context deadline exceeded`** — the EKS
> control plane can't reach the operator's validation webhook (common EKS
> quirk: the control-plane→node path on the webhook port isn't open). The
> webhook is only a pre-check; delete it and re-apply. The operator still
> builds Spinnaker normally:
> ```powershell
> kubectl delete validatingwebhookconfiguration spinnakervalidatingwebhook
> kubectl apply -f spinnakerservice.rendered.yaml
> ```

### 3d. Wait for it to come up — and watch the operator log if nothing appears (5–10 min)

```powershell
kubectl -n spinnaker get pods -w
```

The operator now builds the `spin-*` pods (deck, gate, clouddriver, orca,
front50, echo…). Press Ctrl+C to stop watching once **`spin-deck`** and
**`spin-gate`** show **`1/1 Running`**.

> **If after ~3 min no pods appear**, the operator hit a Halyard error parsing
> your SpinnakerService. Check Halyard's log (it lives in a *different
> container* on the operator pod):
> ```powershell
> kubectl -n spinnaker-operator logs deploy/spinnaker-operator -c halyard --tail=300 > halyard.log
> notepad halyard.log
> ```
> Search the file (Ctrl+F) for `ERROR` — the real cause is on that line, NOT
> in the long Spring/Tomcat stack trace below it. Common ones we've hit:
> - `Unrecognized field "X"` → a typo in `spinnakerservice.yaml`. Halconfig
>   uses **`webhook`** (singular), not `webhooks`.
> - `NullPointerException at ...Provider.getPrimaryAccount` → an enabled
>   provider is missing `primaryAccount`. Add it.
> - `S3 bucket not found` → `$BUCKET` was blank when you rendered. Re-render.
>
> The repo's template already has these fixes; this is for if you customize it.

### 3e. Get the URLs

When `spin-deck` and `spin-gate` are `Running`, get their public hostnames
(these are AWS network load balancers):

```powershell
$DECK = kubectl -n spinnaker get svc spin-deck -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
$GATE = kubectl -n spinnaker get svc spin-gate -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
echo "Spinnaker UI:   http://$($DECK)"
echo "Spinnaker Gate: http://$($GATE)"
```

> **About the port — read this.** Spinnaker's pods listen on **9000** (deck)
> and **8084** (gate), but the operator exposes them on the LoadBalancer's
> **port 80**, like this:
> ```
> spin-deck  LoadBalancer  ...  PORT(S) 80:32068/TCP
> spin-gate  LoadBalancer  ...  PORT(S) 80:32005/TCP
> ```
> So the right URLs are bare hostnames (`http://...elb.amazonaws.com`), NOT
> `:9000` / `:8084`. Browsers and `curl` default to `:80`, which is what the
> NLB is listening on. Add `:9000` and you'll get "connection refused."
>
> **Verify** with `kubectl -n spinnaker get svc spin-deck spin-gate` — the
> `PORT(S)` column tells you which external port to use.

> **If a URL prints blank (`http://`)** — the AWS load balancer doesn't have
> an address yet. Either the `spin-*` pods aren't `Running` (wait), or AWS is
> still provisioning the LB (`EXTERNAL-IP` shows `<pending>` for 2–3 min).

Open the Deck URL in a browser. No login (auth is disabled — see the warning
below).

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

In the Deck UI (the URL from 3e):

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

# Tell spin where Gate is. NLB port is 80 (NOT 8084) — see 3e port warning.
# IMPORTANT: $GATE must already hold the real Gate hostname from 3e.
#   echo $GATE     -> should print something like  a1b2...elb.amazonaws.com
# If it's blank (e.g. you opened a fresh window), re-grab it before continuing:
#   $GATE = kubectl -n spinnaker get svc spin-gate -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
# Use this exact "...$VAR..." form (NOT a @"..."@ here-string with the literal
# placeholder <gate-hostname>) — PowerShell only expands variables inside
# double-quoted strings.
"gate:`n  endpoint: http://$GATE" | Out-File -Encoding ascii "$env:USERPROFILE\.spin\config"

# VERIFY the file holds the real hostname, not a placeholder:
Get-Content "$env:USERPROFILE\.spin\config"
# Expect:
#   gate:
#     endpoint: http://a1b2...elb.amazonaws.com

# from the repo root: import the trimmed dev-only pipeline (recommended for
# a learning run — the full ems-deploy-cicd.json has perf/prod and a
# Jenkins-type BlazeMeter stage that fail without Jenkins or those envs).
cd ..\..       # -> repo root
spin pipeline save --file spinnaker\pipelines\ems-deploy-dev-only.json
```

Refresh the UI → **ems → Pipelines** → you should see `ems-deploy-dev-only`.

Switch to `ems-deploy-cicd.json` (the full perf/prod flow) later, when you
add those environments and a Jenkins for BlazeMeter.

> **One Spinnaker-side tweak the pipeline needs.** The pipeline JSON
> references a subnet attribute named `ecs-tasks-dev` (line: `subnetType`).
> Spinnaker's clouddriver maps that name to the real subnet IDs at deploy
> time. You register it in `clouddriver-local.yml` inside the SpinnakerService
> spec under `spinnakerConfig.profiles.clouddriver.ecs.subnetTypes`, or the
> deploy will fail at "Deploy to Dev" with "no subnets found." Quickest
> learning workaround: in Deck → ems → Pipelines → Edit → Deploy to Dev
> stage → set `subnetType` to the literal subnet IDs from Phase 4
> (`terraform output subnet_ids`).

---

## Phase 6 — Wire GitHub Actions (the auto-deploy trigger)

The workflow `.github/workflows/deploy.yml` is already in the repo. Three setup
steps: get the values, save them to GitHub, open the network so GitHub can reach
Spinnaker.

### 6a. Gather the exact values to paste

In PowerShell, from `terraform\spinnaker`:

```powershell
cd E:\projects\practice\terraform\spinnaker

# the ARN for the app deploy role (NOT the infra one, NOT the spinnaker one)
$ROLE_ARN = (terraform output -raw github_actions_role_arn)

# the Gate hostname (from 3e — re-grab if blank)
$GATE = kubectl -n spinnaker get svc spin-gate -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'

# any random string — not enforced right now
$WEBHOOK_TOKEN = [guid]::NewGuid().ToString()

# print the four values to paste
"AWS_ROLE_ARN              = $ROLE_ARN"
"AWS_REGION                = us-east-1"
"SPINNAKER_GATE_URL        = http://$GATE"
"SPINNAKER_WEBHOOK_TOKEN   = $WEBHOOK_TOKEN"
```

> **Which ARN is "the" ARN?** You may see several roles in the spinnaker outputs.
> The one for GitHub Actions building+pushing images is
> **`github_actions_role_arn`** (no `_infra`). The others are for the infra
> workflow, Spinnaker itself, or ECS tasks — don't paste those here.

### 6b. Save them on GitHub

GitHub → repo → **Settings → Secrets and variables → Actions**.

**Variables tab → New repository variable** (×3):

| Name                | Value (from 6a)         |
|---------------------|-------------------------|
| `AWS_ROLE_ARN`      | `$ROLE_ARN`             |
| `AWS_REGION`        | `us-east-1`             |
| `SPINNAKER_GATE_URL`| `http://$GATE` (no port, no trailing slash — see 3e port warning) |

**Secrets tab → New repository secret** (×1):

| Name                      | Value                  |
|---------------------------|------------------------|
| `SPINNAKER_WEBHOOK_TOKEN` | `$WEBHOOK_TOKEN`       |

### 6c. Open the network so GitHub can reach Spinnaker Gate

The Gate NLB needs to accept inbound HTTP from GitHub's runners on **port 80**
(the external port — see 3e). Two paths depending on what AWS shows you:

**If the Gate NLB has a security group in its console page**, edit that SG and
add: Type `Custom TCP`, Port `80`, Source `0.0.0.0/0`. Done.

**If the Gate NLB shows "No security group associated"** (common — older-style
NLB), traffic is gated at the **EKS worker nodes** instead. Open *their* SG:

```powershell
# get any one EKS worker node name
kubectl get nodes -o jsonpath='{.items[0].metadata.name}'
```

Then in AWS Console:
1. **EC2 → Instances** → search/paste that node name → click the instance.
2. **Security** tab → click the security group link (named like
   `eks-cluster-sg-spinnaker-...`).
3. **Inbound rules → Edit inbound rules → Add rule:**
   - Type: **Custom TCP**
   - Port: **80**
   - Source: **0.0.0.0/0**
4. **Save rules.**

> **Safer (later):** Source `0.0.0.0/0` lets the whole internet hit your Gate.
> Fine for a learning run while NLB auth is off. To scope it down, replace the
> source with GitHub's published runner IP ranges from
> `https://api.github.com/meta` (the `actions` array). Or skip this step
> entirely and trigger the pipeline manually from the Deck UI — no inbound
> rule needed.

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
2. **Spinnaker UI → ems → Pipelines** — an execution starts. With the
   dev-only pipeline imported, it runs just **Deploy to Dev** and stops there.
   (Pre-merge of perf/prod work, you'd swap to `ems-deploy-cicd.json`.)
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

## Troubleshooting — what can go wrong, and why

Real snags hit while building this, in the order you'd meet them. Each
explains the *why* so you can reason about it, not just copy a fix.

### Phase 2 — Terraform / EKS

| Symptom | Why it happens / fix |
|---|---|
| `UnsupportedAvailabilityZoneException ... us-east-1e` while creating EKS | A **region** (us-east-1) is made of **zones** (1a, 1b…1f). EKS control planes don't support zone `1e`, but the default VPC has a subnet there. **Already fixed in the repo** (`data.tf` filters out 1e). If you use a different region/VPC and hit the same, exclude the unsupported zone the error names. |
| `terraform apply` errors `bucket already exists` | S3 bucket names are **globally unique** across all AWS accounts. Pick a different name and update both `providers.tf` files. |
| `Error acquiring the state lock` | You Ctrl+C'd a previous run before it released the DynamoDB lock. Copy the `ID` from the error and run `terraform force-unlock <ID>`. Safe as long as no other apply is truly running. |
| Terraform keeps prompting `Enter a value: var.aws_account_id` | The `account.auto.tfvars` file is missing/empty (your `$ACCOUNT` was blank when you wrote it). Recreate it; `Get-Content account.auto.tfvars` should show 12 digits. |

### Phase 3 — Operator / SpinnakerService

| Symptom | Why it happens / fix |
|---|---|
| `kubectl apply -f 00-namespace.yaml` → `path does not exist` | You're in the wrong folder. That file is in `manifests\operator\`. `cd operator` first. |
| `deploy\operator\kubernetes\ does not exist` | The operator tarball uses `basic\` and `cluster\`, not `kubernetes\`. Use `deploy\operator\cluster\` (cluster mode so it can watch the `spinnaker` namespace). |
| `kubectl -n spinnaker get pods` → "No resources found" / blank URL | **You installed the operator but never applied the SpinnakerService.** The operator is just the installer; the SpinnakerService (3c) is what tells it to build Spinnaker. Do 3b + 3c. |
| `failed calling webhook ... context deadline exceeded` when applying the SpinnakerService | The EKS **control plane can't reach the operator's validation webhook** (control-plane→node path on that port isn't open — common EKS quirk). The webhook is only a pre-check; delete it and re-apply: `kubectl delete validatingwebhookconfiguration spinnakervalidatingwebhook`. The operator still builds Spinnaker. |
| `namespaces "spinnaker" not found` when applying the SpinnakerService | The `spinnaker` namespace doesn't exist yet (the one from 3a was `spinnaker-operator`, a different room). Run `kubectl create namespace spinnaker` first. |
| SpinnakerService accepted but **no pods appear**; operator log shows `got halyard response status 500` | Halyard rejected the config. The **real error** is in the `halyard` container's log (not in `describe` and not in the Spring stack trace). `kubectl -n spinnaker-operator logs deploy/spinnaker-operator -c halyard --tail=300 > halyard.log; notepad halyard.log` and Ctrl+F for `ERROR` — read that line, NOT the 200 lines of Spring filters below it. |
| Halyard log says `Unrecognized field "X"` | Typo in `spinnakerservice.yaml`. Halconfig uses **`webhook`** (singular), not `webhooks` — repo template already fixed. |
| Halyard log says `NullPointerException ... Provider.getPrimaryAccount` | An enabled provider is missing `primaryAccount`. The ECS block needs `primaryAccount: aws-dev` — repo template already fixed. |
| Browser/curl on `http://<host>:9000` → "connection refused" | The NLB exposes deck on **port 80**, not 9000 — the operator's `expose` config rewrites the port. `kubectl -n spinnaker get svc spin-deck spin-gate` shows `PORT(S) 80:32xxx/TCP`. Use the bare hostname (no port) for both Deck and Gate. |
| UI/Gate URL prints `http://` (blank host) | The load balancer has no address yet. Either the `spin-*` pods aren't `Running` (wait), or AWS is still creating the LB (`EXTERNAL-IP` shows `<pending>` — wait 2–3 min). Not an error. |

### Phase 5–7 — Spinnaker / GitHub Actions / deploy

| Symptom | Why it happens / fix |
|---|---|
| `spin pipeline save` → can't reach Gate | The `~/.spin/config` Gate endpoint is wrong. Two common causes: (a) NLB port is 80, not 8084 — use `http://<gate-host>` with no port; (b) you used a here-string (`@"..."@`) and the literal `<gate-hostname>` placeholder got written instead of the value. Fix: re-grab `$GATE` (`kubectl -n spinnaker get svc spin-gate -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'`), then write the file with the `"...$GATE..."` form and `Get-Content` it to verify a real `.elb.amazonaws.com` URL is in there. |
| Gate NLB shows "No security group associated" — can't edit rules | This is an older-style NLB; SGs are enforced at the **EKS worker nodes** instead, not the LB. Edit the worker nodes' SG (`eks-cluster-sg-spinnaker-...`) — Phase 6c has the exact steps. |
| Pipeline fails on a perf/prod or BlazeMeter (Jenkins) stage | You imported the full `ems-deploy-cicd.json`. With only a dev environment and no Jenkins, import `ems-deploy-dev-only.json` instead. |
| Actions "Configure AWS credentials" → `Not authorized to perform sts:AssumeRoleWithWebIdentity` | `AWS_ROLE_ARN` variable wrong, or you pushed from a branch other than `main` (the OIDC trust in `github_oidc.tf` is scoped to `main`). |
| Jib push fails `denied: not authorized` | The `github-actions-ems` role's ECR policy lives in `terraform/spinnaker` — make sure Phase 2 applied cleanly. |
| Spinnaker execution never starts after Actions succeeds | `SPINNAKER_GATE_URL` wrong (must be the bare hostname, port 80 — see Phase 6), or the Gate NLB security group blocks GitHub's runners (see Phase 6 note). Test reachability, or trigger manually in the UI. |
| ECS task stuck `PENDING` then dies | Check **CloudWatch → /ecs/ems-dev** logs. Usually a DB connection failure — confirm the RDS SG allows the tasks SG and the `DB_*` SSM params resolved. |
