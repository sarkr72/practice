# Spinnaker infra — debug guide

Every snag we hit during the live build, in the order you'd meet them, with
**why** it happens so you can reason from first principles instead of just
copy-pasting fixes.

---

## Phase 1 — Bootstrap

### `bucket already exists` from `aws s3api create-bucket`
**Why:** S3 bucket names are **globally unique** across every AWS account on
Earth. Someone else has `rinku-tfstate-001`.

**Fix:** pick a different name. Update three places:
- `terraform/spinnaker/providers.tf` → `backend "s3" { bucket = "..." }`
- `terraform/ems/providers.tf` → same line
- `terraform/spinnaker/variables.tf` → `persistence_bucket_name` default

---

## Phase 2 — Terraform

### `UnsupportedAvailabilityZoneException ... us-east-1e` while creating EKS
**Why:** A region (us-east-1) is made of zones (1a, 1b … 1f). EKS control
planes don't support zone `1e`, but the default VPC has a subnet there, and
the original Terraform handed *all* subnets to EKS.

**Fix (already in repo):** `terraform/spinnaker/data.tf` and
`terraform/ems/data.tf` filter out `us-east-1e`. If you use a different
region/VPC and the error names a different zone, exclude that one.

---

### `Error acquiring the state lock`
**Why:** You Ctrl+C'd a previous `terraform apply` before it released the
DynamoDB lock. The "LOCKED" marker is stuck in the table.

**Fix:** the error prints the lock ID. Run:
```powershell
terraform force-unlock <lock-id>
```
Type `yes` to confirm. Safe as long as no other apply is *truly* running
elsewhere.

---

### Terraform keeps prompting `Enter a value: var.aws_account_id`
**Why:** `account.auto.tfvars` doesn't exist or is empty. Most common cause:
your `$ACCOUNT` variable was blank when you ran the `Out-File` command (you
forgot to run `aws sts get-caller-identity` first).

**Fix:**
```powershell
$ACCOUNT = (aws sts get-caller-identity --query Account --output text)
echo $ACCOUNT     # MUST show 12 digits, not blank
"aws_account_id = `"$ACCOUNT`"" | Out-File -Encoding ascii account.auto.tfvars
Get-Content account.auto.tfvars     # verify it has the digits
```

---

### Warning: deprecated parameter `dynamodb_table`
**Why:** Newer Terraform versions support `use_lockfile` (locking via a file
in S3 directly, no DynamoDB needed). Old `dynamodb_table` still works,
they're just warning.

**Fix:** ignore. Both work. We're keeping `dynamodb_table` for portability.

---

### `terraform apply` hangs / asks for confirmation
**Why:** Plain `terraform apply` shows the plan and waits for you to type
`yes` — by design, a safety check.

**Fix:** type `yes` and press Enter (not just `y`). For scripted /
non-interactive runs, add `-auto-approve` (used by `.github/workflows/infra.yml`).

---

## Phase 3a — Installing the Operator

### `kubectl apply -f 00-namespace.yaml` → `path does not exist`
**Why:** You're in the wrong folder. The file is in
`terraform/spinnaker/manifests/operator/`, not `terraform/spinnaker/manifests/`.

**Fix:**
```powershell
cd terraform\spinnaker\manifests\operator
kubectl apply -f 00-namespace.yaml
```

---

### `deploy\operator\kubernetes\ does not exist`
**Why:** The Spinnaker Operator tarball doesn't have a `kubernetes/` subfolder.
It has `basic/` (operator watches only its own namespace) and `cluster/`
(operator watches all namespaces — what we need).

**Fix:** use `deploy\operator\cluster\`. Already in the runbook now.

---

## Phase 3b — Spinnaker namespace

### `namespaces "spinnaker" not found` when applying the SpinnakerService
**Why:** The `00-namespace.yaml` in step 3a created `spinnaker-operator` (the
room for the operator). `spinnaker` (the room for Spinnaker itself) is a
separate namespace that wasn't created yet.

**Fix:** create it explicitly before applying the CR:
```powershell
kubectl create namespace spinnaker
```

---

### `terraform output -raw spinnaker_role_arn` returns blank
**Why:** Either you're not in `terraform/spinnaker/`, or the apply failed
partway through and the output isn't set.

**Fix:** confirm `Get-Location` is `...terraform\spinnaker`, then re-run
`terraform apply` to make sure all resources actually got created.

---

## Phase 3c — Applying the SpinnakerService

### `failed calling webhook ... context deadline exceeded`
**Why:** The EKS control plane has to call the Operator's validation webhook
to admit your SpinnakerService. The control-plane → worker-node path on the
webhook port (`9876`) isn't open in default EKS networking — a well-known
EKS quirk.

**Fix:** the webhook is just a pre-check. Delete it and re-apply. The
Operator's controller still reconciles your SpinnakerService normally:
```powershell
kubectl delete validatingwebhookconfiguration spinnakervalidatingwebhook
kubectl apply -f spinnakerservice.rendered.yaml
```

---

### Wait — what's a "webhook" here?
The operator registers a *Kubernetes admission webhook* that intercepts
`SpinnakerService` create/update requests to validate them before they hit
the cluster's storage. It runs in the operator pod. When the EKS control
plane can't reach that pod, the request times out.

Deleting the webhook just disables that pre-check. The operator still builds
Spinnaker — it just no longer rejects malformed config up front (you'd see
Halyard errors instead, which is the next debug category below).

---

## Phase 3d — Pods not appearing

### `kubectl -n spinnaker get pods` shows nothing after 5+ minutes
**Why:** The Operator received your SpinnakerService but Halyard rejected it.
**Halyard** is the Spinnaker config compiler that lives in a *separate
container* of the operator pod. Its 500 errors are NOT in the operator's
main log.

**Get the real error:**
```powershell
kubectl -n spinnaker-operator logs deploy/spinnaker-operator -c halyard --tail=300 > halyard.log
notepad halyard.log
```

In Notepad: Ctrl+End → Ctrl+F → search `ERROR`. The actual cause is on that
line, NOT in the 100+ lines of Spring filter stack below it.

---

### Halyard: `Unrecognized field "webhooks"`
**Why:** The SpinnakerService template had `webhooks:` (plural). Halconfig
uses the singular `webhook:`. Strict JSON schema → reject.

**Fix (already in repo):** `spinnakerservice.yaml` now uses `webhook:`.

---

### Halyard: `NullPointerException ... Provider.getPrimaryAccount`
**Why:** The ECS provider was enabled but missing `primaryAccount: aws-dev`.
Halyard requires every enabled provider to declare its primary account.

**Fix (already in repo):** the ECS block in `spinnakerservice.yaml` now has
`primaryAccount: aws-dev`.

---

## Phase 3e — Getting URLs

### `kubectl -n spinnaker get svc spin-deck -o jsonpath=...` prints blank
**Why:** Either (a) the `spin-deck`/`spin-gate` pods aren't `Running` yet
— wait for them — or (b) AWS is still provisioning the NLB. Check with:
```powershell
kubectl -n spinnaker get svc spin-deck spin-gate
```
If `EXTERNAL-IP` shows `<pending>`, AWS is still creating it. Wait 2–3
minutes and re-run.

---

### Browser → `http://<host>:9000` → "connection refused"
**Why:** Spinnaker pods listen on 9000 (deck) / 8084 (gate), but the
Operator's `expose` config rewrites those to **port 80** at the NLB. So the
NLB is listening on 80, not 9000.

**Verify:**
```powershell
kubectl -n spinnaker get svc spin-deck spin-gate
# look at PORT(S) column — you'll see "80:32xxx/TCP", not "9000:..."
```

**Fix:** use bare hostnames with no port:
```
http://<deck-host>     ← correct
http://<deck-host>:9000  ← wrong
```

---

### Spin CLI config writes the literal `<gate-hostname>` placeholder
**Why:** You used a here-string (`@"..."@`) and PowerShell didn't expand the
variable inside it, OR you typed `<gate-hostname>` literally thinking it was
syntax to substitute. PowerShell variable expansion only works inside
double-quoted `"..."` strings, not here-strings as written.

**Fix:**
```powershell
"gate:`n  endpoint: http://$GATE" | Out-File -Encoding ascii "$env:USERPROFILE\.spin\config"
Get-Content "$env:USERPROFILE\.spin\config"      # verify it has a real .elb.amazonaws.com URL
```

---

### Locking down access — Gate NLB shows "No security group associated"
**Why:** Older-style AWS NLBs don't have their own security group; SGs are
enforced at the **EKS worker nodes** instead. Trying to "edit the SG on the
LB" finds nothing.

**Fix:** edit the SG on the worker nodes.
```powershell
kubectl get nodes -o jsonpath='{.items[0].metadata.name}'
```
Then in AWS Console:
1. EC2 → Instances → paste that node name → click the instance.
2. Security tab → click the SG (named `eks-cluster-sg-spinnaker-...`).
3. Inbound rules → Edit → Add rule:
   - Type: Custom TCP
   - Port: **80** (the external port — see port note above)
   - Source: your IP (for security) **or** `0.0.0.0/0` (open to world)
4. Save.

---

## Phase 4 — Spinnaker app config

### Spinnaker UI: "Amazon ECS" not in the Cloud Providers list
**Why:** The SpinnakerService's `ecs` provider block didn't apply, or
clouddriver hasn't restarted with the new config yet.

**Fix:** check the rendered manifest has `ecs.enabled: true`. If yes, restart
clouddriver:
```powershell
kubectl -n spinnaker rollout restart deploy/spin-clouddriver
kubectl -n spinnaker rollout status deploy/spin-clouddriver
```

---

### `spin pipeline save` succeeds but UI shows no pipeline
**Why:** Two cases. (a) Cache — the UI sometimes doesn't refresh; force a
hard reload (Ctrl+F5). (b) The save targeted the wrong application.

**Verify:**
```powershell
spin pipeline list --application ems
```
If empty, the save didn't land for application `ems`. Re-check the
`"application": "ems"` field in the JSON file.

---

## IAM-related deploy errors (when Spinnaker tries to deploy)

> These only happen if you're actively running Spinnaker pipeline executions.
> If you just want Spinnaker installed for UI exploration, you'll never hit
> them.

### `sts:AssumeRole on resource: arn:aws:iam::ACC:role/spinnaker-managed` → AccessDenied
**Why:** Spinnaker re-assumes its own role at deploy time (per-account
`assumeRole` config in SpinnakerService). For that to work, the role's trust
policy must include a statement letting itself assume itself.

**Fix (already in repo):** `terraform/spinnaker/iam.tf` now has a self-trust
statement baked in. If terraform overwrites it via the console, this
self-trust survives.

---

### `iam:GetRole on resource: role ems-dev-task-exec` → AccessDenied
**Why:** Spinnaker validates the ECS task definition by calling `GetRole`
on the task roles, not just `PassRole`. Original inline policy didn't include
`GetRole`.

**Fix (already in repo):** the `PassEcsTaskRoles` statement now includes
`iam:GetRole`.

---

### `secretsmanager:ListSecrets` → AccessDenied → `Cannot invoke Collection.size()`
**Why:** Spinnaker's caching agents inventory the account every minute
(SecretCachingAgent, AmazonCertificateCachingAgent, etc.). Without
`List*`/`Describe*` perms, their caches stay empty/null → validators NPE on
`new HashSet<>(null)`.

**Fix (already in repo):** the inline policy now has a
`CachingAgentInventory` statement with Secrets/ACM/EC2/ELB/IAM/ECS
list/describe permissions.

---

### `Cannot invoke "java.util.Collection.size()" because "c" is null` (the persistent one)
**Why:** Even with all caching perms granted, the `EcsSecurityGroupCachingAgent`
keyed its cache by `account:region:vpcId`. Without a way to derive `vpcId`
from `subnetType`, the lookup returned null → `new HashSet<>(null)` → NPE.

`vpcId` is derived by scanning subnets for an `immutable_metadata` tag whose
JSON `purpose` field matches the `subnetType` string in the pipeline JSON
(e.g. `ecs-tasks-dev`). Default-VPC subnets don't have this tag.

**Fix (already in repo):** `terraform/ems/spinnaker_subnet_tags.tf` adds the
tag to every default-VPC subnet. The `terraform/ems` apply must be done
**before** the Spinnaker pipeline can succeed.

After tagging, restart clouddriver and wait ~2 min for the new cache cycle:
```powershell
kubectl -n spinnaker rollout restart deploy/spin-clouddriver
kubectl -n spinnaker rollout status deploy/spin-clouddriver
Start-Sleep -Seconds 120
```

---

### Spinnaker created the service but ECS task is `PENDING` → dies
**Why:** Spinnaker's createServerGroup succeeded (no IAM/NPE errors), but the
task itself can't start. Almost always a DB connection issue or a secret
that doesn't resolve.

**Fix:** check CloudWatch logs:
```powershell
aws logs tail /ecs/ems-dev --since 10m --format short
```
Look for `HikariPool` errors (DB), `ResourceNotFound` (secret path doesn't
exist), or app startup exceptions.

---

## Final reality check

If you've burned more than an afternoon on Phase 4 (Spinnaker deploys),
remember: **the real deploy path is `aws ecs` from GitHub Actions
(`docs/app-deploy/`).** Spinnaker is here for learning, not for being the
deployer. You can absolutely stop here, ship via direct-ECS, and never look at
the Spinnaker UI again — and you've still set up enterprise infrastructure end
to end.
