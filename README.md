# Banking API

A Spring Boot 4.1.1 application using Java 25, Maven and PostgreSQL. JWT-authenticated users can check balances, deposit and withdraw from accounts they own.

## Run the tests

Run these commands from `app/` (or the root of a standalone application checkout). Requires Java 25, Docker with Compose, OpenSSL, curl, unzip and uv. `make verify` also requires Node.js 24; `make smoke` requires the sibling `../deployment/deploy/monitoring` checkout. The checked-in Maven wrapper pins Maven 3.9.11 and verifies its distribution checksum. On macOS with multiple JDKs:

```sh
# Select an installed Java 25 JDK.
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
scripts/test.sh
docker build -f Dockerfile.local -t banking-api:local .
docker build -t banking-alloy:local ../deployment/deploy/monitoring
scripts/local-smoke.sh
```

The acceptance suite creates and removes its own PostgreSQL container, uses synthetic RSA keys, and tests exact decimals, validation, JWT claims/signatures, account ownership, concurrent withdrawals, concurrent retries and rollback atomicity. `BANK_TEST_DB_URL` / `BANK_TEST_DB_PASSWORD` can select an existing disposable test database with username `banking_test`; the suite clears its banking tables.

The smoke script generates a temporary signing key and Basic credentials, starts PostgreSQL, executes migrations, serves HTTP, obtains tokens from `/auth/token`, checks balances/mutations/retries, restarts the application, and sends all three signals through Alloy to a local authenticated protocol receiver. It deletes its containers and volumes afterward. The receiver counts authenticated, nonempty requests; it does not decode telemetry payloads or validate storage/query behavior in the remote Grafana stack. Each API smoke sequence returns the balance to its starting value, so a successful post-restart sequence confirms availability but does not independently prove a nonzero balance change survived the restart. Local smoke uses loopback HTTP; deployed smoke verifies the public HTTPS certificate.

## Try the API in Postman

Import [the collection](postman/Banking-API.postman_collection.json) and [local environment template](postman/Local.postman_environment.json), then select the environment and set `base_url`, `token_username` and `token_password`. Send **Get token** to save `api_token` automatically. Docker uses an assigned host port; direct local JAR startup uses `http://localhost:8080`. See [Postman setup and request order](postman/README.md) for Basic login, idempotent retries, deployed HTTPS, and command-line execution. The collection covers every API endpoint and includes response assertions; its default banking sequence deposits and withdraws SGD 1.

## API contract

All banking endpoints require `Authorization: Bearer <RS256 JWT>`. Validate the configured issuer, audience, expiration and nonempty subject. `POST /auth/token` requires HTTP Basic credentials from `TOKEN_USERNAME` and `TOKEN_PASSWORD` and returns `{access_token, token_type, expires_in}`. Tokens last 900 seconds and use the configured `TOKEN_SUBJECT` (default `alice`), never a caller-supplied subject. Basic credentials cannot access banking endpoints. `JWT_PRIVATE_KEY` is a shared PKCS#8 PEM RSA private key (at least 2048 bits); the application derives its verification key. Store the same key on all replicas so tokens survive restarts and load balancing. Empty login credentials, issuer, audience or subject, a colon in `TOKEN_USERNAME`, or a missing/invalid signing key prevent startup. Token responses use `Cache-Control: no-store`.

| Method/path | Request | Result |
|---|---|---|
| `GET /accounts/{uuid}/balance` | Bearer token | `{"balance":100.00,"currency":"SGD"}` |
| `POST /accounts/{uuid}/deposits` | `{"amount":0.01}` and `Idempotency-Key` | Resulting balance |
| `POST /accounts/{uuid}/withdrawals` | Same | Resulting balance |
| `POST /auth/token` | HTTP Basic authentication; no body | RS256 Bearer token, valid for 15 minutes |
| `GET /readyz` | No credentials | `SELECT 1` database connectivity, 200/503; does not validate schema |
| `GET /livez` | No credentials | Minimal process health |
| `GET /version` | No credentials | `{version, source}` from build information and `SOURCE_SHA` |

Money is positive SGD with at most two fractional digits and seventeen integer digits. Mutations lock the account row and write the balance and operation ledger in one transaction. A key is 1–128 ASCII letters/digits or `._:-`, scoped to an account. Replaying the same amount/operation returns its original result, even after later operations. Changing the payload for an existing key returns 409. Keys are retained with the ledger; there is no expiry policy in this demonstration.

400 means invalid input; 401 invalid/missing authentication; 404 unknown or unowned account; 409 insufficient funds, balance limit or idempotency conflict; 500 is a redacted server failure. The demo uses a seeded account and has no account creation or deletion endpoints. `SEED_SYNTHETIC=true` on the separate migration task creates account `00000000-0000-0000-0000-000000000001`, subject `alice`, SGD100 once. Repeated migrations do not reset it. The seed always belongs to `alice`; setting `TOKEN_SUBJECT` to another value does not change that ownership.

## Database migrations

Liquibase uses `src/main/resources/db/changelog/db.changelog-master.yaml`, which explicitly orders the SQL changesets. Add new changesets for future schema changes; do not edit already-applied SQL. The `migrate` command and acceptance tests use the same changelog. Liquibase records applied changesets and coordinates migrations through `DATABASECHANGELOG` and `DATABASECHANGELOGLOCK`.

For an existing Flyway database, establish a Liquibase baseline before migrating. To discard an old local demo database, stop the Compose stack with `down -v` and recreate it. Repeated `migrate` runs preserve existing balances, including when `SEED_SYNTHETIC=true`.

Run migrations explicitly using the application image (set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` first):

```sh
# For the local quickstart database; start it with Compose before this command.
docker run --rm --network banking-quickstart_default \
  -e DB_URL -e DB_USERNAME -e DB_PASSWORD \
  -e SEED_SYNTHETIC=true banking-api:local migrate

# Equivalent shortcut, from app/:
DOCKER_NETWORK=banking-quickstart_default SEED_SYNTHETIC=true make migrate
```

A migration container exits successfully when its work finishes; it is not an HTTP service. The shortcut accepts `MIGRATION_IMAGE` (default `banking-api:local`) and an optional `DOCKER_NETWORK`. It requires the three database variables and defaults synthetic seeding to false. Use a JDBC URL reachable from the container; `localhost` refers to that container itself. Migration failures return a nonzero exit status. Start the API only after the command succeeds. Compose has no migration service; `docker compose up` alone does not apply schema changes. Migration output is printed by the one-off container.

### Rollback and checksum maintenance

Use the same image, Docker network and database variables as `migrate`. Commands finish without starting the HTTP server. Stop the API before rolling back schema changes it depends on.

```sh
# Undo the latest changeset (equivalent to rollback 1).
docker run --rm --network banking-quickstart_default \
  -e DB_URL -e DB_USERNAME -e DB_PASSWORD banking-api:local rollback

# Undo the latest two changesets; count must not exceed applied changesets.
docker run --rm --network banking-quickstart_default \
  -e DB_URL -e DB_USERNAME -e DB_PASSWORD banking-api:local rollback 2

# Clear recorded checksums; clearchecksum is also accepted.
docker run --rm --network banking-quickstart_default \
  -e DB_URL -e DB_USERNAME -e DB_PASSWORD banking-api:local clear-checksums
```

There is currently one changeset. Rolling it back drops `banking_operations` and `banking_accounts`, including their data. Use a database backup if that data must be recoverable. Running `migrate` afterward recreates empty tables; `SEED_SYNTHETIC=true` optionally recreates the demo account, but does not restore deleted transactions. Rollback SQL lives alongside the forward SQL; include rollback definitions in future changesets.

`clear-checksums` clears Liquibase's stored checksums without changing the application tables or undoing migrations. The next `migrate` recalculates them and applies any pending changesets; it does not rerun previously applied SQL. Use checksum clearing only after reviewing the reason for a checksum mismatch, not as a replacement for a new changeset.

## Runtime and delivery

`Dockerfile` is a reusable runtime for a single executable JAR. CI builds the JAR first and selects it with `JAR_FILE`; the default is `target/*.jar` (must match exactly one executable JAR). `JAVA_IMAGE` selects a compatible Java base image and defaults to the pinned Java 25 JDK used by this application. Maven and Gradle projects can both use it:

```bash
docker build --build-arg JAR_FILE=target/my-service.jar -t my-service .
docker build --build-arg JAVA_IMAGE=eclipse-temurin:21-jre-jammy \
  --build-arg JAR_FILE=build/libs/my-service.jar -t my-service .
docker run --rm -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55" my-service --help
```

The selected JAR must be inside the build context and allowed by `.dockerignore`. Choose a Java version compatible with that application's bytecode; pin the base image digest for release builds. This recipe uses `/app/app.jar`, runs as UID/GID 10001, forwards container arguments to the JAR, and declares `/tmp` and `/var/log/app` volumes with build-time permissions. It includes a checksum-verified public database CA bundle at `/opt/app/certs/rds-ca.pem`; it does not declare network ports. It supports executable JARs; applications requiring an external classpath or WAR container need their own launch command/runtime.

`Dockerfile.local` is the Maven convenience build used by `make image`; it compiles this application inside Docker and uses the same direct Java startup. For a production build, run `make build` followed by `docker build --build-arg 'JAR_FILE=target/banking-api-*.jar' -t banking-api:production .`. Telemetry is packaged in the application JAR. JVM settings, logging paths, ports and mounts remain configurable at deployment. Both Dockerfiles accept `CA_BUNDLE_URL` and `CA_BUNDLE_SHA256` build arguments (defaulting to the pinned AWS RDS bundle); certificate downloads and checksum verification happen during the build, not startup. Build a production release once and reuse its immutable image across environments with different settings and secrets.

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_PRIVATE_KEY`, `JWT_ISSUER`, `JWT_AUDIENCE`, `TOKEN_USERNAME`, `TOKEN_PASSWORD` and `TOKEN_SUBJECT` configure the server. For production PostgreSQL connections, use `sslmode=verify-full` with the CA appropriate to your database provider. Both Java images include `/opt/app/certs/rds-ca.pem`, readable by the non-root process and protected by the read-only root filesystem in ECS. No certificate volume or initialization container is needed. For another provider, mount its CA certificate if required and reference that path with `sslrootcert` in `DB_URL`. Liquibase is disabled in the serving process. The image's `migrate` command runs Liquibase separately; `bootstrap` is an operator-only first-deployment command with temporary AWS privileges. Neither runs on normal application startup. With the production image, append `migrate`, `rollback [count]`, `clear-checksums` or `bootstrap` to the container invocation; these modes exit before Spring starts, so the telemetry starter is not initialized. ECS and Compose explicitly set `LOG_PATH=/var/log/app` and JVM memory options. The Dockerfiles prepare fresh log volumes for UID/GID 10001 with owner-only access; existing volumes and custom host-mounted directories must have compatible ownership and readable log files. The image prepares `/tmp` with mode 1777; local Compose overrides it with a UID-10001 tmpfs.

The non-root image serves HTTP on 8080 without generating certificates. Public HTTPS terminates at the ALB; the private ALB-to-task connection uses HTTP. Actuator is isolated on loopback 9000. Structured Logback files, Micrometer metrics and the OpenTelemetry Spring Boot starter feed the task's Alloy sidecar. WARN/ERROR console messages provide a small CloudWatch fallback. Never enable request-body/header logging for this API.

GitHub Actions starts with [A. Application CI](.github/workflows/app-ci.yml) on every push to `main` and on PRs targeting `main`. It calls separate reusable [lint](.github/workflows/lint.yml), [test](.github/workflows/test.yml), [build/scan](.github/workflows/build-image.yml) and [publish](.github/workflows/publish.yml) workflows. Lint and tests run in parallel. Image building and scanning run on hosted runners; the trusted `banking-app` runner publishes the exact scanned archive to Amazon ECR without rebuilding it. CI stops after saving `image-manifest.json` with the immutable digest. No PR uses the shared Docker host or registry credentials.

The POM starts at `0.1.0-SNAPSHOT`. **B. Prepare Release** opens a reviewed POM-version PR (patch by default, explicit minor/major). Merging it triggers the same main CI. **C. Create Release**, given that successful CI run ID, records its exact image digest in a GitHub Release and opens the next development snapshot PR. Deployment remains a separate workflow in `banking-deployment`; creating an app release does not deploy anything. See [CI setup and release guide](docs/ci-cd.md) for variables, GitHub App permissions, run selection and rollback handoff.

Dependencies, including the OpenTelemetry instrumentation BOM, are pinned; Maven properties contain patch overrides for scanner findings in the Spring Boot managed versions. Refresh those overrides with a tested dependency update, not by disabling the scan. Source SHA and CI run identify each published development build.

## OpenTelemetry without a Java agent

The Maven dependency `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter` initializes tracing within Spring Boot. Its versions are aligned by `opentelemetry-instrumentation-bom`. Neither Docker image downloads an agent or starts Java with `-javaagent`.

Telemetry defaults live under `otel` in `src/main/resources/application.yml` and apply both to a direct JAR run and to Docker. The starter instruments incoming HTTP requests and JDBC calls, and adds `trace_id` and `span_id` to Logback MDC for structured log correlation. SQL statement sanitization stays enabled. Metrics continue through Micrometer/Actuator, and Alloy reads log files; the starter's metric and log exporters are disabled to avoid duplicate delivery.

Set `OTEL_EXPORTER_OTLP_ENDPOINT` to the collector base URL (default `http://127.0.0.1:4318`) and `OTEL_TRACES_SAMPLER_ARG` to the desired sampling ratio (default `0.1`; local smoke uses `1.0`). Use `OTEL_TRACES_EXPORTER=none` for a local JAR run without a collector. The existing local smoke test verifies trace transport and log correlation without any Java agent.

Official guide: [OpenTelemetry Spring Boot starter](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/getting-started/).

## Repository structure

`src/main/java/dev/banking/account/` contains `api`, `application`, `domain` and `infrastructure`. The transactional application service coordinates the domain rules and JDBC repository; the lock, balance update and ledger insert stay in one transaction. `common/` contains health/version APIs, error mapping, JWT configuration and administrative database commands. `BankingApplication` remains the component-scan root.

The [OpenAPI contract](spec/openapi.yaml) describes the existing banking routes. `make verify` runs Checkstyle, release-policy tests, OpenAPI validation and the PostgreSQL acceptance suite. `make smoke` builds both local images and exercises the API before and after restart, plus telemetry transport. GitHub workflows live at this repository's root, independently from the deployment repository.

`docker/docker-compose.infra.yml` is the local PostgreSQL/application/Alloy test fixture. `application-local.yml` is an explicit opt-in profile for running the JAR directly: it binds HTTP to localhost:8080 and still requires DB credentials and a JWT signing private key and Basic login credentials. Run migrations separately before starting it. Container defaults use HTTP on 8080 and loopback Actuator on 9000.

Maven Wrapper generation and checksum support follow the [Apache Maven documentation](https://maven.apache.org/tools/wrapper/). Publication uses Amazon ECR; vulnerability scanning uses Trivy. Tests use a disposable PostgreSQL Docker container rather than Testcontainers. There is no Redis dependency or JaCoCo coverage gate.

See [test coverage and verification commands](docs/local-verification.md).

Token authentication uses separate Spring Security filter chains for Basic login and Bearer API requests; see [Spring Security authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/dao-authentication-provider.html).
# CPU scaling demonstration

`POST /demo/cpu` accepts JSON `{"workMs":250}` (50–500 integer milliseconds;
omitted/null workMs defaults to 250). It uses the existing JWT authentication,
with no special subject restriction. Work is fixed-size SHA-256 hashing only:
no database reads/writes or external calls. One dedicated worker per task, zero
queue capacity and a 100 ms recovery gap bound resource use. Busy requests return
429 with `Retry-After: 1`; invalid bounds return 400, disabled work returns 404,
and timeout/shutdown/cancellation returns 503. Responses are not cached.

The base app defaults `CPU_DEMO_ENABLED=false`; the demo Terraform environment
sets it to true. Other module consumers stay disabled unless explicitly enabled.
The worker checks a monotonic deadline (including scheduling delay), cancellation
and interruption every 256 hashes, and never exceeds 5,000,000 hashes. Scheduling
and one batch can cause small deadline overshoot. Work cancels on async timeout,
observable disconnect/error and app shutdown; disconnect detection is not immediate,
so the worker's own deadline remains the safety limit.

Responses include `elapsedMs`, measured `cpuMs`, `iterations`, a checksum, and the
stop reason. Under Fargate throttling, 500 ms wall time can contain substantially
less CPU time; unsupported CPU accounting reports zero. Micrometer exposes
`banking.demo.cpu.active`, `.completed`, `.rejected`, `.cancelled`, `.failed` and
`.duration` through the existing private Prometheus endpoint, without user labels.
CPU is shared with normal requests despite the dedicated executor and recovery
gap. Monitor normal latency and ALB health; do not assume latency isolation.

Use the separate `k6/cpu-demo.js` workload in the parent assignment project, not
the mixed banking workload. Its default smoke makes one 50 ms CPU request. The
optional load profile is capped at 4 CPU requests/sec, 8 total VUs and 5 minutes,
and distinguishes expected 429 rejections from unexpected errors. Sustained live
load requires separate target/ceiling approval; deploying the route does not run it.
