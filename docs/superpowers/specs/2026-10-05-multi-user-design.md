# Multiple users and user management

The user approved admin-managed users with individual password logins and JWTs.
This is a local implementation only: no deployment, release, push or account
creation endpoints. Preserve all existing balances and transaction history.

## Identity and access

The existing configured Basic login remains an operator-managed administrator.
Its JWT retains `TOKEN_SUBJECT` for compatibility with the existing seeded account
and gains the `users:manage` scope. This configured administrator is outside the
managed directory; user CRUD cannot remove it or change its credentials.

Managed users log in through the existing `POST /auth/token` Basic endpoint.
They receive a stable UUID subject and no administrative scope. Password hashes
are BCrypt, never returned in API responses. No public registration or promotion
endpoint is added. Existing valid JWTs continue to work for banking, but cannot
manage users without the new scope.

New managed-user JWTs carry a user ID and credential version. Validation checks
the active database record: deleting the user or changing their password revokes
previous managed-user tokens immediately. Changing a username keeps the UUID
and account ownership stable. The administrator login name is reserved.

## API

- `POST /users`: administrator creates `{username, displayName, password}`;
  return 201, Location and `{id, username, displayName}`.
- `GET /users`: administrator lists active users ordered by username and ID.
- `GET /users/{id}`: administrator reads a user, or receives 404.
- `PUT /users/{id}`: administrator replaces username and display name; optional
  password changes credentials and revokes existing user tokens.
- `DELETE /users/{id}`: administrator marks the user deleted; return 204.
  Retain their record and username to preserve banking ownership/history.
- `GET /accounts/list`: complete the unfinished endpoint with only the current
  subject's accounts, returning `{id, balance, currency}` records ordered by ID.

Both lists accept `limit` (1–100, default 50) and `offset` (nonnegative, default
0). Empty results return 200 with `[]`. Invalid input returns 400, invalid
authentication 401, insufficient privilege 403, missing users 404 and duplicate
or reserved usernames 409. Responses never expose credential material.

Usernames are lowercase ASCII, 3–64 characters, starting with a letter and using
letters, digits, dot, underscore or hyphen. Display names are nonblank and at most
100 characters. Passwords are nonblank, 12–72 characters and at most 72 UTF-8 bytes
to avoid BCrypt truncation. Directory responses disable caching.

## Persistence and compatibility

Append Liquibase changeset 002; do not change the checksum of changeset 001.
Use a separate `banking_users` table with UUID ID, unique username, display name,
password hash, deleted flag and credential version. Deletion uses UPDATE, which
matches the application's current database grants. Existing account owner
subjects and synthetic seeding stay unchanged. New users initially have no
accounts; existing account-creation functionality remains out of scope.

## Verification

Real PostgreSQL acceptance tests cover CRUD, multiple independent logins,
administrative restrictions, pagination, duplicate names, validation, token
revocation and stable account ownership after rename/deletion. Migration tests
cover upgrade, rollback of just 002 and reapplication without banking-data loss.
Run Maven verification, Java Checkstyle, Node policy tests and OpenAPI validation,
then obtain one focused independent review. Apply only verified feature changes
back to the original checkout after checking it has not changed concurrently.
