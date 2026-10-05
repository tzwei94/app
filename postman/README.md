# Banking API in Postman

Import both files with Postman's **Import** action:

- [Banking-API.postman_collection.json](Banking-API.postman_collection.json): banking, authentication, health and user-management requests, with response assertions.
- [Local.postman_environment.json](Local.postman_environment.json): reusable environment template with empty login credentials and token.

Select **Banking API - local template** as the active environment. Set these values:

| Variable | Value |
|---|---|
| `base_url` | Running API address, without a trailing slash. Direct local JAR: `http://localhost:8080`. Docker Compose: use its assigned host port. Deployed API: public `https://` hostname covered by the ALB certificate. |
| `token_username` | The configured administrator `TOKEN_USERNAME`, or a managed user's username. |
| `token_password` | The matching administrator or managed-user password; keep this local. |
| `api_token` | Automatically saved by **Get token**; no manual generation needed. |
| `account_id` | Defaults to Alice's synthetic account, `00000000-0000-0000-0000-000000000001`. |
| `amount` | Positive JSON number with at most two decimal places; defaults to `1.00` SGD for both deposit and withdrawal. |

## Local Docker setup

Start the stack using the main repository's [quickstart](https://github.com/tzwei94/assignment/blob/main/QUICKSTART.md). Set `base_url` to its assigned HTTP address and copy the startup `TOKEN_USERNAME` / `TOKEN_PASSWORD` into the corresponding Postman environment variables.

To rediscover the quickstart port without printing credentials, run from the parent repository root:

```sh
docker port banking-quickstart-api-1 8080/tcp
```

For example, `127.0.0.1:49152` means `base_url=http://127.0.0.1:49152`. Use the current output, not a previously saved port. If you selected a different Compose project name, inspect that project's API container instead. `make smoke` creates a temporary stack and removes it when finished, so its URL is not a persistent Postman endpoint.

`ECONNREFUSED` means no service is accepting the connection at that address; check the running container and host port before changing authentication. HTTP 401 means the API was reached but credentials or the Bearer token need attention. A successful `/readyz` checks database connectivity only; migrations must still have completed before banking requests work.

Send **Authentication → Get token** (`POST /auth/token`). It uses Basic authentication and saves the returned `access_token` as `api_token` automatically. A failed login clears the previous token. Tokens expire after 15 minutes; send Get token again when they expire. No Python token generator is needed. The configured administrator receives `TOKEN_SUBJECT` (default `alice`) and the `users:manage` scope. A managed user receives their immutable UUID subject, with no directory-management privileges. Clients cannot choose token claims. The migration seed belongs to `alice`; another `TOKEN_SUBJECT` will get 404 for that seeded account.

Local HTTP needs no certificate settings. Use public HTTPS with normal certificate verification for deployed Basic authentication. Keep credential-bearing exports local; the committed template has empty credentials.

## Send requests or run the collection

Send the **Health** requests first, then **Authentication → Get token**. The **Banking** folder inherits Bearer authentication from the collection. Health requests and **Reject missing token** explicitly use no authentication. Token requests use Basic authentication. User-management requests require the configured administrator's Bearer token.

Run the Banking folder in its listed order, using the Collection Runner or sending requests manually:

1. **Get balance** reads the current balance.
2. **Deposit** adds `amount`, generating a new `deposit_key` each time it is sent.
3. **Retry same deposit** reuses that key and verifies the original response. Send Deposit first and keep `amount` and `account_id` unchanged. Repeating this retry does not add money again.
4. **Withdraw** subtracts the same `amount`, generating a new `withdrawal_key` each time.
5. **Get final balance** reads the resulting balance.
6. **List owned accounts** returns only the authenticated subject's accounts.
7. **Reject missing token** expects HTTP 401.

On a fresh seeded account, the default sequence is SGD 100 → 101 → 101 → 100. A fully successful sequence leaves the starting balance unchanged. Sending mutations individually or stopping a run midway can change the balance. Each request includes response assertions, visible in Postman's test results. Use a synthetic account for these requests.

The same collection works against a deployed API: duplicate the environment, set its public HTTPS URL and the configured Basic credentials. See the [API contract](../README.md#api-contract) for validation and error responses.

## User management

First obtain an administrator token with **Authentication → Get token**, then run
**User management (creates and deletes a synthetic user)** in order. Create
synthetic user generates `managed_username` and a temporary `managed_password`,
and stores `managed_user_id`. The individual login saves `managed_api_token`
without overwriting the administrator's `api_token`. The requests verify list,
read and update, rejection of regular-user directory access, soft deletion and
revocation of the deleted user's token. A new user has no accounts; no account
creation is performed. Keep generated password/token values in the local
Postman environment and out of committed exports.

If the runner stops before Delete user, finish that request with the
administrator token. Soft deletion keeps a tombstone and reserves the final
username; no accounts or transaction history are erased. Apply Liquibase
changeset 002 with the existing migration command before using these endpoints.

## Command-line collection run

With Node.js/npm installed, the same collection can be checked with Newman using a local environment export containing the configured Basic credentials:

```sh
# Run from the app repository root.
npx --yes newman@6.2.1 run postman/Banking-API.postman_collection.json \
  --environment /absolute/path/to/local.postman_environment.json
```

The environment template defaults to port 8080; update it for Docker's assigned host port before running. Newman runs the banking mutations and synthetic-user CRUD requests too.
