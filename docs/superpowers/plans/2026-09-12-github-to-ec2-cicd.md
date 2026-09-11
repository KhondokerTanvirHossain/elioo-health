# GitHub Actions → EC2 CI/CD with Supabase — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A push to `main` builds, tests, publishes a Docker image to GHCR, and replaces the MedScribe container on the existing EC2 host, which uses Supabase (`niramoy-rx`, schema `medscribe`) as its database.

**Architecture:** One workflow with two jobs: `build` (Gradle + throwaway Postgres service) on every push/PR, and `deploy` (Buildx → GHCR → SSH → `deploy.sh`) only on `main`. The server keeps a single `medscribe.env` file; the container gets it via `--env-file`. The production Spring profile becomes fully env-driven and runs Flyway against Supabase on startup.

**Tech Stack:** GitHub Actions (`actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`, `docker/login-action@v3`, `docker/setup-buildx-action@v3`, `docker/build-push-action@v6`, `appleboy/ssh-action@v1`), GHCR, Docker 25 on Amazon Linux 2023, Supabase session pooler (Postgres 17), Spring Boot 3.4.2, Flyway.

**Spec:** `docs/superpowers/specs/2026-09-11-github-to-ec2-cicd-design.md`

## Global Constraints

- Repo `KhondokerTanvirHossain/elioo-health` is **public**; never commit secrets. Secrets live only in GitHub Actions secrets, `/home/ec2-user/medscribe.env` (mode 600) and the laptop's git-ignored `.env.local`.
- EC2: `ec2-13-205-14-249.ap-south-1.compute.amazonaws.com`, user `ec2-user`, key `~/Documents/000_office/umr/aws/bdemr-text-analysis.pem`. Shared box: do not touch the `n8n-*` or `healthbuddy-calculator` containers, ports 80/443/3000, or `~/.env` / `~/deploy.sh` from the old setup (the new files use different names).
- Container limits: `--memory=1200m`, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 -XX:+UseSerialGC`.
- Image name: `ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai` (lower-case is mandatory for GHCR).
- Database: Supabase ref `iwvtcjzlkiutffzjkvfa`, region ap-south-1, DB `postgres`, role `medscribe`, schema `medscribe`, connect via session pooler port 5432 with user `medscribe.iwvtcjzlkiutffzjkvfa`.
- GCP key on server: `/home/ec2-user/gcp-credentials/medscribe-ai-prod-483312-316f94565ddd.json`, GCP project `medscribe-ai-prod-483312`.
- Only three GitHub secrets: `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`. The server pulls from GHCR with the job's `GITHUB_TOKEN` passed over SSH for the duration of the deploy.
- Commit after every task with the `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` trailer.

---

## File map

| Path | Responsibility |
|---|---|
| `medscribe-ai/src/main/resources/application-prod.properties` | Env-driven DB/Flyway/GCP/admin/tracing/actuator config for the container |
| `.github/workflows/ci-cd.yml` | The pipeline (build job, deploy job) |
| `deploy.sh` | Server-side: pull SHA image, replace container, health-wait |
| `medscribe.env.example` | Documents every runtime variable the container needs |
| `Dockerfile` | Existing multi-stage build; add default `JAVA_TOOL_OPTIONS` |
| `.dockerignore` | Exclude `**/gcp/*.json` |
| `README.md` | Local run + deployment sections |
| Deleted: `.gitlab-ci.yml`, `deploy-ec2.sh`, `deploy-ec2-simple.sh`, `configure.sh`, `Dockerfile.simple`, `medscribe-ai/build.gradle.backup` | One deploy path |
| Spec amendment | Drop `GHCR_PULL_TOKEN`; document `GITHUB_TOKEN` pull |

---

### Task 1: Provision the `medscribe` role and schema on Supabase and run the app against it locally

**Files:**
- Create (git-ignored): `.env.local` at repo root
- No source changes

**Interfaces:**
- Produces: schema `medscribe` owned by login role `medscribe` on project `iwvtcjzlkiutffzjkvfa`; the six PG variables in `.env.local` that later tasks copy to the server.

- [ ] **Step 1: Create role and schema (no password yet)** via the Supabase MCP `execute_sql` on project `iwvtcjzlkiutffzjkvfa`:

```sql
CREATE ROLE medscribe LOGIN;
GRANT medscribe TO postgres;                 -- lets the Supabase 'postgres' role act as owner
CREATE SCHEMA medscribe AUTHORIZATION medscribe;
```

- [ ] **Step 2: Verify**

```sql
select r.rolname, n.nspname from pg_roles r join pg_namespace n on n.nspowner = r.oid where r.rolname = 'medscribe';
```
Expected: one row `medscribe | medscribe`.

- [ ] **Step 3: User sets the password** in Supabase Dashboard → SQL Editor (the assistant never sees it):

```sql
ALTER ROLE medscribe PASSWORD '<choose-a-strong-password>';
```

- [ ] **Step 4: User finds the session-pooler host** in Dashboard → Connect → "Session pooler". It looks like `aws-1-ap-south-1.pooler.supabase.com` (may be `aws-0-`). Only the host is needed.

- [ ] **Step 5: User writes `.env.local`** at the repo root (git-ignored):

```bash
PGHOST=aws-1-ap-south-1.pooler.supabase.com   # from step 4
PGPORT=5432
PGDATABASE=postgres
PGUSER=medscribe.iwvtcjzlkiutffzjkvfa
PGPASSWORD=<the password from step 3>
PGSCHEMA=medscribe
```

- [ ] **Step 6: Run the app locally against Supabase**

```bash
set -a; source .env.local; set +a
./gradlew :medscribe-ai:bootRun
```
Expected in the log: `Migrating schema "medscribe" to version "1 - create medical report tables"` … `Successfully applied 6 migrations`, then `Started MedscribeAiApplication`.

- [ ] **Step 7: Verify from outside**

```bash
curl -s localhost:8086/actuator/health | grep -o '"r2dbc":{"status":"UP"'
curl -s localhost:8086/api/v1/medical-report/query/high-risk     # expect []
```
And on Supabase: `select version, success from medscribe.flyway_schema_history order by installed_rank;` → six rows, all `true`.

- [ ] **Step 8: Stop the app** (Ctrl+C). Nothing to commit (`.env.local` is ignored; confirm with `git status --short` showing nothing).

---

### Task 2: Make the production profile env-driven

**Files:**
- Modify: `medscribe-ai/src/main/resources/application-prod.properties` (replace whole file)
- Modify: `medscribe-ai/src/main/resources/application.properties:33-38` (actuator exposure)

**Interfaces:**
- Consumes: env variables `SPRING_PROFILES_ACTIVE, PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD, PGSCHEMA, GCP_PROJECT_ID, GCP_CREDENTIALS_PATH, SPRING_ADMIN_ENABLED, TRACING_ENABLED` (all documented in Task 3's `medscribe.env.example`).

- [ ] **Step 1: Replace `application-prod.properties`** with:

```properties
server.port=8086

# ---------------------------------------------------------------------------
# Database: every value comes from the environment (container --env-file).
# On EC2 this points at the Supabase session pooler.
# ---------------------------------------------------------------------------
PGPORT=5432
PGDATABASE=postgres
PGSCHEMA=medscribe

spring.r2dbc.url=r2dbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}?schema=${PGSCHEMA}
spring.r2dbc.username=${PGUSER}
spring.r2dbc.password=${PGPASSWORD}
spring.r2dbc.pool.initial-size=5
spring.r2dbc.pool.max-size=20
spring.r2dbc.pool.max-idle-time=30m
spring.r2dbc.pool.max-acquire-time=5s

# Schema migrations (JDBC, startup only). The schema pre-exists and is owned by PGUSER.
spring.flyway.enabled=true
spring.flyway.url=jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}
spring.flyway.user=${PGUSER}
spring.flyway.password=${PGPASSWORD}
spring.flyway.default-schema=${PGSCHEMA}
spring.flyway.create-schemas=false
# No baseline-on-migrate: a pre-existing non-empty schema must fail loudly rather than skip V1

# ---------------------------------------------------------------------------
# GCP: credentials file is mounted into the container by deploy.sh
# ---------------------------------------------------------------------------
gcp.enabled=true
gcp.project-id=${GCP_PROJECT_ID:}
gcp.credentials-path=${GCP_CREDENTIALS_PATH:}

# ---------------------------------------------------------------------------
# Optional integrations: off unless explicitly configured
# ---------------------------------------------------------------------------
feature-flagging.base-url=${FEATURE_FLAGGING_SERVICE_URL:http://localhost:8087}
spring.boot.admin.client.enabled=${SPRING_ADMIN_ENABLED:false}
spring.boot.admin.client.url=${SPRING_ADMIN_SERVER_URL:http://localhost:8080}
spring.boot.admin.client.instance.service-base-url=${MEDSCRIBEAI_SERVICE_URL:http://localhost:8086}
spring.boot.admin.client.username=${ADMIN_SERVER_USERNAME:}
spring.boot.admin.client.password=${ADMIN_SERVER_PASS:}
management.tracing.enabled=${TRACING_ENABLED:false}

# Only health and info are reachable on the public port in production
management.endpoints.web.exposure.include=health,info
```

- [ ] **Step 2: Leave `application.properties` exposure as-is for local** (it lists `*`), but add a comment above it: `# Production restricts this to health,info in application-prod.properties`.

- [ ] **Step 3: Verify the prod profile boots locally against Supabase**

```bash
set -a; source .env.local; set +a
SPRING_PROFILES_ACTIVE=prod,aws,gcp ./gradlew :medscribe-ai:bootRun
```
Expected: `Successfully validated 6 migrations` (already applied in Task 1), `Started MedscribeAiApplication`, no `spring.boot.admin` registration errors, no Zipkin connection warnings.

- [ ] **Step 4: Verify actuator exposure**

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8086/actuator/health   # 200
curl -s -o /dev/null -w '%{http_code}\n' localhost:8086/actuator/env      # 404
```

- [ ] **Step 5: Stop the app and commit**

```bash
git add medscribe-ai/src/main/resources/application-prod.properties medscribe-ai/src/main/resources/application.properties
git commit -m "config(prod): env-driven database, Flyway, GCP, and restricted actuator

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Server-side deploy script, env template, and Dockerfile defaults

**Files:**
- Replace: `deploy.sh`
- Create: `medscribe.env.example`
- Modify: `Dockerfile` (add `ENV JAVA_TOOL_OPTIONS`)
- Modify: `.dockerignore` (add GCP json exclusion)

**Interfaces:**
- Produces: `deploy.sh` contract — reads env `IMAGE` (default `ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai`), `IMAGE_TAG` (required), `ENV_FILE` (default `/home/ec2-user/medscribe.env`), `CONTAINER_NAME` (default `medscribe-ai`); exits 0 only when `/actuator/health` returns 200 within 90 s. Task 4's workflow calls it as `IMAGE_TAG=<sha> bash /home/ec2-user/deploy.sh`.

- [ ] **Step 1: Write `deploy.sh`**

```bash
#!/usr/bin/env bash
# MedScribe AI — server-side deploy. Invoked by GitHub Actions over SSH:
#   IMAGE_TAG=<git sha> bash /home/ec2-user/deploy.sh
# Requires: docker, a prior `docker login ghcr.io`, and $ENV_FILE (mode 600).
set -euo pipefail

IMAGE="${IMAGE:-ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai}"
IMAGE_TAG="${IMAGE_TAG:?IMAGE_TAG (git sha) is required}"
ENV_FILE="${ENV_FILE:-/home/ec2-user/medscribe.env}"
CONTAINER_NAME="${CONTAINER_NAME:-medscribe-ai}"
PORT=8086
HEALTH_URL="http://localhost:${PORT}/actuator/health"

log() { printf '[deploy] %s\n' "$*"; }

[ -f "$ENV_FILE" ] || { log "ERROR: $ENV_FILE not found. Copy medscribe.env.example there and fill it in."; exit 1; }

# GCP credentials: mount if the path in the env file exists on this host
GCP_HOST_PATH="$(grep -E '^GCP_CREDENTIALS_HOST_PATH=' "$ENV_FILE" | cut -d= -f2- || true)"
GCP_MOUNT=()
if [ -n "$GCP_HOST_PATH" ] && [ -f "$GCP_HOST_PATH" ]; then
  GCP_MOUNT=(-v "$GCP_HOST_PATH:/secrets/gcp-credentials.json:ro")
  log "GCP credentials will be mounted from $GCP_HOST_PATH"
else
  log "WARNING: no GCP credentials file; OCR/translation will be degraded"
fi

log "Pulling ${IMAGE}:${IMAGE_TAG}"
docker pull "${IMAGE}:${IMAGE_TAG}"

if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; then
  log "Removing existing container $CONTAINER_NAME"
  docker rm -f "$CONTAINER_NAME" >/dev/null
fi

log "Starting $CONTAINER_NAME"
docker run -d \
  --name "$CONTAINER_NAME" \
  --restart unless-stopped \
  --memory=1200m \
  -p "${PORT}:${PORT}" \
  --env-file "$ENV_FILE" \
  -e GCP_CREDENTIALS_PATH=/secrets/gcp-credentials.json \
  "${GCP_MOUNT[@]}" \
  "${IMAGE}:${IMAGE_TAG}" >/dev/null

log "Waiting for health at $HEALTH_URL"
for i in $(seq 1 45); do
  if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
    log "Healthy after $((i*2))s: $(curl -fsS "$HEALTH_URL")"
    docker image prune -f >/dev/null || true
    exit 0
  fi
  sleep 2
done

log "ERROR: container did not become healthy in 90s. Last 50 log lines:"
docker logs --tail 50 "$CONTAINER_NAME" || true
exit 1
```

- [ ] **Step 2: Write `medscribe.env.example`**

```bash
# Runtime configuration for the MedScribe AI container.
# Copy to /home/ec2-user/medscribe.env on the server (chmod 600) and fill in.
# Locally, the equivalent file is .env.local (git-ignored).

SPRING_PROFILES_ACTIVE=prod,aws,gcp

# --- Database (Supabase session pooler) ---
PGHOST=aws-1-ap-south-1.pooler.supabase.com
PGPORT=5432
PGDATABASE=postgres
PGUSER=medscribe.iwvtcjzlkiutffzjkvfa
PGPASSWORD=
PGSCHEMA=medscribe

# --- AWS (Textract / Comprehend Medical / Bedrock) ---
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=

# --- GCP (Vision OCR / Translate) ---
GCP_PROJECT_ID=medscribe-ai-prod-483312
# Path ON THE HOST; deploy.sh mounts it into the container at /secrets/gcp-credentials.json
GCP_CREDENTIALS_HOST_PATH=/home/ec2-user/gcp-credentials/medscribe-ai-prod-483312-316f94565ddd.json

# --- Optional integrations (leave off unless you run them) ---
SPRING_ADMIN_ENABLED=false
TRACING_ENABLED=false
```

- [ ] **Step 3: Add JVM defaults to `Dockerfile`** — insert after the `EXPOSE 8086` line:

```dockerfile
# Fits beside other containers on a small host; override with -e JAVA_TOOL_OPTIONS=...
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"
```

- [ ] **Step 4: Add to `.dockerignore`**

```
**/gcp/*.json
.env.local
```

- [ ] **Step 5: Syntax-check and local image build**

```bash
bash -n deploy.sh && echo "deploy.sh syntax OK"
chmod +x deploy.sh
docker build -t medscribe-ai:local . 2>&1 | tail -3
```
Expected: `deploy.sh syntax OK`; build finishes with `naming to docker.io/library/medscribe-ai:local`.

- [ ] **Step 6: Run the image locally against Supabase** (proves the Dockerfile + prod profile + env file work together before touching EC2)

```bash
docker run -d --name medscribe-local --memory=1200m -p 8087:8086 \
  --env-file .env.local -e SPRING_PROFILES_ACTIVE=prod,aws,gcp medscribe-ai:local
for i in $(seq 1 45); do curl -fsS localhost:8087/actuator/health && break; sleep 2; done; echo
docker logs medscribe-local 2>&1 | grep -E 'Successfully (validated|applied)|Started MedscribeAiApplication|PLACEHOLDER'
docker rm -f medscribe-local
```
Expected: `{"status":"UP",...}`, migrations validated, `Started`, and the GCP placeholder warning (no key on the laptop).

- [ ] **Step 7: Commit**

```bash
git add deploy.sh medscribe.env.example Dockerfile .dockerignore
git commit -m "deploy: GHCR-based server deploy script, env template, JVM limits

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: GitHub Actions workflow

**Files:**
- Create: `.github/workflows/ci-cd.yml`
- Modify: `docs/superpowers/specs/2026-09-11-github-to-ec2-cicd-design.md` (replace `GHCR_PULL_TOKEN` mentions)

**Interfaces:**
- Consumes: secrets `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY` (set in Task 5); `deploy.sh` contract from Task 3.
- Produces: image tags `<sha>` and `latest` on GHCR.

- [ ] **Step 1: Write `.github/workflows/ci-cd.yml`**

```yaml
name: CI/CD

on:
  push:
    branches: ["**"]
  pull_request:
    branches: [main]

env:
  IMAGE: ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai

jobs:
  build:
    name: Build and test
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:17-alpine
        env:
          POSTGRES_DB: medscribe
          POSTGRES_USER: medscribe
          POSTGRES_PASSWORD: medscribe
        ports: ["5433:5432"]
        options: >-
          --health-cmd "pg_isready -U medscribe -d medscribe"
          --health-interval 5s --health-timeout 3s --health-retries 10
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
      - uses: gradle/actions/setup-gradle@v4
      - name: Build (unit tests + context test against service Postgres)
        run: ./gradlew build --no-daemon
        env:
          PGHOST: localhost
          PGPORT: "5433"
          PGDATABASE: medscribe
          PGUSER: medscribe
          PGPASSWORD: medscribe
          PGSCHEMA: medscribe
      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: "**/build/reports/tests/test"

  deploy:
    name: Build image and deploy to EC2
    needs: build
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/setup-buildx-action@v3
      - name: Build and push image
        uses: docker/build-push-action@v6
        with:
          context: .
          file: Dockerfile
          push: true
          tags: |
            ${{ env.IMAGE }}:${{ github.sha }}
            ${{ env.IMAGE }}:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max
      - name: Copy deploy.sh to server
        uses: appleboy/scp-action@v1
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          source: deploy.sh
          target: /home/ec2-user/
      - name: Deploy on server
        uses: appleboy/ssh-action@v1
        env:
          GHCR_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          GHCR_USER: ${{ github.actor }}
          IMAGE_TAG: ${{ github.sha }}
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          envs: GHCR_TOKEN,GHCR_USER,IMAGE_TAG
          script: |
            set -e
            echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
            IMAGE_TAG="$IMAGE_TAG" bash /home/ec2-user/deploy.sh
            docker logout ghcr.io
      - name: Smoke test from runner
        run: |
          for i in $(seq 1 10); do
            if curl -fsS "http://${{ secrets.EC2_HOST }}:8086/actuator/health"; then echo; exit 0; fi
            sleep 5
          done
          echo "Health check failed"; exit 1
```

- [ ] **Step 2: Amend the spec** — in `docs/superpowers/specs/2026-09-11-github-to-ec2-cicd-design.md`, replace the `GHCR_PULL_TOKEN` secret with: "the deploy job passes its short-lived `GITHUB_TOKEN` to the server over SSH for `docker login ghcr.io`, then logs out; no PAT is needed." Update the "Open items" list accordingly.

- [ ] **Step 3: Validate YAML locally**

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci-cd.yml')); print('yaml OK')"
```
Expected: `yaml OK`. (If PyYAML is missing: `python3 -m pip install --user pyyaml`.)

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci-cd.yml docs/superpowers/specs/2026-09-11-github-to-ec2-cicd-design.md
git commit -m "ci: GitHub Actions build, GHCR publish, and SSH deploy to EC2

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Server preparation and GitHub secrets

**Files:**
- On server: `/home/ec2-user/medscribe.env` (mode 600), `/home/ec2-user/deploy.sh`
- GitHub: secrets `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`

**Interfaces:**
- Consumes: `.env.local` from Task 1 (for the PG values), `medscribe.env.example` from Task 3.

- [ ] **Step 1: Generate the server env file from the template + `.env.local`** (run on the laptop; the password is never printed)

```bash
KEY=~/Documents/000_office/umr/aws/bdemr-text-analysis.pem
HOST=ec2-13-205-14-249.ap-south-1.compute.amazonaws.com
set -a; source .env.local; set +a
sed -e "s|^PGHOST=.*|PGHOST=${PGHOST}|" \
    -e "s|^PGUSER=.*|PGUSER=${PGUSER}|" \
    -e "s|^PGPASSWORD=.*|PGPASSWORD=${PGPASSWORD}|" \
    medscribe.env.example > /tmp/medscribe.env
scp -i "$KEY" /tmp/medscribe.env deploy.sh ec2-user@$HOST:/home/ec2-user/
rm /tmp/medscribe.env
ssh -i "$KEY" ec2-user@$HOST 'chmod 600 ~/medscribe.env && chmod +x ~/deploy.sh && sed "s/=.*/=<set>/" ~/medscribe.env'
```
Expected: the listing shows every variable name with `<set>`; AWS keys remain empty (user adds later via `ssh` + `vi ~/medscribe.env` once they have valid keys).

- [ ] **Step 2: Verify the server can reach Supabase** (rules out a network/egress problem before the first deploy)

```bash
ssh -i "$KEY" ec2-user@$HOST 'set -a; . ~/medscribe.env; set +a; timeout 10 bash -c "cat < /dev/null > /dev/tcp/$PGHOST/$PGPORT" && echo "pooler reachable"'
```
Expected: `pooler reachable`.

- [ ] **Step 3: Set GitHub secrets**

```bash
gh secret set EC2_HOST -R KhondokerTanvirHossain/elioo-health -b "ec2-13-205-14-249.ap-south-1.compute.amazonaws.com"
gh secret set EC2_USER -R KhondokerTanvirHossain/elioo-health -b "ec2-user"
gh secret set EC2_SSH_KEY -R KhondokerTanvirHossain/elioo-health < ~/Documents/000_office/umr/aws/bdemr-text-analysis.pem
gh secret list -R KhondokerTanvirHossain/elioo-health
```
Expected: three secrets listed.

- [ ] **Step 4: Confirm the security group allows 8086** — after the first deploy (Task 7) `curl http://13.205.14.249:8086/actuator/health` from the laptop must succeed. If it times out while `ssh … curl localhost:8086/actuator/health` works, the user opens inbound TCP 8086 (source 0.0.0.0/0) on the instance's security group in the AWS console (CLI keys are currently invalid).

Nothing to commit in this task.

---

### Task 6: Repository cleanup and README

**Files:**
- Delete: `.gitlab-ci.yml`, `deploy-ec2.sh`, `deploy-ec2-simple.sh`, `configure.sh`, `Dockerfile.simple`, `medscribe-ai/build.gradle.backup`
- Modify: `README.md` — "Getting Started" steps 2–5 and add a "Deployment" section

- [ ] **Step 1: Delete the obsolete files**

```bash
git rm -q .gitlab-ci.yml deploy-ec2.sh deploy-ec2-simple.sh configure.sh Dockerfile.simple medscribe-ai/build.gradle.backup
```

- [ ] **Step 2: Rewrite README "Getting Started" (replace steps 2–5)** with:

````markdown
### 2. Start a local database

```bash
docker compose up -d        # Postgres 17 on localhost:5433 (db/user/pass: medscribe)
```

Or point at Supabase instead: copy `medscribe.env.example` to `.env.local`, fill in the
`PG*` values, then `set -a; source .env.local; set +a` before running.

### 3. Run

```bash
./gradlew :medscribe-ai:bootRun      # http://localhost:8086 (UI, /actuator/health)
```

Flyway applies `medscribe-ai/src/main/resources/db/migration/V*.sql` automatically.
Without GCP credentials the app starts in degraded mode (OCR and translation fail, everything
else works). AWS calls need valid keys in `~/.aws` or `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`.

### 4. Build and test

```bash
./gradlew build                                   # all modules, unit tests
RUN_AWS_INTEGRATION_TESTS=true ./gradlew test     # also the real-AWS integration tests
```
````

- [ ] **Step 3: Add a "Deployment" section** (replace the "Docker Development" section):

````markdown
## Deployment

Every push runs the **CI/CD** workflow (`.github/workflows/ci-cd.yml`):

1. `build` — Gradle build and tests on every branch and PR.
2. `deploy` (only on `main`) — builds the Docker image, pushes it to
   `ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai:<sha>`, then SSHes to the EC2
   host and runs `deploy.sh`, which replaces the `medscribe-ai` container and waits for
   `/actuator/health`.

Server prerequisites (one-time): Docker, `/home/ec2-user/medscribe.env` (from
`medscribe.env.example`, mode 600), and GitHub secrets `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`.

Manual rollback on the server: `IMAGE_TAG=<previous sha> bash ~/deploy.sh`.
````

- [ ] **Step 4: Build still green**

```bash
./gradlew build -q && echo BUILD OK
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: remove GitLab/legacy deploy scripts; document local run and deployment

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: First pipeline run and acceptance

**Files:** none

- [ ] **Step 1: Push `main`**

```bash
git push origin main
```

- [ ] **Step 2: Watch the run**

```bash
gh run list -R KhondokerTanvirHossain/elioo-health -L 1
gh run watch -R KhondokerTanvirHossain/elioo-health --exit-status
```
Expected: both jobs succeed. If `build` fails, open `gh run view --log-failed`; if `deploy` fails at the SSH step, check the secret values and that `deploy.sh` printed its `[deploy]` lines.

- [ ] **Step 3: Acceptance checks from the laptop**

```bash
H=http://13.205.14.249:8086
curl -s $H/actuator/health                                  # {"status":"UP", ... "r2dbc":{"status":"UP"
curl -s -o /dev/null -w '%{http_code}\n' $H/                # 200 (UI)
curl -s $H/api/v1/medical-report/query/high-risk            # []
curl -s -o /dev/null -w '%{http_code}\n' $H/actuator/env    # 404
```

- [ ] **Step 4: Acceptance on Supabase** (MCP `execute_sql`):

```sql
select installed_rank, version, success from medscribe.flyway_schema_history order by installed_rank;
```
Expected: ranks 1–6, all `success = true`.

- [ ] **Step 5: Check image visibility** — `gh api /users/KhondokerTanvirHossain/packages/container/elioo-health%2Fmedscribe-ai --jq .visibility`. Either value is fine; the server authenticates on every deploy regardless.

- [ ] **Step 6: Record outcome** in `docs/superpowers/specs/2026-09-11-github-to-ec2-cicd-design.md` — change `Status: Approved` to `Status: Implemented <date>, first successful run <run URL>`; commit and push.

```bash
git commit -am "docs: mark CI/CD spec implemented

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" && git push origin main
```
