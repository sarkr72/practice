# Terraform — EMS infrastructure

## Layout

```
terraform/
├── environments/      # one dir per deployed environment
│   ├── dev/
│   └── prod/          # mirror of dev with prod-sized values
└── modules/           # reusable building blocks
    ├── vpc/
    ├── eks/
    ├── rds/
    ├── elasticache/
    ├── msk/
    └── ecr/
```

**Principle:** modules know nothing about environments. Environments compose modules and pass
in values. If you find yourself putting `if env == "prod"` logic inside a module, that's a
signal to pull it out as a variable.

## Prod differences

The prod environment mirrors dev but with:

- `msk.number_of_broker_nodes = 3` (so `min.insync.replicas = 2` works)
- `rds.instance_class = "db.r6g.large"` and `multi_az = true`
- `redis.num_cache_nodes = 2` (replication enabled)
- `eks.node_group_size = { desired = 4, min = 4, max = 12 }`
- `vpc.single_nat_gateway = false` (one NAT per AZ — HA, extra cost)

Copy `environments/dev/` → `environments/prod/`, adjust `terraform.tfvars`, and change the
backend state key to `prod/terraform.tfstate`.

## State management

Each environment has its own state file. **Never share state between dev and prod.** If you
need to reference outputs across environments, use a `data "terraform_remote_state"` block.

## Naming convention

All resources get the `ems-${environment}` prefix via the `local.name` in each environment's
`main.tf`. Keep this — it makes cost allocation and teardown trivial.

## Destroying

```bash
cd environments/dev
terraform destroy
```

RDS has `deletion_protection = true` — you'll need to `terraform apply -target` with that
flipped first, or remove it from state with `terraform state rm` if you really know what
you're doing.
