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
| Containers | `medscribe-ai` (Java), image `ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai:<git sha>`, published on the box's `127.0.0.1:8086` only (W1c; Caddy reaches it as `medscribe-ai:8086` on `n8n_edge`), `--env-file`; and `medioo-web` (Next.js, DR-40, from W1b), image `ghcr.io/khondokertanvirhossain/elioo-health/medioo-web:<git sha>`, same sha, on `n8n_edge` only (no host port), no env file, 320 MB cap. **Caddy sends it only the paths listed in `docs/infra/Caddyfile.medioo`** (from W1c: `/app/healthz` alone; see [Routing a path to medioo-web](#routing-a-path-to-medioo-web)) |
| Runtime config | `/home/ec2-user/medscribe.env`, mode 600, passed to `docker run --env-file` |
| Database | Supabase `niramoy-rx`, session pooler, schemas `medscribe` and `baymax` |
| Object storage | AWS S3 `elioo-baymax-prod` (ap-south-1, private, SSE-S3) via the EC2 instance role (DR-5) |
| Deploy | push to `main` → GitHub Actions → GHCR → SSH → `deploy.sh`. Manually: `IMAGE_TAG=<git sha> bash ~/deploy.sh` |

`deploy.sh` first prunes old local tags of both its images (see [Disk](#disk) below), then refuses to go on if
`/` has less than the free-space floor, then pulls the Java tag, removes the old container, starts the new one with
`--env-file` and the GCP credentials mounted read-only, then polls `/actuator/health` for 90 seconds. It exits
non-zero and prints the last 50 log lines if the container never becomes healthy, so a failed deploy is loud.

**Java first, then Node** (DR-40 A2, `deploy_web` in `deploy.sh`, tested by `scripts/test_deploy_sh.sh` against fakes
and by `scripts/test_deploy_web_docker.sh` against the real image and daemon). Only once Java is healthy does
`deploy.sh` touch `medioo-web`: it pulls `medioo-web:<same sha>`, removes the old `medioo-web`, and starts the new
one. It runs with `--network n8n_edge`, no `-p` and no `--env-file`, so Node never holds Java's secrets. Its
environment is only `JAVA_INTERNAL_URL=http://medscribe-ai:8086` and `GIT_SHA=<sha>`. It also gets `--memory=320m`,
`--restart unless-stopped` and rotated logs (`max-size=10m`, `max-file=3`). Then `deploy.sh` waits up to 60 s
(wall clock; each try is capped at 5 s and its fetch at 2 s) for `/app/healthz`, fetched inside the container, to
answer `service == "medioo-web"` with `sha` (the `GIT_SHA` it was started with) **and** `build` (the `BUILD_SHA` CI
baked into the image) both equal to the tag `deploy.sh` holds. The `build` field is what makes it a check of the
image: a container started from an image built from another sha cannot pass. Needs coreutils `timeout` (the box has
`/usr/bin/timeout`).
- **The web step fails:** if the new `medioo-web` cannot start or never becomes healthy, the deploy exits 1 and
  prints its last 50 log lines, then **puts the previous `medioo-web` back** (the image it was running, with that
  sha) and checks it healthy. Java, already healthy, is not touched. On the first deploy there is no previous one,
  and the failed container is left in place to inspect (`docker logs medioo-web`).
- **No edge network:** `n8n_edge` missing fails the web step (exit 1) before the running `medioo-web` is touched. On
  any other network it would pass its in-container probe while Caddy could not reach it nor it Java.
- **The web pull fails.** If that tag is already on the box (a manual rollback, logged out, to a tag the prune kept),
  `deploy.sh` logs `web pull failed (<docker's error>); using the local <sha>` and deploys it. Otherwise:
  - GHCR's missing-tag answer (`manifest unknown` / `not found`; every sha before W1b has no web image): logs
    `web image <sha> not found; Java-only deploy (<docker's error>)`, leaves any running `medioo-web` as it is, and
    exits 0. **In CI** the deploy job passes `WEB_IMAGE_REQUIRED=1`, because it has just pushed that image: there a
    missing tag fails the deploy, after Java is up.
  - Any other error (`denied`/`unauthorized` on the private package, a rate limit, the network, no space left) is a
    fault, not a pre-W1b sha: `ERROR: could not pull web image <sha>: <docker's error>`, exit 1, whatever
    `WEB_IMAGE_REQUIRED` says, with Java deployed and `medioo-web` unchanged. A green run means the web is at the sha.

The web image is built (with `--build-arg BUILD_SHA=<sha>`) and pushed by the same deploy job, before anything
reaches the box. A web build failure stops the job with production untouched; the build job already built the same
Dockerfile on the PR (`scripts/test_deploy_web_docker.sh`).
After the deploy job, CI runs the https upstream table, `scripts/smoke-https.sh https://medioo.eliooo.org`
(tested by `scripts/test_smoke_https.sh`), through Caddy, the path every user takes. One row per path class, each
with its status and, for the Next page, the Java pages and the API, the security headers (`content-security-policy`
present and carrying a nonce on Next's path, `x-frame-options: DENY`, `x-content-type-options: nosniff`,
`referrer-policy: strict-origin-when-cross-origin`, `strict-transport-security` present). The expected values are
what production answered on 2026-10-21:

| Path | Expected | Served by |
|---|---|---|
| `/app/healthz` | 200, JSON `service == "medioo-web"`, nonce CSP | medioo-web (the only routed path) |
| `/actuator/health` | 200, JSON top-level `status == "UP"` | Java |
| `/app/` | 200, the login page (`name="whatsapp_number"`) | Java |
| `/` | 200 | Java (landing) |
| `/medscribeai`, `/medscribeai/` | 200 | Java (MedScribe) |
| `/baymax`, `/baymax/` | 301, `Location: /app` and `/app/` | Java |
| `/api/v1/baymax/wa/webhook` (GET, no parameters) | 403 (Meta verification refused) | Java |
| `/_next/static/x.js` | 404, JSON `statusCode == 400` (Java's error body, not Next's HTML 404) | Java, until `/_next/*` is routed |
| `https://baymax.eliooo.org/app/home` | 301, `Location: https://medioo.eliooo.org/app/home` (DR-15) | Caddy's old-name block |

Each of `x-frame-options`, `x-content-type-options`, `referrer-policy` and `strict-transport-security` must arrive
**exactly once** on the header rows: a browser acts on every copy (two conflicting `X-Frame-Options` are ignored, so
framing is allowed), so an append instead of a replace fails the row whatever the last value says.

The table is retried while Caddy reaches the new upstreams, within `SMOKE_DEADLINE` (default 90 s) for the whole run:
each request's `--max-time` shrinks to what is left, so a Caddy that accepts and never answers fails in 90 s instead
of holding the deploy job (CI's step also has `timeout-minutes: 3`). It prints only paths, status codes and header
values, never a body, because the Actions log is public (DR-38) and the health body carries disk, path and database
details. JSON rows read the top-level field with `jq`, never a grep: every health component also says
`"status":"UP"`.

Then CI checks the box's Caddyfile for **drift**: it copies `scripts/caddy-drift.sh` and
`docs/infra/Caddyfile.medioo` to `/home/ec2-user/medioo-ci/` and compares the box's live `medioo.eliooo.org` and
`baymax.eliooo.org` blocks with the repo's, read-only, on the box (so the n8n and bddrug blocks never reach the log).
It compares the file the **container** reads (`docker exec n8n-caddy-1 cat /etc/caddy/Caddyfile`, into a mode-600
temp file removed on exit), and fails too if that differs from the host file: an inode replaced by `sed -i`/`mv`
leaves Caddy on the old file while the host file looks right. A difference fails the job with
`box Caddyfile drifted from the repo — fold the box edit in first` and our blocks' diff; a block that never closes
fails as `unterminated` with no diff (its extraction would run into the other tenants' blocks). Fold the box edit into
the repo file in a PR; never paste the repo file over it.

**Java is off the public port (W1c).** `deploy.sh` publishes Java as `-p 127.0.0.1:8086:8086`, so `:8086` answers
only on the box itself (its health wait and the admin `curl`s in this runbook); everything from outside goes through
Caddy and TLS. Until W1c, host port 8086 served the whole app over plain HTTP to the internet, and CI's smoke checked
`http://<EC2 host>:8086`. Now every deploy asserts the opposite on the real box: `docker port medscribe-ai` must print
exactly `8086/tcp -> 127.0.0.1:8086` (the deploy step), and `http://<EC2 host>:8086` must not answer from the runner
(curl `000`, its own step). Anything that publishes 8086 again, such as a hand `docker run -p 8086:8086` during an
incident, turns the deploy red. The security group's inbound 8086 rule is still open, pending the PO/AWS (defence in
depth: nothing listens there any more).

`DEPLOY_FUNCTIONS_ONLY=1` is for `scripts/test_deploy_prune.sh`, `scripts/test_deploy_sh.sh` (both use the fakes in
`scripts/deploy_test_fakes.sh`) and `scripts/test_deploy_web_docker.sh`, which source `deploy.sh` for its functions.
Executing `deploy.sh` with it set fails on purpose rather than exiting 0 having deployed nothing.

## Disk

The root volume is 20 GB and shared with n8n and bddrug. Each `medscribe-ai` image is about 460 MB (456 MB on
2026-10-21; tags share layers where they can), and one lands on every merge to `main`. Check it with:

```bash
df -h /
docker images ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai --format '{{.Tag}} {{.CreatedAt}} {{.ID}}'
docker images ghcr.io/khondokertanvirhossain/elioo-health/medioo-web --format '{{.Tag}} {{.CreatedAt}} {{.ID}}'
```

`medioo-web` images (from W1b) are about 240 MB each (242 MB built locally on 2026-10-21), and tags share the
`node:22-alpine` base layers.

**The pruning rule** (`prune_repo` in `deploy.sh`; the rule is tested by `scripts/test_deploy_prune.sh`, and its use
on both repositories, before the free-space floor, by `scripts/test_deploy_sh.sh`): before every pull, remove local
tags of exactly each of our two repositories (`ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai` and
`ghcr.io/khondokertanvirhossain/elioo-health/medioo-web`), separately, keeping the newest 3 of each by creation time
(date and time of day; the tags are git shas, so name order means nothing). Never remove the tag being deployed (in
either repository), and never remove the image of a running container, whatever its age. Removal is by
`repo:tag`, never by image id, and never touches another repository: n8n's and bddrug's images belong to
their own deploys. A removal the daemon refuses is logged and skipped, so pruning cannot fail a deploy. Older
tags stay in GHCR, so a rollback to one of them is still `IMAGE_TAG=<sha> bash ~/deploy.sh`; if that sha is
still on the box it is kept and not pulled again from scratch, so a GHCR or network problem during an incident
cannot take the known-good image away.

**Steady state to plan disk against:** the prune runs before the pull, so after a deploy the box holds **up to
4** `medscribe-ai` tags (the 3 kept plus the new one), about 1.8 GB at 456 MB each, plus one more if a running
image older than those is protected, or if a rollback target is. Add up to 4 `medioo-web` tags, at most about
1 GB and less in practice because of the shared base layers. Both repositories are pruned before the free-space
floor is read, so space either one frees counts for both pulls. The one floor covers both pulls: the web pull,
about 240 MB, comes after the Java one.

**The free-space floor:** after the prune and before the pull, `deploy.sh` reads the free KB on `/`
(`df -Pk /`) and **refuses to pull below 1.5 GB (1572864 KB, about 3x the image)**, or when the figure cannot
be read. It logs `ERROR: only N KB free on / ... refusing to pull` and exits 1: the deploy job goes red and the
old container keeps serving. Why: the disk is shared, and a pull that takes `/` to zero does not hurt our
container (the pull comes before `docker rm -f`) but can corrupt or stop bddrug's Postgres, n8n and Caddy's
ACME/certificate storage. Pruning only keeps *our* repository in check; whatever else fills the disk, the floor
stops this deploy from finishing the job. To clear it, free space (start with `docker images` and the other
repositories' owners), then rerun the deploy. `MIN_FREE_KB=<n> IMAGE_TAG=<sha> bash ~/deploy.sh` overrides the
floor for one manual run; use it only knowing what else lives on `/`.

**Dangling images are no longer pruned by anything.** The old global `docker image prune -f` after each deploy
removed dangling (`<none>`) images of every repository on the box, n8n's and bddrug's included; it is gone, and
`prune_repo` skips `<none>`, so dangling `medscribe-ai` layers now accumulate too. Check during any disk look
with `docker images -f dangling=true` (and `docker system df`). Cleanup is a manual step for whoever is on the
box doing the disk check, today Tanvir: `docker image prune -f` removes only images no tag and no container
refers to, but it does so for every repository, so say so in the build report when you run it.

Why it runs before the pull and not after: the old `docker image prune -f` after a healthy start removed only
*dangling* images, and a tagged image is never dangling, so it never removed one of ours. Tags piled up until
**2026-10-21, when the box had 233 MB free** with 40 `medscribe-ai` tags on it; the next pull would have run out
of disk part-way through a deploy. That day, on the PO's instruction, 37 old tags were removed by hand, keeping
the running sha and the newest 2: free space went from **233 MB to 5.2 GB**. Nothing outside `medscribe-ai` was
touched. Most of the image tags left on the box are bddrug's (three repositories, 18 tags each, on
2026-10-21); those are bddrug's to prune, not this deploy's.

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
| `BAYMAX_AUTH_HMAC_SECRET` | **set** (`openssl rand -hex 32`) | Stands in for phone numbers in otp_code / web_session / audit_event. Blank = OTP login answers 503 (fail closed). Rotating it logs every family out. BMX-5. Since F9a (V22) it also keys every inbound WhatsApp message (`inbound_message.provider_key`): with `BAYMAX_WA_ENABLED=true` a blank value **refuses to start**, and rotating it makes Meta re-deliveries of messages received before the rotation look new (answered twice) for as long as Meta retries them. |
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
| `BAYMAX_WEB_PUBLIC_ORIGIN` | **absent** — no line at all (the default is `https://medioo.eliooo.org`), or exactly `https://medioo.eliooo.org`. **Never a blank line** | the one `Origin` a browser mutation under `/app/api` may come from (B1 CSRF rule). `${VAR:default}` falls back only when the variable is *unset*: a present-but-empty `BAYMAX_WEB_PUBLIC_ORIGIN=` resolves to "", and then every browser PUT/POST/DELETE answers 403 `FORBIDDEN_ORIGIN` (browsers always send `Origin` on those). Startup says so with one WARN (`baymax.web.public-origin is blank`); grep for it after a deploy. Set it only if the public host changes |
| `BAYMAX_WEB_PREVIEW_FAMILIES` | absent, or bare comma-separated family UUIDs: no quotes, no spaces, no comment. Check with `cat -A` before the redeploy | the families that see the Next.js screens before a route flips (`/app/api/me` → `preview`). Absent or blank = nobody previews. An entry that is not a family id (a typo, a truncated id) is skipped — that family simply does not preview — and startup logs one WARN with the count of skipped entries; it never refuses to start. Quotes `docker --env-file` keeps are stripped, but do not rely on it |

**Must be blank** in production:

| Variable | Why it must be empty |
|---|---|
| `BAYMAX_STORAGE_ENDPOINT` | any value overrides the AWS endpoint. A whitespace-only value is *not* empty and produces an invalid endpoint override |
| `BAYMAX_STORAGE_ACCESS_KEY` | any value is used as a static credential |
| `BAYMAX_STORAGE_SECRET_KEY` | same |
| `BAYMAX_AUTH_WEB_MINTS_CODE` | laptop only (WhatsApp off): the web form mints for an account number without the window. Startup refuses `true` with `BAYMAX_WA_ENABLED=true` |
| `BAYMAX_OTP_DELIVERY` | unused since DR-39 (PR 12); delete the line (harmless if left) |

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
| `BAYMAX_WA_TEST_NUMBER` | optional; the one E.164 number `scripts/wa-replay-callback.py` may send as (F9a) | Not read by the app. Today Tanvir's number. Can be passed on the command line instead of written here; see "Trying it from the server" under F9a |
| `BAYMAX_WA_PUBLIC_NUMBER` | **set**: the display number of `BAYMAX_WA_PHONE_NUMBER_ID`, digits only, country code first (`8801…`), no plus, spaces, quotes or comment | the sign-in page's "WhatsApp খুলে কোড আনুন" link (`wa.me/<n>?text=কোড`, DR-39). Blank = no button (WARN at startup); malformed = startup refused, which takes MedScribe down too, so check it with `cat -A` before the deploy |
| `BAYMAX_WA_SERVICE_WINDOW` | blank (defaults `PT23H`) | a web sign-in code is sent to WhatsApp automatically only if the number's own last message is at most this old (WhatsApp's free window is 24 h). Startup refuses zero or more than 24 h |
| `BAYMAX_WA_GRAPH_VERSION` | **blank** (= the shipped default, `v25.0`) | set `v21.0` **only to roll back** after a Graph error on v25.0, then remove the line again. Surrounding spaces and a CR are dropped; a value not shaped like `v25.0` inside them (`21.0`, `v21.0 # rollback`) refuses to start (it takes MedScribe down too: check with `cat -A`); a version within 90 days of its expiry, past it, or not in the expiry table logs a WARN containing `graph version` and still starts. See "Graph API version" below |
| `BAYMAX_WA_READ_RECEIPTS` | blank (= `off`) | `off` / `read` (blue ticks on every message Medioo answers) / `typing` (blue ticks, plus typing dots while a photo, a PDF or the বিস্তারিত answer is prepared). Never for a number the allowlist refuses. Case and surrounding spaces are forgiven (`Typing` is `typing`); anything else refuses to start. Log line: `wa read receipt sent ref=<digest> typing=<bool>`; a failure, or no answer from Graph within 2 s, is `wa read receipt failed … status=<code or TimeoutException>` and never holds back or costs the reply. **What to expect on the phone:** the dots are visible only for বিস্তারিত (the answer is prepared before the reply). A photo or PDF gets its acknowledgement at once, and WhatsApp clears the dots at our next message, so a photo shows the ticks and at most a brief flicker of dots |
| `BAYMAX_GATE_MODE` | blank (= `off`) | `off` / `all` / `urgency` (DR-13). Nobody changes it to try something on the phone; a gate change is a PO decision |

## Graph API version (plan PR 10)

Every Graph call (sends, media lookups and downloads, read receipts) uses `baymax.wa.graph-version`, shipped as
`v25.0` (expires 2028-07-29). The previous pin, `v21.0`, expires **2027-01-21**. Expiry dates come from
https://developers.facebook.com/docs/graph-api/changelog/versions and live in `WaConfigValidator.GRAPH_EXPIRY`
(fetched 2026-10-21). Startup warns (a WARN containing `graph version`, never a refusal) from 90 days before the
version in use expires.

**The build is a calendar alarm too.** `WaConfigValidatorTest.theShippedGraphVersionIsFarFromItsExpiry` needs at
least 180 days left on the shipped version (UTC), so for v25.0 the build goes red from **2028-02-01**, with no code
change, on every PR (`main` requires "Build and test", admins included). Remedy, before that date: re-fetch the
changelog, add the next version's row to `WaConfigValidator.GRAPH_EXPIRY`, bump `DEFAULT_GRAPH_VERSION` and
`baymax.wa.graph-version` in `application.properties`, and do the PO steps below again. A hotfix that cannot wait for
the migration: `env -u GITHUB_TOKEN gh variable set GRAPH_EXPIRY_GUARD_SKIP --body true` (CI passes it to the build,
which then skips only that test), merge, and `env -u GITHUB_TOKEN gh variable delete GRAPH_EXPIRY_GUARD_SKIP` right
after. Reminder for the PO: put 2028-01-15 in the calendar.

### Switching version: in this order, one change at a time

The outbound version is ours (this property). The version of the callbacks Meta sends us is set only in the Meta app
dashboard, and a callback version left on v21.0 stops when v21.0 does. The two are switched **separately**, so a
failure can be told apart and undone on its own.

```bash
L() { docker logs medscribe-ai --since 15m 2>&1 | sed 's/\x1b\[[0-9;]*m//g'; }
```

**(a) Deploy, dashboard webhook still on v21.0. Confirm outbound on v25.0.**

1. The version in use is v25.0, positively: `L | grep -c 'wa config ok: .*graphVersion=v25.0 '` is **1**. (0 means
   the validator never ran, WhatsApp is off, or a stray `BAYMAX_WA_GRAPH_VERSION` line overrides it; check with
   `grep -c '^BAYMAX_WA_GRAPH_VERSION=' ~/medscribe.env`.) Then `L | grep 'graph version'` prints nothing.
2. Send "Hi" from an allowlisted phone. `L | grep -E 'wa text route=|wa reply key=|wa outbound sent ref='` shows the
   route, the reply key and `ref=<digest>`, and the greeting arrives on the phone.
3. Send `t1-routine-lab.jpg` (the plan's test set) from the same phone. This is the only step that calls
   `GET /v25.0/{media-id}` and the media download, the calls most likely to differ between versions.
   `L | grep -E 'wa media resolved id=|wa media downloaded id=|wa intake accepted'` shows **all three** lines,
   `L | grep -c 'wa intake failed'` is 0, and the ack "ছবিটা পেয়েছি…" and then the explanation arrive on the phone.
   **First rule out the free cap:** since PR 7 the cap is checked before the ack, so a family at it gets only "এই
   মাসে বিনামূল্যের…", nothing is downloaded, and no media line appears. If `L | grep 'wa intake outcome=FREE_TIER'`
   prints a line, step 3 is **inconclusive, not failed**: rerun with a family under the cap (`BAYMAX_FREE_MAX_DOCS_PER_MONTH`, 3 papers
   per UTC month by default).
4. Leak check, a number matched as a whole field so a media id or the phone-number id containing `8801…` cannot
   raise a false alarm: `L | grep -cE 'wamid\.|(^|[^0-9])\+?8801[0-9]{9}([^0-9]|$)'` is 0.

**Outbound rollback**, if step 2 or 3 fails: no greeting, `wa media resolved` missing, a `wa intake failed` line,
or a Graph 4xx/5xx in the log. A `wa intake outcome=FREE_TIER` line (the phone shows "এই মাসে বিনামূল্যের…" alone)
is not one of these: the photo never reached Graph, so it says nothing about the version; rerun step 3 with a family
under the cap before rolling back. Back up the env, add `BAYMAX_WA_GRAPH_VERSION=v21.0` on its own line, redeploy the
same tag, rerun (a) expecting `graphVersion=v21.0`, and record it in the build report. Remove the line once the cause
is fixed; it must be gone before 2027-01-21.

**(b) Only after (a) passes: Meta app dashboard → WhatsApp → Configuration → Webhook → set the webhook version to
`v25.0`.** No deploy. Then send "Hi" once more and confirm `L | grep -c 'wa inbound ref='` went up by one (the
webhook handler's arrival line), `L | grep 'wa text route='` shows the new message, the reply arrives, and the leak
check in (a) step 4 is still 0. Send the photo again too if time allows.

**Inbound rollback**, if within a minute of the message no `wa inbound ref=` line appears (callbacks not arriving), or it appears with no `wa text route=` line after it (arriving but not parsed), or no reply comes: set the dashboard webhook
version back to `v21.0`. No redeploy, and independent of `BAYMAX_WA_GRAPH_VERSION`, which moves outbound calls only and
cannot undo this. The webhook answers 200 either way, so the family hears nothing: this check is the only signal.
Record it in the build report.

## Sign-in codes: break-glass (DR-39)

Codes reach the family only over WhatsApp, and no log line carries one above DEBUG. If a family cannot get a code
(the log says `otp NOT delivered phoneHash=… reason=window_expired|token_invalid|not_allowlisted|channel_off|send_failed|no_message_id`):

1. Back up the env (`cp ~/medscribe.env ~/medscribe.env.bak-$(date +%Y%m%d-%H%M)`), add
   `LOGGING_LEVEL_COM_ELIOO_BAYMAX_WEB_ADAPTER_OUT_DELIVERY=DEBUG`, redeploy.
2. The family sends কোড (or asks on the web). `docker logs medscribe-ai --since 5m 2>&1 | grep 'otp fallback'` shows
   the code against the phone hash; read it to the family by phone yourself.
3. Remove the line and redeploy at once: the code is in the log for as long as it stays.

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
L | grep -cE 'OTP for|otp fallback|\bcode=[0-9]{6}\b'                                        # 0: a sign-in code (DR-39); 0 whenever the DEBUG break-glass is off
L | grep -c 'otp delivered channel=whatsapp'                                                   # >= 1 after a sign-in try in this window, or the line above passed on an empty log
```

The sign-in count matches the field (`code=` and six digits) as well as the two known line shapes, so a code leaking
in some new wording still counts. It is only meaningful over a window that contains a sign-in: try one (web inside the
window, or কোড on WhatsApp) within the same `--since 15m`, and check the second line is not 0.

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
curl -s -H "X-Baymax-Admin-Token: $TOK" http://127.0.0.1:8086/api/v1/baymax/admin/storage/selftest
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
curl -s -D - https://medioo.eliooo.org/app/api/me                                                            # 401 {"reason":"UNAUTHENTICATED"}, Cache-Control: no-store, no Location (never a 303)
curl -s -D - -X POST https://medioo.eliooo.org/app/api/sign-in/request -H 'X-Medioo: 1' -H 'Content-Type: application/json' -d '{}'  # 403 {"reason":"FORBIDDEN_ORIGIN"}, Cache-Control: no-store: the sessionless sign-in refuses a request with no Origin, and Caddy sends it to Java; sends nothing
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/baymax/home                               # 301 → /app/home
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/medscribeai/                              # 200, MedScribe PoC UI
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/api/v1/baymax/admin/storage/selftest  # 401 without the token
```

Baymax health returning 404 in production means the module did not load: `BAYMAX_ENABLED` is not `true`.

The second container (from W1b), on the box. From outside, it answers only the paths Caddy routes to it
(`docs/infra/Caddyfile.medioo`; from W1c, `/app/healthz`):

```bash
docker ps --format '{{.Names}} {{.Image}} {{.Status}}' | grep -E '^(medscribe-ai|medioo-web) '   # both, same sha, medioo-web "(healthy)"
docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' medioo-web                          # under 320 MiB (about 45 MiB idle)
docker port medioo-web                                                                          # prints nothing: no host port
docker port medscribe-ai                                                                        # 8086/tcp -> 127.0.0.1:8086 only (W1c)
curl -s https://medioo.eliooo.org/app/healthz                                                   # {"service":"medioo-web","sha":<sha>,"build":<sha>}
free -m                                                                                         # "available" stays at 1 GB or more
```

The Spring Framework the running container actually carries (build.gradle overrides Boot 3.4.2's 6.2.2, which
hangs a multipart press of several photos, spring-framework#34178). Every line must be the same version, 6.2.19 or
later. The image build already refuses otherwise (`scripts/check-framework-jars.sh`, which reads each jar's manifest
and runs on every PR in CI's builder-stage build and again in the deploy job); this is a by-name look at what deployed:

```bash
docker exec medscribe-ai sh -c 'ls /workspace/app/lib' | grep -E '^spring-[a-z-]+-[0-9]' | grep -vE '^spring-(boot|data|security)'   # all 6.2.19
```

Rolling back across the Framework bump: see [Rollback](#rollback).

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

## Routing a path to medioo-web

DR-40 A1: Java is the default upstream. A path reaches `medioo-web:3000` only once it is listed in
`docs/infra/Caddyfile.medioo` **and** applied in the box's Caddyfile, `/home/ec2-user/n8n/Caddyfile`, which also holds
the n8n and bddrug sites (one reload touches all three tenants). From W1c the repo file is the source; the box's
medioo/baymax blocks are pasted from it. Routed today: `/app/healthz` (W1c). `/_next/*` and `/fonts/*` come with the
first HTML route.

To flip a route (or flip one back, which is the same procedure with the block removed):

1. **PR first.** Add the `handle <path> { reverse_proxy medioo-web:3000 }` block to `docs/infra/Caddyfile.medioo`,
   and the path's row to `scripts/smoke-https.sh` (with its test). The ship stage applies it to the box before the
   PR merges, so the merge's deploy finds the box and the repo agreeing. **Hold every other merge from the box edit
   (step 4) until this PR's deploy is green:** any PR merged in between deploys `main`'s Caddyfile.medioo, and its
   drift step goes red against the edited box.
2. **Diff the box against the repo first.** On the box, with the copy of `main`'s file CI leaves after every deploy:
   `bash ~/medioo-ci/scripts/caddy-drift.sh ~/medioo-ci/docs/infra/Caddyfile.medioo ~/n8n/Caddyfile`.
   It must print `... match the repo`. Any difference is a box edit: fold it into the repo file first, never
   overwrite it. **Bootstrap (the first flip, W1c):** `~/medioo-ci/` does not exist until the first deploy that has
   the drift step, so copy it by hand from a checkout, `main`'s file for this step and the PR's to its own path:
   ```bash
   ssh <box> 'mkdir -p ~/medioo-ci/scripts ~/medioo-ci/docs/infra ~/medioo-ci/pr<N>'
   git show origin/main:docs/infra/Caddyfile.medioo > /tmp/main.medioo
   scp scripts/caddy-drift.sh <box>:medioo-ci/scripts/          # from the PR's checkout: the checker under review
   scp /tmp/main.medioo <box>:medioo-ci/docs/infra/Caddyfile.medioo
   scp docs/infra/Caddyfile.medioo <box>:medioo-ci/pr<N>/Caddyfile.medioo   # for step 6
   ```
   Paste the step 2 and step 6 result lines into the PR.
3. **Record the tenants' answers before the edit:**
   `for h in medioo.eliooo.org drugdb.eliooo.org <n8n host>; do curl -s -o /dev/null -w "$h %{http_code}\n" https://$h/; done`.
4. **Back up, then write in place only.** The Caddyfile is a single-file bind mount into `n8n-caddy-1`: `sed -i`,
   `mv` of a temp file, `rsync` or an editor that saves by rename replace the inode, and the container keeps reading
   the old file while `caddy reload` reports success.
   ```bash
   cd /home/ec2-user/n8n
   cp Caddyfile Caddyfile.bak-$(date +%Y%m%d-%H%M)
   cat /path/to/new-Caddyfile > Caddyfile             # the old file with ONLY our two blocks replaced
   docker exec n8n-caddy-1 cat /etc/caddy/Caddyfile | diff - Caddyfile          # must print nothing
   docker exec n8n-caddy-1 caddy validate --config /etc/caddy/Caddyfile        # must say Valid configuration
   docker exec n8n-caddy-1 caddy reload --config /etc/caddy/Caddyfile
   ```
5. **Check all three tenants:** the same `curl` loop as step 3 must print the same codes. Any change: restore the
   backup (`cat Caddyfile.bak-<stamp> > Caddyfile`), confirm the container sees it, reload.
6. **Run the table:** `scripts/smoke-https.sh https://medioo.eliooo.org` from the PR's checkout. Every row PASS.
   Then `caddy-drift.sh` with the PR's `Caddyfile.medioo` against the box: it must match
   (`bash ~/medioo-ci/scripts/caddy-drift.sh ~/medioo-ci/pr<N>/Caddyfile.medioo ~/n8n/Caddyfile`). Merge the PR next;
   other merges stay held until its deploy is green.
7. **Record the routes floor** once the PR has merged (its sha is the squash-merge commit on `main`; it does not exist
   at step 4). **Required:** until the file is written, `deploy.sh` enforces nothing and logs "no route has flipped"
   although one has. Post the `cat` output on the PR.
   ```bash
   cp ~/medioo-routes-floor ~/medioo-routes-floor.bak-$(date +%Y%m%d-%H%M) 2>/dev/null || true
   echo "<merge sha> <path> $(date -u +%F)" > ~/medioo-routes-floor
   cat ~/medioo-routes-floor
   ```
   From then on `deploy.sh` refuses an older sha ([Rollback](#rollback)). Advancing the floor at each flip also keeps
   the compare range short.

Headers: the medioo block deletes `Server`, which defers its header block to response-write time, so Caddy
overwrites HSTS, `X-Content-Type-Options` and `Referrer-Policy` on every path, Java's or Next's.
`Referrer-Policy` stays `strict-origin-when-cross-origin`, the value production has always sent; changing it is a
PO decision. Each upstream owns its own CSP and `X-Frame-Options` (Next's CSP carries a per-request nonce).

## Rollback

Redeploy the previous image tag; every merge to `main` is tagged with its git sha.

```bash
IMAGE_TAG=<previous git sha> bash ~/deploy.sh
```

**One `IMAGE_TAG` rolls back both containers** (DR-40 A2). Both images carry the sha of the merge that built
them, so the one variable sets Java and `medioo-web` together, Java first. The limit is the routes floor below.
- **Neither copy of the target is pruned:** the tag being deployed is protected in both repositories, so a rollback
  to a sha still on the box keeps its Java and web images (`rollback-both` in `scripts/test_deploy_sh.sh`).
- **Rollback to a sha from before W1b:** GHCR has no `medioo-web` image for it. `deploy.sh` deploys Java only, logs
  `web image <sha> not found; Java-only deploy (Error response from daemon: manifest unknown)`, exits 0, and leaves
  the newer `medioo-web` running. That is safe, because API changes are additive only (DR-40 A2).
- **A manual run is not logged in to GHCR** (the CI deploy logs out on every exit). If the web tag is still on the
  box, a failed pull uses that copy (`using the local <sha>`). If it is not, and the `medioo-web` package is private,
  the pull answers `denied`, and the run **fails** (exit 1, `ERROR: could not pull web image ...`) after Java is
  rolled back: it does not report a Java-only success, because the web would then be at another sha. GHCR may give
  the same `denied` for a pre-W1b sha of a private package, so log in first for any manual rollback
  (`docker login ghcr.io`, then `docker logout ghcr.io` afterwards), or have the package made public like
  `medscribe-ai`'s (PO choice).
- **A failed web swap puts the previous one back**, so a rollback whose web image is broken leaves the web where it
  was and turns the run red; Java stays at the rollback sha.
- **Taking `medioo-web` away entirely:** after W1c, first revert its routes as below. Then
  `docker rm -f medioo-web`.

**Rollback floor (DR-40 A2, from the first route flip in W1c).** Caddy routing is state on the box, not tied to
`IMAGE_TAG`. Each route flip records its merge sha in `/home/ec2-user/medioo-routes-floor` (see
[Routing a path to medioo-web](#routing-a-path-to-medioo-web)), and `deploy.sh` **refuses** an `IMAGE_TAG` that is
not that sha or a descendant of it, before touching anything:
`ERROR: IMAGE_TAG <sha> is not a descendant of the routes floor <floor> (GitHub: behind)`. The box has no clone, so
the ancestry comes from GitHub's compare API (one unauthenticated call, `?per_page=1&page=2` so the answer carries the
status and no files list, `--max-time 30`, retried once; the repository is public; 60 calls per hour per IP); an API
that cannot be reached twice in a row, or a floor file that does not start with a sha, is refused too, not passed. No floor file means no route
has flipped: nothing is enforced. To roll back below the floor:

1. Revert the routes flipped since the target sha: edit the box's Caddyfile back as in the procedure above (back up,
   write in place only, confirm the container sees it, validate, reload) and fold the same revert into
   `docs/infra/Caddyfile.medioo` in a PR, or the next deploy's drift check goes red.
2. Record the older floor (the merge sha of the most recent flip still in place), or remove the file when no route
   remains flipped: `cp ~/medioo-routes-floor ~/medioo-routes-floor.bak-$(date +%Y%m%d-%H%M)`, then write it.
3. `IMAGE_TAG=<sha> bash ~/deploy.sh`, then `scripts/smoke-https.sh https://medioo.eliooo.org` from a checkout of
   that sha (its table matches its routes).

`ROUTES_FLOOR_FORCE=1 IMAGE_TAG=<sha> bash ~/deploy.sh` overrides the refusal with a `WARNING: ROUTES_FLOOR_FORCE=1`
line, for an emergency where the floor itself is the problem (GitHub down, a wrong floor file); it does not make an
unrouted path work. It is deliberately not `FORCE=1`, which `scripts/draft-labels.sh` reads: a stray export of that
overrides nothing (`floor-generic-force`). CI has no override: when GitHub's API is down, deploy by hand with it.
Rolling back past W1b leaves a newer `medioo-web` running; rolling Java back past an `/app/api` endpoint a flipped
route calls breaks that route for every family. The first flip is `/app/healthz` (W1c), which nothing but the smoke
and monitoring calls.

Migrations do not roll back. A release that adds a column is safe to roll back this way; one that changes the
meaning of existing data is not, and needs a forward fix. **D2's filed-day migration (plan D2, `filed_day_and_uncertain_date`)
is one of these:** it redefines `document.timeline_date` so a retake is filed under its Dhaka upload day. An image older
than it starts against it (Flyway ignores a future migration) but still computes `doc_date IS NULL AS
date_is_fallback` in the timeline and earlier-readings SQL, so every retake would show under its upload day with no
"(আপলোডের তারিখ)" mark, an upload day passing as a visit day. Roll forward with a fix, never back past it.
**B3's `now_ack` migration (plan B3) is another:** `now_ack` references `document`, `patient_profile` and
`family_account` with no cascade, and only images from B3 on delete its rows. An older image starts against it, but
once any দেখেছি row exists its `deleteDocument`, `deletePatient` and `deleteFamily` all fail on a `now_ack_*_fkey`
constraint, so delete-on-request (§6.3, 24h) is broken for those families. Rolling Java back past B3 requires
`DELETE FROM baymax.now_ack` first (the taps are a display preference; losing them only shows the NOW banner again),
or a forward fix; the old deletes do not know the table. To take Baymax out of service without changing
version, set `BAYMAX_ENABLED=false` and redeploy: routes and migrations stop, MedScribe is untouched.

**Rolling back across the Framework bump (B4b #109, #114).** Images built from `579a35a` onward carry Framework
6.2.19; the ones before carry Boot 3.4.2's 6.2.2, and with it the multipart hang (spring-framework#34178). Which images
those are does not change; what a rollback to one costs does, with every merge, so check it at the time:

1. **Prefer the forward path.** A PR that sets `ext['spring-framework.version']` in the root `build.gradle` to the
   version wanted needs no schema reasoning. Both guards refuse anything below 6.2.19, so going lower also means
   lowering the floor in the same PR (`FIXED` in `ShippedSpringFrameworkVersionTest`, `FLOOR_*` in
   `scripts/check-framework-jars.sh`): a decision, and it brings the hang back.
2. **An image rollback reverts every merge after the target, migrations included.** List them first:

   ```bash
   running=$(docker inspect -f '{{.Config.Image}}' medscribe-ai | awk -F: '{print $NF}')   # on the box
   git diff --name-only <target sha> <running sha> -- '*/db/migration/' '*/db/baymax/migration/'   # in a checkout
   ```

   Anything listed is checked against the paragraph above: each must be additive (nullable or defaulted columns, no
   `NOT NULL` without a default, no renamed or dropped column the old code reads), and none may be one that rules a
   rollback out. D2's `filed_day_and_uncertain_date` (#117) is one, so no image older than #117 is a rollback target.
3. **The box keeps only the newest 3 tags** (the prune in `deploy.sh`), so an older image comes from GHCR:
   `docker login ghcr.io` first, as for any manual rollback.

Restoring configuration: the backups are `~/medscribe.env.bak.*`.

```bash
cp ~/medscribe.env.bak.<timestamp> ~/medscribe.env && chmod 600 ~/medscribe.env
IMAGE_TAG=$(docker inspect -f '{{.Config.Image}}' medscribe-ai | awk -F: '{print $NF}') bash ~/deploy.sh
```

## Reading logs

```bash
docker logs --tail 200 medscribe-ai 2>&1 | sed 's/\x1b\[[0-9;]*m//g'
docker logs medscribe-ai 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep '\[baymax\]'
docker logs --tail 200 medioo-web 2>&1          # Next.js; rotated at 10 MB x 3 files
docker exec medioo-web node -e "fetch('http://127.0.0.1:3000/app/healthz').then(r=>r.text()).then(console.log)"   # sha and build: both the deployed sha
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
- a document that is DONE / NEEDS_RETAKE with no explanation or retake message carrying a body (a fail-closed
  `failed_safety` row alone is no answer), or FAILED with no `failure` notice (PR 9, V23), is answered if it was
  updated in the last 6 hours (`baymax.recovery.explain-max-age`); older ones are only counted.

```bash
docker logs medscribe-ai --since 15m 2>&1 | grep -E 'recovery documentId=|recovery sweep|recovery: .* (never explained|no failure notice)'
```

One `[baymax] recovery documentId=<uuid> outcome=<code>` line per document (`reprocessed`, `explained`,
`skipped_too_old`, `failed_interrupted_repeatedly`, `failed_pages_missing`, `error`), then
`[baymax] recovery sweep done documents=<n>`. `outcome=error` (for example S3 unavailable) leaves the document as it
was, to be retried at the next start. `outcome=explained` on a FAILED document means the failure notice
(`failure.document`) was sent, up to 6 h after the failure; on a DONE document whose only row was `failed_safety`, it
was explained again (model cost) and, if that fails closed again, the code-only fallback went.

Two counts, nothing sent for either:

- WARN `recovery: <n> finished document(s) older than PT6H were never explained`: DONE / NEEDS_RETAKE only. Since PR 9
  it also counts DONE documents whose explanation failed closed before the fallback existed (their only row is
  `failed_safety`), so it may jump once after the PR 9 deploy and should then hold steady.
- INFO `recovery: <n> FAILED document(s) older than PT6H have no failure notice`: every FAILED document from before
  V23 is in it and never gets a notice, so it is non-zero on every start. Watch whether it **grows** between restarts:
  growth means a FAILED paper after PR 9 went unanswered.

The first start after the PR 9 deploy sends `দুঃখিত — এই কাগজটা এখন পড়া গেল না…` to every family whose paper FAILED
in the 6 h before it, logged as `outcome=explained`.

Explanation lines since PR 9, and how the standard `grep -cE ' ERROR '` check reads them:

- ERROR `[baymax] explanation threw documentId=… error=<Class> reason=<code>`: composition threw. `reason` is
  `medicine_elided`, `medicine_not_verbatim`, `diagnosis_not_verbatim` (DR-16/DR-26 transcription defects),
  `urgency_changed`, `template_checklist`, or `other` (a model or provider error: credits, outage). The exception text
  is at DEBUG only. Expected to be followed by the INFO fallback line below; an ERROR count above 0 after a deploy is
  worth one look per document even when the fallback went.
- INFO `[baymax] explanation fallback documentId=… urgency=… gate=… cause=<failed_safety|reason>`: the code-only,
  value-free fallback was stored (and delivered unless `gate=pending`). Logged only after it was released, never on
  an attempt. `grep -c 'explanation fallback'` should stay 0 in normal use.
- ERROR `[baymax] explanation threw and no fallback exists documentId=… reason=…` (or `explanation failed closed and
  no fallback exists`): nothing was sent; the original error follows from the processing chain
  (`explanation failed documentId=…`). Look at the document.

**Crash loop** (the container restarts every few minutes and the log shows a recovery line just before each death):
back up `~/medscribe.env`, add `BAYMAX_RECOVERY_ENABLED=false`, redeploy at the running tag, then find the document
by its `recovery documentId=` line and look at it before turning recovery back on. The attempt counter stops the
loop on its own after two restarts; the switch is for when two is too many.

Known gap: a restart in the second or two between the webhook's 200 and the document row being created leaves no
document to recover. Since F9a the message itself is recorded before the 200, so such a photo shows up in the startup
count below (`never answered before this restart`); a Meta re-delivery reclaims it, the rest are F9c.

## The same WhatsApp message twice (F9a)

Meta re-delivers a webhook it thinks we did not acknowledge. The webhook now **claims** every message before it
answers: one row in `baymax.inbound_message`, keyed by `HMAC(BAYMAX_AUTH_HMAC_SECRET, "wamid:" || id)` (never the
wamid, which is base64 of the sender's number). Only the claim that inserted the row goes on to reply; a re-delivery
is answered 200 and ignored. A committed claim is always dispatched, even if Meta hangs up before the answer.

- **503** when the claim fails for a reason a retry can fix (database unreachable, a missing table, the HMAC secret):
  Meta retries for up to 7 days and the claim makes the retry harmless. Messages of the same callback claimed before
  the failure are answered; the rest wait for the retry.
- **200, answered unkeyed** when the database refuses the row itself (SQLSTATE 22/23: a NUL in the text, an over-long
  id). It would refuse it on every retry, so a 503 would be a week-long loop; the message is answered without
  idempotency, like one with no provider id.
- **Reclaim**: a row claimed by the PREVIOUS container and never answered (a deploy's `docker rm -f` between the commit
  and the dispatch) is taken back, once, when Meta re-delivers it. A row received by the running container is never
  taken back (it is in flight).

What is kept: text and the Meta media id only for a sender with a family account (both deleted with the family; the
CHECK `inbound_message_content_needs_family` refuses either without one), never text that carries a standalone
six-digit run (a login code, however it is pasted back). Deleting one paper clears its row's media id, patient and
document and keeps the key. **Retention**: rows from a number with no family are deleted after 8 days (Meta retries
for 7; the key is all the dedupe needs), by `WaInboundRetentionJob` daily at 03:40 (`baymax.wa.inbound-purge-cron`).

Log lines (digests only, never a wamid or a number):

```bash
docker logs medscribe-ai --since 15m 2>&1 | grep -E 'wa inbound duplicate ref=|wa intake done ref=|asking Meta to retry|wa inbound unrecordable|wa inbound reclaimed|never answered before this restart|wa inbound purge'
```

- `wa inbound duplicate ref=<digest> ignored (already claimed)`: a re-delivery, not answered again. Expected now and then.
- `wa webhook: inbound could not be recorded, asking Meta to retry error=<class>`: a 503. More than a handful means the
  database is unreachable from the container; Meta will retry, nothing is lost yet.
- `wa inbound unrecordable ref=<digest> error=<class>: processed without idempotency`: a data error on one message;
  answered, but a Meta re-delivery of it would be answered again. Any at all is worth a look at the class.
- `wa inbound reclaimed: an earlier instance claimed it and never answered`: a deploy cut a message off and Meta's
  re-delivery is answering it now.
- At startup, WARN `<n> WhatsApp message(s) were received but never answered before this restart`: messages claimed by
  the previous container and cut off by THIS restart before their reply. Each is closed as `outcome = 'CUT_OFF'` when
  counted, so the next startup counts only what its own restart cut off (never a running total). Meta re-delivers one
  only if it never got the 200 (then it is reclaimed); the rest nobody re-sends yet (F9c, Phase 4). The count says how
  urgent that is. Put it in the build report.

```sql
-- in flight (NULL) or cut off by a restart (CUT_OFF), newest first; no text, no numbers
SELECT received_at, type, coalesce(outcome, 'in flight') AS state, family_id IS NOT NULL AS known_family
FROM baymax.inbound_message WHERE handled_at IS NULL OR outcome = 'CUT_OFF' ORDER BY received_at DESC LIMIT 20;
```

### Trying it from the server

A phone cannot make Meta re-deliver. `scripts/wa-replay-callback.py` builds the callback Meta would send for the text
"বিস্তারিত" from the number given as `--number` (with a message id of Meta's shape), signs it with
`BAYMAX_WA_APP_SECRET`, and POSTs the identical bytes twice. **It forges a message from that number**, so it refuses
unless the number is given explicitly, is on `BAYMAX_WA_ALLOWLIST`, and equals `BAYMAX_WA_TEST_NUMBER` (the dedicated
test number: Tanvir's, today); it never takes the first allowlist entry, which becomes a pilot family's number the day
one is added first. It prints the masked target and sends nothing without `--yes`. Standard library, python3 3.9 on the
box is enough. It reads the secret and the allowlist from `~/medscribe.env` itself; `BAYMAX_WA_TEST_NUMBER` is read from
the environment (or that file). `--dry-run` prints the path and body and sends nothing; the signature only with
`--show-signature` (with it, anyone holding the output can replay the request to the public webhook).

What one run does to the test number, as a real message from it would:

- **one WhatsApp reply is sent** to it (the pointer to the latest paper, or `wa.no_document` if none is done; the
  detail word may compose a detail message about that paper);
- **its 24 h WhatsApp window is recorded as open** (`wa_last_inbound`, DR-39): a web sign-in code may then be sent to
  it as free-form text;
- **an `inbound_message` row is stored**, with the text "বিস্তারিত".

```bash
scp -i <key> scripts/wa-replay-callback.py ec2-user@<box>:~/wa-replay-callback.py          # from the dev machine
BAYMAX_WA_TEST_NUMBER=+8801793399171 python3 -I ~/wa-replay-callback.py --number +8801793399171 --yes
#   prints: target ****9171, then 200, 200
docker logs medscribe-ai --since 5m 2>&1 | grep -c 'wa inbound duplicate'                   # 1
```

What proves it: two `200`s, **one** WhatsApp reply on the test phone, and exactly one `wa inbound duplicate` line.
Each run uses a new id (its tail carries the minute), so running it again a minute later is answered once more, as a
new message should be; run twice within the same minute, the second run's two POSTs are both duplicates and get no
reply.

## Running the nudge job by hand (BMX-8)

```bash
curl -sS -X POST -H "X-Baymax-Admin-Token: $T" http://127.0.0.1:8086/api/v1/baymax/admin/nudges/evaluate   # {"composed": n}
```
Every outcome is a row in `baymax.nudge`; pending nudges appear under `GET /admin/messages/pending` like any message.

## Re-cropping documents (DR-12)

Crops are anchored on each item's text since BMX-5b. Documents extracted before that carry crops that may show
neighbouring text. Re-crop replays the stored `extraction_json` through the current cutter after OCR-ing the stored
page images again — **Vision cost only (~$0.0015/page), no model call**, items and crops replaced, the document's
model, confidence and date untouched. Not scheduled; run it by hand when a corpus predates a cutter change.

```bash
T=$(grep "^BAYMAX_ADMIN_TOKEN=" ~/medscribe.env | cut -d= -f2-)
curl -sS -X POST -H "X-Baymax-Admin-Token: $T" http://127.0.0.1:8086/api/v1/baymax/admin/documents/<id>/recrop   # one
curl -sS -X POST -H "X-Baymax-Admin-Token: $T" http://127.0.0.1:8086/api/v1/baymax/admin/documents/recrop        # all DONE
```

The response lists, per document, the stored counts per section and `unverified` (items the current cutter could
not place); a document that is not DONE is reported as `skipped_<status>` and left alone.
