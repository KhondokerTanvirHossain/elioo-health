# Production runbook

The pilot server, how to change its configuration safely, and how to prove a deploy worked.
Facts here are checked against the running system; when this file and the server disagree, the server wins
and this file should be fixed.

## The box

| | |
|---|---|
| Host | EC2 `i-0215d6b600b251455`, `ec2-13-205-14-249.ap-south-1.compute.amazonaws.com`, user `ec2-user`, region ap-south-1 |
| Shared with | an n8n stack; its Caddy terminates HTTPS for `medioo.eliooo.org` (and answers `baymax.eliooo.org` with a permanent redirect to it, path preserved — DR-15) and reaches the app as `medscribe-ai:8086` on the docker network `n8n_edge` |
| DNS | `medioo.eliooo.org` **and** `baymax.eliooo.org` are each an **A record** pointing at the EC2 IP (`13.205.14.249`) in Hostinger (zone on `dns-parking.com`), TTL 14400. They are *not* CNAMEs, so **moving the box means changing both records** — updating only `medioo` leaves the old hostname resolving to a dead IP. `baymax` was added 2026-09-19; before that the documented redirect had never worked, because only `medioo` existed. **Negative caching: a newly added record can keep reading NXDOMAIN on a machine that looked it up while it was still missing — up to the zone's negative TTL (4h here) after the record is live.** `curl`, `host` and `getent` then fail while `dig @8.8.8.8 <name>` answers correctly, because `dig` bypasses the system resolver. That is a stale local cache, **not** a failed deploy: confirm from the EC2 box (`ssh … 'curl -sS -o /dev/null -w "%{http_code} %{redirect_url}\n" https://baymax.eliooo.org/app/home'`) before treating it as one |
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
| `BAYMAX_PUBLIC_BASE_URL` | `https://medioo.eliooo.org` (set 2026-09-19) | Builds the timeline links in outbound messages. Default in application.properties is the same host; set explicitly so a host change is one env edit + restart. |
| `BAYMAX_GATE_NUDGES` | blank (defaults true) | DR-18: every nudge parked for the reviewer during the pilot, whatever `BAYMAX_GATE_MODE` says. Set `false` only after the PO ends the pilot gate. |
| `BAYMAX_NUDGE_ENABLED` | blank (defaults true) | The hourly proactive job (BMX-8). Set `false` on any box that must never message a family. `BAYMAX_NUDGE_CRON` overrides the schedule (default `0 5 * * * *`). |
| `BAYMAX_RECOVERY_ENABLED` | blank (defaults true) | F9b: at startup, finish the documents the previous container left RECEIVED/PROCESSING, and explain recent ones that finished but were never explained. Set `false` **only** to stop a crash loop (see "Documents a restart interrupted"). |
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

**WhatsApp channel** (BMX-10). All of these are blank on a box where the channel is off; with
`BAYMAX_WA_ENABLED=true`, `WaConfigValidator` refuses to start unless the token, app secret, verify token, phone
number id and WABA id are set and well-formed (it names the variable, never the secret):

| Variable | Value | Why |
|---|---|---|
| `BAYMAX_WA_ENABLED` | `true` to run the channel; blank = off | blank/`false` ⇒ no webhook, no Graph client, every other behaviour unchanged |
| `BAYMAX_WA_TOKEN` | the **permanent system-user** token | a temporary user token dies in 24 hours and every send then fails `token_invalid` |
| `BAYMAX_WA_APP_SECRET` | the Meta app secret | verifies `X-Hub-Signature-256` on every callback; a wrong value is a 403 on every message |
| `BAYMAX_WA_VERIFY_TOKEN` | a long random string, the same as in the Meta dashboard | the webhook handshake. **Rotation outstanding (DR-23)**: the current value reached the log before query-string redaction; change it in the Meta dashboard and here together |
| `BAYMAX_WA_PHONE_NUMBER_ID` | digits only | the sending number's Graph id |
| `BAYMAX_WA_WABA_ID` | digits only | the WhatsApp Business Account id |
| `BAYMAX_WA_ALLOWLIST` | comma-separated E.164 numbers | **Tanvir's number only while the gate is off.** Enforced in code on every send; a non-E.164 entry refuses to start. **Blank means no restriction**: every family number would be messaged |
| `BAYMAX_GATE_MODE` | blank (= `off`) | `off` / `all` / `urgency` (DR-13). Nobody changes it to try something on the phone; a gate change is a PO decision |

## Leak counters

After every deploy, on the server. Each count is **0** unless marked; anything else is a credential, a phone number
or a wamid (base64 of the phone number) in the log. Count only; never print the matching lines.

Two formats every count depends on, both checked against real output (PR 1):

- **The console log is coloured.** The level is wrapped in ANSI codes (`[ESC[1;31mERROR ESC[0;39m]`), so a plain
  `grep ' ERROR '` never matches and reads 0 whatever happened. Every count below strips the codes first.
- **Header lines keep HttpHeaders' own format**, `Name:"value"`, and a redacted header reads `Name:"REDACTED"`
  (`IWebFilter.redactHeaders` returns an `HttpHeaders` for exactly this reason). A grep written for any other
  format — `Name: value`, `Name=[value]` — reads 0 on a leak. Header names are matched case-insensitively, since a
  proxy hop may lowercase them.

```bash
L() { docker logs medscribe-ai --since 15m 2>&1 | sed 's/\x1b\[[0-9;]*m//g'; }
L | grep -cE '\[ERROR\]|refusing to start'                                                   # 0, or each line explained
L | grep -ciE 'baymax_session=|wamid\.'                                                        # 0: a session id or a wamid
L | grep -oiE '(x-baymax-admin-token|authorization|cookie|x-hub-signature-256):"[^"]*"' | grep -vc ':"REDACTED"$'  # 0: a credential header in clear
L | grep 'wa inbound ref=' | tail -1 | grep -cE 'ref=[0-9a-f]{12} '                             # 1 after a WhatsApp message: ids are digests
```

A non-zero second or third count means: rotate `BAYMAX_ADMIN_TOKEN`, consider revoking live sessions
(`UPDATE baymax.web_session SET revoked_at = now() WHERE revoked_at IS NULL`), and find the log line before the
next deploy.

**Before the deploy that first redacts headers (PR 1), once:** the same counts over the whole life of the running
container decide whether the admin token must be rotated. Take them **before merging**: the deploy removes the
container (`deploy.sh`, `docker rm -f`) and its log with it, and the log files inside the container go too (no
volume). They cover this container only; any earlier container's log is already gone, so if the admin routes were
ever called in production, rotate whatever the count says.

```bash
B() { docker logs medscribe-ai 2>&1 | sed 's/\x1b\[[0-9;]*m//g'; }
B | grep -oiE 'x-baymax-admin-token:"[^"]*"' | grep -vc ':"REDACTED"$'   # admin-token values in clear
B | grep -c 'baymax_session='                                                # session cookies in clear
B | grep -c 'wamid\.'                                                        # wamids
```

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
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/actuator/health          # 200
curl -s https://medioo.eliooo.org/api/v1/baymax/health                                      # {"status":"UP","module":"baymax"}
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/medscribeai/api/v1/medical-report/languages  # 200, MedScribe at its prefix (DR-11)
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/                                          # 200, Medioo landing
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' https://baymax.eliooo.org/app/home                  # 301 → https://medioo.eliooo.org/app/home (old public name, path preserved)
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/app/                                      # 200, login page
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/baymax/home                               # 301 → /app/home
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/medscribeai/                              # 200, MedScribe PoC UI
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/api/v1/baymax/admin/storage/selftest  # 401 without the token
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


## Documents a restart interrupted (F9b)

A deploy is `docker rm -f` + `docker run`, and processing is detached, so whatever was being read when the old
container died is left RECEIVED or PROCESSING. Once the new instance is ready it sweeps them, one at a time, in the
background (the health check does not wait for it):

- the cutoff is the instant the instance started; anything updated after that is this instance's own work and is
  never touched;
- created more than 7 days ago (`baymax.recovery.max-age`): reported `skipped_too_old`, left as it is, nobody messaged;
- each re-run is counted in `document.recovery_attempts` and committed **before** the work; after 2
  (`baymax.recovery.max-attempts`) the document is failed `interrupted_repeatedly`, so a document that kills the JVM
  cannot crash-loop the container;
- pages not all in the `stored_object` ledger: failed `pages_missing_after_restart`;
- otherwise its leftover crops and items are cleared and it is re-run from its stored pages (model cost again);
- a document that reached DONE / NEEDS_RETAKE but has no explanation or retake message is explained if it was
  updated in the last 6 hours (`baymax.recovery.explain-max-age`); older ones are only counted, at WARN.

```bash
docker logs medscribe-ai --since 15m 2>&1 | grep -E 'recovery documentId=|recovery sweep|recovery: .* never explained'
```

One `[baymax] recovery documentId=<uuid> outcome=<code>` line per document (`reprocessed`, `explained`,
`skipped_too_old`, `failed_interrupted_repeatedly`, `failed_pages_missing`, `error`), then
`[baymax] recovery sweep done documents=<n>`. `outcome=error` (for example S3 unavailable) leaves the document as it
was, to be retried at the next start. A WARN `recovery: <n> finished document(s) older than PT6H were never explained`
is a count only; nothing was sent.

**Crash loop** (the container restarts every few minutes and the log shows a recovery line just before each death):
back up `~/medscribe.env`, add `BAYMAX_RECOVERY_ENABLED=false`, redeploy at the running tag, then find the document
by its `recovery documentId=` line and look at it before turning recovery back on. The attempt counter stops the
loop on its own after two restarts; the switch is for when two is too many.

Known gap: a restart in the second or two between the webhook's 200 and the document row being created leaves no
document to recover.

## Running the nudge job by hand (BMX-8)

```bash
curl -sS -X POST -H "X-Baymax-Admin-Token: $T" http://localhost:8086/api/v1/baymax/admin/nudges/evaluate   # {"composed": n}
```
Every outcome is a row in `baymax.nudge`; pending nudges appear under `GET /admin/messages/pending` like any message.

## Re-cropping documents (DR-12)

Crops are anchored on each item's text since BMX-5b. Documents extracted before that carry crops that may show
neighbouring text. Re-crop replays the stored `extraction_json` through the current cutter after OCR-ing the stored
page images again — **Vision cost only (~$0.0015/page), no model call**, items and crops replaced, the document's
model, confidence and date untouched. Not scheduled; run it by hand when a corpus predates a cutter change.

```bash
T=$(grep "^BAYMAX_ADMIN_TOKEN=" ~/medscribe.env | cut -d= -f2-)
curl -sS -X POST -H "X-Baymax-Admin-Token: $T" http://localhost:8086/api/v1/baymax/admin/documents/<id>/recrop   # one
curl -sS -X POST -H "X-Baymax-Admin-Token: $T" http://localhost:8086/api/v1/baymax/admin/documents/recrop        # all DONE
```

The response lists, per document, the stored counts per section and `unverified` (items the current cutter could
not place); a document that is not DONE is reported as `skipped_<status>` and left alone.
