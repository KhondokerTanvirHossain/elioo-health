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
| Containers | `medscribe-ai` (Java), image `ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai:<git sha>`, host port 8086, `--env-file`; and `medioo-web` (Next.js, DR-40, from W1b), image `ghcr.io/khondokertanvirhossain/elioo-health/medioo-web:<git sha>`, same sha, on `n8n_edge` only (no host port), no env file, 320 MB cap. **No route sends `medioo-web` anything until W1c** |
| Runtime config | `/home/ec2-user/medscribe.env`, mode 600, passed to `docker run --env-file` |
| Database | Supabase `niramoy-rx`, session pooler, schemas `medscribe` and `baymax` |
| Object storage | AWS S3 `elioo-baymax-prod` (ap-south-1, private, SSE-S3) via the EC2 instance role (DR-5) |
| Deploy | push to `main` → GitHub Actions → GHCR → SSH → `deploy.sh`. Manually: `IMAGE_TAG=<git sha> bash ~/deploy.sh` |

`deploy.sh` first prunes old local tags of both its images (see [Disk](#disk) below), then refuses to go on if
`/` has less than the free-space floor, then pulls the Java tag, removes the old container, starts the new one with
`--env-file` and the GCP credentials mounted read-only, then polls `/actuator/health` for 90 seconds. It exits
non-zero and prints the last 50 log lines if the container never becomes healthy, so a failed deploy is loud.

**Java first, then Node** (DR-40 A2, `deploy_web` in `deploy.sh`, tested by `scripts/test_deploy_sh.sh`). Only
once Java is healthy does `deploy.sh` touch `medioo-web`: it pulls `medioo-web:<same sha>`, removes the old
`medioo-web`, and starts the new one. It runs with `--network n8n_edge`, no `-p` and no `--env-file`, so Node never
holds Java's secrets. Its environment is only `JAVA_INTERNAL_URL=http://medscribe-ai:8086` and `GIT_SHA=<sha>`. It
also gets `--memory=320m`, `--restart unless-stopped` and rotated logs (`max-size=10m`, `max-file=3`). Then
`deploy.sh` waits up to 60 s for `/app/healthz`, fetched inside the container, to answer
`service == "medioo-web"` with this sha.
- **The web step fails:** if the web never becomes healthy, the deploy exits 1 and prints its last 50 log lines.
  Java, already healthy, is not touched, and the failed `medioo-web` is left in place to inspect
  (`docker logs medioo-web`).
- **No web image for the sha:** if `docker pull` of the web image fails (every sha before W1b has none), `deploy.sh`
  logs `web image <sha> not found; Java-only deploy (<docker's error>)`, leaves any running `medioo-web` as it is,
  and exits 0.
- **In CI:** the deploy job passes `WEB_IMAGE_REQUIRED=1`, because it has just pushed that image. A failed pull is
  then an auth or registry fault and fails the deploy, after Java is up, instead of passing for a pre-W1b sha.

The web image is built and pushed by the same deploy job, before anything reaches the box. A web build failure stops
the job with production untouched.
After the deploy job, CI's smoke test checks both `http://<EC2 host>:8086/actuator/health` and
`https://medioo.eliooo.org/actuator/health`; both must pass, because a healthy container behind a broken Caddy
or certificate is still an outage. It prints only the HTTP status, never the health body, because the Actions
log is public (DR-38) and the body carries disk, path and database details.

**Known exposure until plan W1c:** the `:8086` check works only because host port 8086 is open to the
internet, which serves the whole app (login and admin routes included) over plain HTTP, bypassing Caddy and
TLS. The PR that binds Java to `127.0.0.1` (W1c) must remove the `:8086` check from the smoke step in the same
change, or every deploy after it turns `main` red.

`DEPLOY_FUNCTIONS_ONLY=1` is for `scripts/test_deploy_prune.sh` and `scripts/test_deploy_sh.sh`, which source
`deploy.sh` for its functions (both use the fakes in `scripts/deploy_test_fakes.sh`).
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
`node:22-alpine` base layers. The same pruning rule applies to that repository, separately: each one keeps its own
newest 3.

**The pruning rule** (`prune_repo` in `deploy.sh`, tested by `scripts/test_deploy_prune.sh`): before every
pull, remove local tags of exactly `ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai`, keep the newest 3
by creation time (date and time of day; the tags are git shas, so name order means nothing), never remove the
tag being deployed, and never remove the image of a running container, whatever its age. Removal is by
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
curl -s -D - https://medioo.eliooo.org/app/api/me                                                            # 401 {"reason":"UNAUTHENTICATED"}, Cache-Control: no-store, no Location (never a 303)
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/baymax/home                               # 301 → /app/home
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/medscribeai/                              # 200, MedScribe PoC UI
curl -s -o /dev/null -w '%{http_code}\n' https://medioo.eliooo.org/api/v1/baymax/admin/storage/selftest  # 401 without the token
```

Baymax health returning 404 in production means the module did not load: `BAYMAX_ENABLED` is not `true`.

The second container (from W1b), on the box. Until W1c nothing outside the box can reach it, by design:

```bash
docker ps --format '{{.Names}} {{.Image}} {{.Status}}' | grep -E '^(medscribe-ai|medioo-web) '   # both, same sha, medioo-web "(healthy)"
docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' medioo-web                          # under 320 MiB (about 45 MiB idle)
docker port medioo-web                                                                          # prints nothing: no host port
free -m                                                                                         # "available" stays at 1 GB or more
```

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

**One `IMAGE_TAG` rolls back both containers** (DR-40 A2). Both images carry the sha of the merge that built
them, so the one variable sets Java and `medioo-web` together, Java first. The limit is the routes floor below.
- **Rollback to a sha from before W1b:** there is no `medioo-web` image for it. `deploy.sh` deploys Java only, logs
  `web image <sha> not found; Java-only deploy`, exits 0, and leaves the newer `medioo-web` running. That is safe,
  because API changes are additive only (DR-40 A2).
- **Taking `medioo-web` away entirely:** after W1c, first revert its routes as below. Then
  `docker rm -f medioo-web`.
- **A manual run is not logged in to GHCR** (the CI deploy logs out at the end). A web tag that is no longer on
  the box can then fail to pull if the `medioo-web` package is private, and the run falls back to Java-only, with
  docker's own error in that log line. To pull it, log in first, or have the package's visibility set to public
  like `medscribe-ai`'s.

**Rollback floor (DR-40 A2, from the first route flip in W1c).** Caddy routing is state on the box, not tied to
`IMAGE_TAG`. Before rolling back to a sha older than the most recent route flip, first revert that route in the box's
Caddyfile (back up, write in place only, confirm the container sees it, validate, reload, as in the header of
`docs/infra/Caddyfile.medioo`), then set `IMAGE_TAG`, then run `scripts/smoke-https.sh https://medioo.eliooo.org`.
Rolling back past W1b leaves a newer `medioo-web` running; rolling Java back past an `/app/api` endpoint a flipped
route calls breaks that route for every family. No route has flipped yet (2026-10-21), so today the one-liner above
is the whole procedure.

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
docker logs --tail 200 medioo-web 2>&1          # Next.js; rotated at 10 MB x 3 files
docker exec medioo-web node -e "fetch('http://127.0.0.1:3000/app/healthz').then(r=>r.text()).then(console.log)"
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
