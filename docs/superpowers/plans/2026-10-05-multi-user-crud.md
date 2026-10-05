# Multi-user CRUD Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Add an administrator-managed user directory with individual JWT logins and finish account listing.

**Architecture:** Follow the existing API/application/domain/JDBC layers. Keep the configured administrator login, add managed users in PostgreSQL, and enforce its JWT scope on the directory. Managed-user tokens carry a database-checked credential version.

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL, Liquibase, BCrypt, existing Maven and MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-05-multi-user-design.md`

## Global Constraints

- Local implementation only; no deployment, release, push or account creation endpoints.
- Preserve changeset 001, existing account ownership, balances and transaction history.
- Return only safe profile/account fields; passwords never appear in API responses.
- Lists use limit 1–100 (default 50) and nonnegative offset (default 0).
- Usernames 3–64 lowercase ASCII characters; display names 1–100 nonblank characters.
- Passwords nonblank, 12–72 characters and at most 72 UTF-8 bytes.

## Review Focus

- A regular or legacy token must never acquire directory privileges.
- Duplicate/reserved usernames, including simultaneous creation, return a safe conflict.
- Renaming keeps account ownership; deleting keeps account and ledger data.
- Password change and deletion revoke previously issued managed-user tokens.
- Migration rollback removes only the user table when rolling back one changeset.

### Task 1: Migration, directory and authentication

**Files:** Create user domain, JDBC repository, application service and API under `src/main/java/dev/banking/user/`; add `002-users.sql` and rollback SQL; modify security and token controllers and the master changelog. Test `UserManagementAcceptanceTest` and `DatabaseCommandsTest`.

**Interfaces:** Produce safe `UserProfile(UUID id, String username, String displayName)`; credentials remain internal. `UserRepository.validToken(UUID id, long version)` supports decoder validation. `UserService` exposes create/list/get/update/delete and validates reserved names and BCrypt byte limits.

- [ ] Write HTTP acceptance tests for admin CRUD, individual logins, denial, duplicates, validation, pagination, revocation, rename and preserved banking data.
- [ ] Run new tests against the unchanged committed app; expect missing directory endpoint failures.
- [ ] Add changeset 002 and JDBC operations; soft-delete users with UPDATE and retain unique names.
- [ ] Add DTO validation and directory endpoints; scope-gate all `/users` routes.
- [ ] Extend Basic login and JWT issuance/validation without changing legacy banking subjects.
- [ ] Update migration tests for two changesets and verify rollback/reapply preserves bank data.
- [ ] Run the directory and migration tests; expect zero failures.

### Task 2: Complete the partial account list and document the API

**Files:** Modify the four original partial-feature files and `JdbcAccountRepository`; create `AccountSummary`; extend `BankingAcceptanceTest`; update README, OpenAPI and Postman collection/docs.

**Interfaces:** `AccountRepository.findAllOwned(String subject, int limit, int offset)` returns `List<AccountSummary>`; `BankingService.list` passes subject and pagination through. Controller obtains subject from the validated JWT.

- [ ] Write owner-filtering, empty-list, sorting, pagination, validation and unauthenticated rejection tests; run and expect a missing endpoint failure.
- [ ] Implement the list using explicit safe columns and a bound owner filter; replace the invalid single-Balance placeholder.
- [ ] Document directory, login, soft deletion, token revocation and account listing in README/OpenAPI/Postman.
- [ ] Run `make verify` with a disposable PostgreSQL database; expect all tests, Checkstyle, Node policy tests and contract validation to pass.
- [ ] Obtain a focused independent review, fix actionable findings and rerun affected checks.
- [ ] Compare original file hashes and apply only the verified feature files; leave all unrelated files unchanged.

Execution is inline under the user's `go`; changes remain uncommitted for review.
