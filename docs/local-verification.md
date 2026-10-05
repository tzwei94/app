# Application tests

Run from `app/` in the parent repository, or the root of a standalone application checkout. Use Java 25, Node.js 24, Docker with Compose, OpenSSL, curl, unzip, uv and Make:

```sh
make verify
make smoke
```

The checked-in `./mvnw` pins Maven 3.9.11 and verifies the downloaded distribution. An additional system Maven installation is unnecessary. On macOS, select Java 25 first if needed: `export JAVA_HOME="$(/usr/libexec/java_home -v 25)"`.

## Checks and prerequisites

| Command | Checks | Additional requirements |
|---|---|---|
| `make lint` | Java Checkstyle and Node release/image-policy tests | Java 25, Node.js 24, unzip |
| `make contract` | OpenAPI 3.0.3 syntax/schema validation | uv; installs pinned `openapi-spec-validator==0.7.2` |
| `make test` | Maven verification, domain tests, real PostgreSQL acceptance tests and Liquibase administrative commands | Docker and OpenSSL, unless an external test database is supplied |
| `make verify` | All three checks above | Does not run `actionlint` or build/scan container images |
| `make smoke` | Builds local API/Alloy images and runs the HTTP/telemetry fixture | Docker Compose, curl, OpenSSL, uv and sibling `../deployment/deploy/monitoring` checkout |

The Java test script creates a disposable PostgreSQL container, publishes it on an assigned loopback port and removes it on exit. Tests cover money rules, JWT validation, Basic token issuance, account ownership and paginated listing, administrator-only user CRUD, independent user login, credential/token revocation, concurrent duplicate usernames, concurrent mutations and retries, transaction rollback, and Liquibase migration, rollback and checksum commands.

To supply a test database, export both `BANK_TEST_DB_URL` (a JDBC URL reachable from the host JVM) and `BANK_TEST_DB_PASSWORD`; the username must be `banking_test`. Use a disposable database: the suite clears its banking tables. The script does not remove an externally supplied database.

## Smoke checks and their limits

`make smoke` generates temporary credentials and a signing key, starts PostgreSQL, runs migrations with the synthetic seed, and starts the API, Alloy and a local authenticated receiver. It checks Basic login, authenticated banking requests, idempotent retries and anonymous rejection, repeats the banking sequence after an API restart, and repeats it while the receiver is stopped. It then restarts the receiver and requires nonempty authenticated requests on all three signal paths. It also checks trace/span IDs in operation logs and absence of the synthetic account UUID.

Each banking sequence deposits and withdraws the same amount. These checks demonstrate API availability across restart and receiver outage, but do not independently prove that a nonzero balance change survived restart or that every queued telemetry item was replayed. The receiver counts requests; it does not decode payloads or store/query telemetry. Verify storage and retrieval separately against the deployed monitoring backend.

The script removes its Compose containers and volumes and deletes `local-data/demo.key` on exit. It leaves the extracted `local-data/application.jsonl` for inspection after a successful run. These paths are Git-ignored. Avoid concurrent smoke runs in the same checkout because their local key/log filenames are shared.

The local API uses an assigned loopback HTTP port; deployed smoke uses HTTPS with normal certificate verification. The fixture is temporary and is removed when the script ends; use the parent repository's [quickstart](https://github.com/tzwei94/banking-platform/blob/main/QUICKSTART.md) for a stack that stays available for Postman.

## CI and release checks

For workflow changes, run `actionlint` from this repository as shown in the [CI guide](ci-cd.md#local-verification). The Node tests cover version selection, POM edits, CI provenance, image identity, immutable release manifests and retry recovery. Remote GitHub App permissions, OIDC trust, ECR pushes and Release API behavior require a configured workflow run.

CI builds the Linux AMD64 production image and scans the exact archive with Trivy before publication. Its gate fails on fixable HIGH/CRITICAL vulnerabilities (`ignore-unfixed: true`); it is not a claim that the image has no vulnerabilities. Local smoke does not perform this scan and normally builds for the Docker host architecture.
