# Documentation

| Document | What it is |
|---|---|
| [BAYMAX.md](BAYMAX.md) | Baymax engineering brief: hard constraints, architecture decisions, target data model, proactive rules, build-report format. Read first for Baymax work |
| [DECISIONS.md](DECISIONS.md) | Baymax decision records (DR-n), chronological; later records supersede earlier ones |
| [PRODUCT.md](PRODUCT.md) | Baymax MVP v1.0 product document (2026-09-12): problem, personas, locked decisions, MVP scope, unit economics, pilot metrics |
| [RUNBOOK.md](RUNBOOK.md) | Production: the box, safe edits to the server env (which BAYMAX_* lines must be blank vs set), deploy, the post-deploy self-test, migrations, rollback |
| [PILOT_LOG.md](PILOT_LOG.md) | Evidence from real production runs — what actually happened, with row states and the family-facing text; kept because the fixtures that produced it do not survive |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Current system: modules, pipeline, data, configuration, deployment, open work |
| [../README.md](../README.md) | Setup, run, build, test, deploy |
| [../CLAUDE.md](../CLAUDE.md) | Working guide for AI-assisted development in this repo |
| [requests.http](requests.http) | Ready-to-run HTTP examples for every public endpoint |
| [superpowers/specs/](superpowers/specs/) | Design specs for shipped changes (CI/CD to EC2, provider-neutral LLM layer) |
| [superpowers/plans/](superpowers/plans/) | The implementation plans that executed those specs |
| [archive/](archive/) | PoC-era plans, completion reports and fix logs (2025-12 to 2026-01). Historical only; several describe states that no longer exist. |
