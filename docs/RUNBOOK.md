# Production runbook

The pilot server, how to change its configuration safely, and how to prove a deploy worked.
Facts here are checked against the running system; when this file and the server disagree, the server wins
and this file should be fixed.

## The box

| | |
|---|---|
| Host | EC2 `i-0215d6b600b251455`, `ec2-13-205-14-249.ap-south-1.compute.amazonaws.com`, user `ec2-user`, region ap-south-1 |
| Shared with | an n8n stack; its Caddy terminates HTTPS for `baymax.eliooo.org` and reaches the app as `medscribe-ai:8086` on the docker network `n8n_edge` |
| Container | `medscribe-ai`, image `ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai:<git sha>` |
| Runtime config | `/home/ec2-user/medscribe.env`, mode 600, passed to `docker run --env-file` |
| Database | Supabase `niramoy-rx`, session pooler, schemas `medscribe` and `baymax` |
| Object storage | AWS S3 `elioo-baymax-prod` (ap-south-1, private, SSE-S3) via the EC2 instance role (DR-5) |
| Deploy | push to `main` → GitHub Actions → GHCR → SSH → `deploy.sh`. Manually: `IMAGE_TAG=<git sha> bash ~/deploy.sh` |

`deploy.sh` pulls the tag, removes the old container, starts the new one with `--env-file` and the GCP
credentials mounted read-only, then polls `/actuator/health` for 90 seconds. It exits non-zero and prints
the last 50 log lines if the container never becomes healthy, so a failed deploy is loud.

## Editing the server environment

**Every change to `medscribe.env` is listed in the build report for the ticket that caused it.** The file
holds secrets and is not in git, so the report is the only record of what changed and why.

The habit, in order:

```bash
# 1. back up first, always — the file is the only copy
cp ~/medscribe.env ~/medscribe.env.bak.$(date +%Y%m%d-%H%M%S)

# 2. edit (sed for a value, append for a new line); keep mode 600
sed -i -E 's/^SOME_KEY=.*/SOME_KEY=new-value/' ~/medscribe.env
grep -q '^NEW_KEY=' ~/medscribe.env || printf 'NEW_KEY=value\n' >> ~/medscribe.env
chmod 600 ~/medscribe.env

# 3. show the result with whitespace visible, masking secrets
grep -n '^BAYMAX_' ~/medscribe.env | cat -A | sed -E 's/(TOKEN=|KEY=)[^$]*/\1<masked>/'

# 4. redeploy at the tag that is already running, unless you mean to change version
IMAGE_TAG=$(docker inspect -f '{{.Config.Image}}' medscribe-ai | awk -F: '{print $NF}') bash ~/deploy.sh
```

**`cat -A` matters.** `docker run --env-file` takes each value verbatim, including trailing spaces, and does
no shell-style trimming or quote handling. A line that looks empty in an editor can carry whitespace that the
application then treats as a real value. This has bitten us once already (see below).

## Baymax variables: which must be blank, which must be set

`BAYMAX_ENABLED=false` (or absent) switches the whole module off: no routes, no beans, no migrations. It is
absent on any server where Baymax should not run.

**Must be set** in production:

| Variable | Value | Why |
|---|---|---|
| `BAYMAX_ENABLED` | `true` | loads the module at all |
| `BAYMAX_ADMIN_TOKEN` | a long random string | gates `/api/v1/baymax/admin/**` and, until BMX-5 brings OTP, the family endpoints. Unset ⇒ those routes answer 503 |
| `BAYMAX_AUTH_HMAC_SECRET` | **set** (`openssl rand -hex 32`) | Stands in for phone numbers in otp_code / web_session / audit_event. Blank = OTP login answers 503 (fail closed). Rotating it logs every family out. BMX-5. |
| `BAYMAX_COOKIE_SECURE` | blank (defaults true) | Only ever set `false` on a plain-http dev box; production is HTTPS behind Caddy. |
| `BAYMAX_STORAGE_BUCKET` | `elioo-baymax-prod` | blank ⇒ no storage bean; the module still loads but every storage call fails at call time |
| `BAYMAX_STORAGE_REGION` | `ap-south-1` | |
| `BAYMAX_STORAGE_CREDENTIALS` | `instance-role` | **critical**, see below |
| `BAYMAX_STORAGE_PATH_STYLE` | `false` | AWS S3 uses virtual-hosted addressing; `true` is for MinIO and Supabase |
| `BAYMAX_STORAGE_SSE` | `AES256` | server-side encryption header on every PUT. Blank for MinIO/Supabase, which encrypt at rest themselves and may reject the header |

**Must be blank** in production:

| Variable | Why it must be empty |
|---|---|
| `BAYMAX_STORAGE_ENDPOINT` | any value overrides the AWS endpoint. A whitespace-only value is *not* empty and produces an invalid endpoint override |
| `BAYMAX_STORAGE_ACCESS_KEY` | any value is used as a static credential |
| `BAYMAX_STORAGE_SECRET_KEY` | same |

### Why the keys must be empty, and `credentials=instance-role` present

The container also carries `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` for the Comprehend-only IAM user.
The AWS SDK's default credential chain reads environment variables *before* the instance profile, so under
`default-chain` the S3 adapter would sign bucket calls as the Comprehend user and get AccessDenied.
`BAYMAX_STORAGE_CREDENTIALS=instance-role` goes straight to the instance profile and ignores those variables.
The instance role carries only the policy in [aws/baymax-s3-policy.json](aws/baymax-s3-policy.json): object
read/write/delete plus list, scoped to the one bucket.

The container reaches instance metadata over IMDSv2 on the `n8n_edge` bridge network; no metadata hop-limit
change is needed. If the container is ever moved to `--network none` or a hardened setup, the instance role
stops resolving and the self-test below fails at the `put` step.

**Incident, 2026-09-14.** Going live, the env file had ten-character placeholder strings in both storage keys
and eleven spaces in the endpoint. Either would have broken S3 at runtime while looking correct in an editor.
Blanking all three and adding `BAYMAX_STORAGE_CREDENTIALS=instance-role` fixed it.

## Post-deploy check

One call, from the server, proves bucket, credentials and presigned URLs from inside the running container.
It writes a tiny JPEG under `_selftest/`, presigns it, fetches it over HTTP, and deletes it.

```bash
TOK=$(grep '^BAYMAX_ADMIN_TOKEN=' ~/medscribe.env | cut -d= -f2-)
curl -s -H "X-Baymax-Admin-Token: $TOK" http://localhost:8086/api/v1/baymax/admin/storage/selftest
```

Healthy output — every step `ok` and `"ok":true`:

```json
{"bucket":"elioo-baymax-prod","credentials":"instance_role",
 "steps":{"put":"ok (14 bytes)","presign":"ok","get":"ok","delete":"ok"},"ok":true}
```

The route answers 503 when any step fails, so a non-200 status is itself the alarm. Reading the failing step:

| Failing step | Usual cause |
|---|---|
| `put` | bucket name wrong, instance role missing or unattached, policy does not cover the bucket ARN, or metadata unreachable from the container |
| `presign` | credentials resolved but the region is wrong |
| `get` | presigned URL rejected: clock skew on the host, or an endpoint override that does not match the signing host |
| `delete` | policy grants Put/Get but not `s3:DeleteObject` |

Alongside it, the routes that should always answer:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://baymax.eliooo.org/actuator/health          # 200
curl -s https://baymax.eliooo.org/api/v1/baymax/health                                      # {"status":"UP","module":"baymax"}
curl -s -o /dev/null -w '%{http_code}\n' https://baymax.eliooo.org/api/v1/medical-report/languages  # 200, MedScribe unaffected
curl -s -o /dev/null -w '%{http_code}\n' https://baymax.eliooo.org/api/v1/baymax/admin/storage/selftest  # 401 without the token
```

Baymax health returning 404 in production means the module did not load: `BAYMAX_ENABLED` is not `true`.

## Migrations

Two independent Flyway instances run at startup over JDBC, then the app uses R2DBC:

- MedScribe: `medscribe-ai/src/main/resources/db/migration`, schema `medscribe`.
- Baymax: `baymax/src/main/resources/db/baymax/migration`, schema `baymax`, run by `BaymaxSchemaMigrator`.

The Baymax instance is deliberately **not** a Spring bean, because Boot's Flyway auto-configuration backs off
when a `Flyway` bean exists and the MedScribe migrations would silently stop running.

The Supabase `medscribe` role **cannot `CREATE SCHEMA`**, so `baymax.flyway.create-schemas` is false there and
a new schema is created by hand once:

```sql
CREATE SCHEMA baymax AUTHORIZATION medscribe;
```

Check what has been applied:

```sql
SELECT version, description, success FROM baymax.flyway_schema_history ORDER BY installed_rank;
```

## Rollback

Redeploy the previous image tag; every merge to `main` is tagged with its git sha.

```bash
IMAGE_TAG=<previous git sha> bash ~/deploy.sh
```

Migrations do not roll back. A release that adds a column is safe to roll back this way; one that changes the
meaning of existing data is not, and needs a forward fix. To take Baymax out of service without changing
version, set `BAYMAX_ENABLED=false` and redeploy: routes and migrations stop, MedScribe is untouched.

Restoring configuration: the backups are `~/medscribe.env.bak.*`.

```bash
cp ~/medscribe.env.bak.<timestamp> ~/medscribe.env && chmod 600 ~/medscribe.env
IMAGE_TAG=$(docker inspect -f '{{.Config.Image}}' medscribe-ai | awk -F: '{print $NF}') bash ~/deploy.sh
```

## Reading logs

```bash
docker logs --tail 200 medscribe-ai 2>&1 | sed 's/\x1b\[[0-9;]*m//g'
docker logs medscribe-ai 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep '\[baymax\]'
```

Patient text never appears at INFO. Prompts, model replies and OCR text are DEBUG only; `ai_call_log` and
`stored_object` hold ids, counts, money and timings, never content.
