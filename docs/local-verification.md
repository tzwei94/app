# Application tests

Run from the application repository root with Java 25, Node.js 24, Docker, OpenSSL, unzip and uv available:

```sh
make verify
make smoke
```

`make verify` runs Checkstyle, the OpenAPI validator, Node release-policy tests and Maven tests. The Java test script creates a disposable PostgreSQL instance and removes it on exit. Tests cover money rules, JWT validation, account ownership, concurrent mutations and retries, transaction rollback, and Liquibase administrative commands.

To supply a test database, set `BANK_TEST_DB_URL` and `BANK_TEST_DB_PASSWORD`; the username must be `banking_test`. Use a disposable database: the suite clears its banking tables.

`make smoke` builds the API and sibling deployment repository's Alloy image. It checks Basic login, authenticated banking requests, idempotent retries, migration, restart persistence, and logs/metrics/traces through a local receiver. It also checks receiver outage recovery, trace/span correlation and account-ID redaction, then removes its containers and volumes. The local API uses loopback HTTP; deployed smoke checks use public HTTPS.

For CI workflow changes, run `actionlint` from this repository. The Node tests cover version selection, POM edits, CI provenance, image identity, immutable release manifests and retry recovery. Remote GitHub App permissions, OIDC trust, ECR pushes and Release API behavior require a configured workflow run.

Build and scan each release image in CI. Local telemetry checks verify transport; query the deployed monitoring backend separately to verify storage and retrieval. Run image checks on Linux AMD64 before releasing to the configured Fargate runtime.
