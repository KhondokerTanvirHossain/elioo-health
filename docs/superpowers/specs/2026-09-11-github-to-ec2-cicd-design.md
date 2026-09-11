# GitHub Actions → EC2 CI/CD with Supabase — Design

**Date:** 2026-09-11
**Status:** Approved (phase 2 of: 1 run locally, 2 deploy with CI/CD, 3 make every part work)

## Goal

A push to `main` builds and tests the project, publishes a Docker image, and
replaces the running container on the existing EC2 host. The application uses
the existing Supabase project `niramoy-rx` as its database, in its own schema.

Out of scope: HTTPS/domain, authentication, fixing the cloud integrations
(OCR, translation, AWS keys). The app may run in the same degraded mode as it
does locally today.

## Fixed facts

| Item | Value |
|---|---|
| GitHub repo | `github.com/KhondokerTanvirHossain/elioo-health`, default branch `main` |
| Image registry | GHCR: `ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai` (private) |
| EC2 host | `ec2-13-205-14-249.ap-south-1.compute.amazonaws.com` (13.205.14.249), Amazon Linux 2023, user `ec2-user`, Docker 25 installed |
| EC2 sizing | 2 vCPU, 3.7 GB RAM, 20 GB disk (48% used). **Shared** with n8n + Caddy (ports 80/443) and healthbuddy-calculator (3000). Port 8086 is free. |
| JVM/container limits | Container `--memory=1200m`; `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 -XX:+UseSerialGC` so the app fits beside the other containers |
| GCP key on server | `/home/ec2-user/gcp-credentials/medscribe-ai-prod-483312-316f94565ddd.json` (service account `medscribe-ai@medscribe-ai-prod-483312`); the deploy mounts it read-only at `/secrets/gcp-credentials.json` so OCR/translation can work on EC2 |
| Old local DB | The previous setup ran Postgres on the EC2 host itself (`PGHOST=13.205.14.249`, a 396 MB `medscribe.dump` is in the home dir). It is replaced by Supabase and left untouched. |
| App port | 8086 (security group must allow it) |
| Database | Supabase project `niramoy-rx` (ref `iwvtcjzlkiutffzjkvfa`, ap-south-1, Postgres 17) |
| DB schema | `medscribe`, owned by a dedicated login role `medscribe` |
| DB connection | Supabase **session pooler**, port 5432, database `postgres`, user `medscribe.iwvtcjzlkiutffzjkvfa` (IPv4-capable; works for both R2DBC and Flyway JDBC) |
| Spring profiles on EC2 | `prod,aws,gcp` |

## Components

### 1. Database provisioning (one-time, manual + assisted)

1. Via the Supabase MCP connector, run:
   `CREATE ROLE medscribe LOGIN; CREATE SCHEMA medscribe AUTHORIZATION medscribe;`
   No password is set here so the secret never passes through the assistant.
2. The user runs `ALTER ROLE medscribe PASSWORD '<secret>';` in the Supabase SQL
   editor and records the password in `.env.local` (laptop) and
   `/home/ec2-user/medscribe.env` (server).
3. Flyway runs with `default-schema=medscribe`, `create-schemas=false`
   (schema pre-exists and is owned by the role). V1–V6 apply on first start.

The other application's tables in `public` are never touched. Supabase's
advisory that three `public` tables have RLS disabled is reported to the user
and left for them to decide.

### 2. Production profile (`application-prod.properties`)

Rewritten to be fully env-driven, mirroring `local2`:

- `PGHOST`, `PGPORT` (default 5432), `PGDATABASE` (default `postgres`),
  `PGUSER`, `PGPASSWORD`, `PGSCHEMA` (default `medscribe`) feed both
  `spring.r2dbc.*` and `spring.flyway.*`.
- Flyway block identical to local2 except `create-schemas=false`.
- Remove the literal `<YOUR_GCP_PROJECT>`; `gcp.project-id=${GCP_PROJECT_ID:}`.
- Spring Boot Admin client: `spring.boot.admin.client.enabled=${SPRING_ADMIN_ENABLED:false}`.
- Tracing: `management.tracing.enabled=${TRACING_ENABLED:false}`.
- Actuator exposure limited to `health,info` in prod (today every endpoint,
  including env and heapdump, is exposed on the public port).

`application.properties` keeps `spring.profiles.active=local2,aws,gcp` as the
laptop default; the container overrides it with `SPRING_PROFILES_ACTIVE`.

### 3. Workflow `.github/workflows/ci-cd.yml`

```
on: push (all branches), pull_request (main)

job build (ubuntu-latest):
  - checkout
  - setup-java 21 (temurin) + gradle cache
  - services: postgres:17 on 5433 with medscribe/medscribe/medscribe
  - ./gradlew build   (env PGHOST=localhost PGPORT=5433 ... so contextLoads passes)
  - upload test reports on failure

job deploy (needs: build, if: github.ref == 'refs/heads/main' && event == push):
  - checkout
  - docker/login-action to ghcr.io with GITHUB_TOKEN (permissions: packages: write)
  - docker/setup-buildx-action
  - docker/build-push-action: context ".", file Dockerfile,
      tags ghcr.io/<owner>/elioo-health/medscribe-ai:${{ github.sha }} and :latest,
      cache-from/to type=gha
  - appleboy/ssh-action: host/user/key from secrets, script:
      echo $GHCR_PULL_TOKEN | docker login ghcr.io -u <owner> --password-stdin
      IMAGE_TAG=${{ github.sha }} bash /home/ec2-user/deploy.sh
  - curl http://<EC2_HOST>:8086/actuator/health from the runner (smoke test)
```

Repository secrets: `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY` (contents of the
.pem), `GHCR_PULL_TOKEN` (fine-grained PAT, read:packages only, used by the
server to pull the private image).

### 4. Server-side files

`deploy.sh` (in repo, copied to `/home/ec2-user/deploy.sh` once by the
assistant over SSH; the workflow also re-copies it on every deploy so the
server never drifts from the repo):

1. Require `/home/ec2-user/medscribe.env`; fail loudly if missing.
2. `docker pull $IMAGE:$IMAGE_TAG`.
3. Stop and remove the existing `medscribe-ai` container if present.
4. `docker run -d --name medscribe-ai --restart unless-stopped -p 8086:8086 --env-file /home/ec2-user/medscribe.env [-v $GCP_CREDENTIALS_PATH:/secrets/gcp-credentials.json:ro if the file exists] $IMAGE:$IMAGE_TAG`.
5. Poll `localhost:8086/actuator/health` up to 90 s; print last 50 log lines
   and exit non-zero on failure.
6. `docker image prune -f` of dangling images.

`medscribe.env.example` (in repo) documents every variable:
`SPRING_PROFILES_ACTIVE=prod,aws,gcp`, `PGHOST`, `PGPORT`, `PGDATABASE`,
`PGUSER`, `PGPASSWORD`, `PGSCHEMA`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`,
`AWS_SECRET_ACCESS_KEY`, `GCP_PROJECT_ID`, `GCP_CREDENTIALS_PATH`.
The real file lives only on the server (mode 600) and on the laptop as
`.env.local` (git-ignored).

### 5. Repository cleanup

Delete `.gitlab-ci.yml`, `deploy-ec2.sh`, `deploy-ec2-simple.sh`,
`configure.sh`, `Dockerfile.simple` (cannot build: omits GCP modules), and
`medscribe-ai/build.gradle.backup`. Update `README.md` "Getting Started" and
add a "Deployment" section describing the pipeline. Add a `.dockerignore`
entry for `**/gcp/*.json`.

## Data flow

```
developer push → GitHub Actions build (tests vs throwaway Postgres)
              → image to GHCR (sha + latest)
              → SSH: deploy.sh on EC2 → docker run --env-file
              → app: Flyway (JDBC) migrates medscribe schema on Supabase
              → app: R2DBC runtime traffic to Supabase session pooler
              → runner curls :8086/actuator/health
```

## Error handling

- Build failure blocks deploy (job dependency).
- Deploy script exits non-zero if the container fails health within 90 s;
  the previous image remains on the host and can be re-run with
  `IMAGE_TAG=<old sha> bash deploy.sh` (manual rollback).
- Missing env file or missing GHCR login fails fast with a clear message.

## Testing / acceptance

1. `./gradlew build` green on the runner.
2. Workflow run on `main` completes both jobs.
3. From the laptop: `curl http://ec2-13-205-14-249.ap-south-1.compute.amazonaws.com:8086/actuator/health` returns `{"status":"UP"}` with r2dbc UP.
4. UI loads at that host on port 8086.
5. `GET /api/v1/medical-report/query/high-risk` returns `[]` (Supabase round-trip).
6. `select version from medscribe.flyway_schema_history` on Supabase shows 1–6.
7. Local laptop run against Supabase via `.env.local` also works (same profile machinery).

## Open items handed to the user

- Set the `medscribe` role password in Supabase and share it only via the env files.
- Create the GHCR pull PAT (read:packages) in GitHub settings.
- Confirm the EC2 security group allows inbound TCP 8086 from the internet (and 22 from GitHub runners; `0.0.0.0/0` on 22 is acceptable for now since the key is required).
- `gh auth login` once so secrets can be set from the CLI.
