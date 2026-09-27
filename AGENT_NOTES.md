# Agent Notes — Read This First (Token-Efficiency Memory)

> Purpose: give an AI coding agent enough context to work in this repo **without**
> re-reading every file or having the user re-paste full file contents each session.
> Update this file whenever structure/conventions change meaningfully.

## How to use this file
1. Read `SPEC.md` for full architecture/domain/API reference (already comprehensive, dated 2026-09-03).
2. Use the **file index** below instead of `list_dir`/`semantic_search` to jump straight to a file.
3. Use `grep_search`/`read_file(offset,limit)` for targeted lookups instead of dumping whole files into chat.
4. Only paste full file contents in chat when asking for a from-scratch rewrite; otherwise reference the path and let the agent read it.

---

## File Index (avoid re-discovering structure)

### Android app — `app/src/main/java/com/panakam/construction/`
- `MainActivity.kt` — entry point
- `auth/` — `AuthManager.kt` (session + all staff/user API calls), `User.kt`, `UserRole.kt` (enum: ADMIN, PROJECT_MANAGER, SITE_WORKER, AUDITOR, CUSTOMER)
- `data/` — `LocalProjectStorage`, `S3FileManager`
- `database/` — `DatabaseManager.kt` (HTTP client, `BASE_URL` config to backend)
- `model/` — `Project.kt`, `ProjectFile.kt`
- `ui/navigation/` — `AppNavigation.kt`, `Routes.kt` (single-Activity NavHost)
- `ui/theme/` — Material 3 blue gradient theme
- `ui/screens/` (top-level):
  - `LoginScreen.kt`, `StaffChangePasswordScreen.kt` (forced first-login reset for admin-created staff), `ForgotPasswordScreen.kt`
  - `HomeScreen.kt`
  - `UserManagementScreen.kt` — admin user CRUD, role change, link staff↔customer, **Add Staff** (creates account w/ default password = phone number)
  - `CollectionsScreen.kt` — paid/pending per unit/project
  - `SuspenseScreen.kt` — admin-only holding/adjusted suspense entries
- `ui/screens/projects/`:
  - `ProjectListScreen.kt`, `AddEditProjectScreen.kt`, `ProjectDetailScreen.kt`
  - `ProjectUnitsScreen.kt` — unit grid, sell/block/revert
  - `ProjectInventoryScreen.kt`, `ProjectFinancialsScreen.kt`, `ProjectFilesScreen.kt`
  - `ProjectSalesRepsScreen.kt`
  - `CustomerDetailScreen.kt`, `CustomerPaymentsScreen.kt` (AddPaymentDialog: receipt upload→extract→confirm), `CustomerAllPaymentsScreen.kt`
  - `CustomerPortalScreen.kt`, `CustomerChangePasswordScreen.kt` (forced first-login reset)
  - `AuditorPaymentsScreen.kt` — cross-project audit queue

### Backend — `backend/src/main/kotlin/com/panakam/construction/backend/`
- `Application.kt` — Ktor entry (`fun main()`), route wiring, env vars
- `db/` — `Tables.kt` (Exposed table definitions — see SPEC §4)
- `routes/` — `AuthRoutes.kt`, `CustomerRoutes.kt`, `ProjectRoutes.kt`, `UnitsRoutes.kt`, `InventoryRoutes.kt`, `FinancialRoutes.kt`, `FileRoutes.kt`, `PaymentRoutes.kt`, `CollectionRoutes.kt`, `SuspenseRoutes.kt`, `AuditRoutes.kt`
- `service/` — `OcrService.kt` (PDFBox + AWS Textract extraction), `AuditService.kt`
- `migrations/` — one-off SQL migration scripts (currently just `2026-09-26-split-customer-accounts.sql`)

---

## Conventions to remember (avoid re-deriving from source each time)

- **UI pattern**: Compose screens use `Scaffold` + `TopAppBar` + `LazyColumn`; dialogs (`AlertDialog`) are used for create/edit/confirm actions, gated by nullable `remember { mutableStateOf<T?>(null) }` state (e.g. `userToDelete`, `userToEdit`).
- **AuthManager API style**: methods take `onSuccess`/`onFailure` lambda callbacks (no coroutines/suspend at call site), return raw `Map<String, String>` for entities rather than typed data classes in many screens.
- **Roles**: `UserRole` enum drives UI gating AND (since 2026-09-20) server-side enforcement — `authenticate(AUTH_JWT)` + `call.requireRole(...)`/`requireSelfOrRole(...)` now cover Auth/Units/Customer/Payment routes. Don't assume "UI-only, no backend check" for these anymore.
- **Auth tokens**: login returns a signed JWT (`AuthManager.getToken()`); every request sends `Authorization: Bearer <token>` (`attachAuthHeader`). Backend re-validates the user/customer still exists on every call, not just at login — a deleted account is locked out immediately.
- **Staff account creation is Admin-only** (fixed 2026-09-20 — previously `POST /auth/register` let ANYONE from the Play Store self-register as `SITE_WORKER` and immediately see every project's units, a serious data leak). Now `POST /auth/register` only ever succeeds once, to bootstrap the first Admin on a brand-new deployment (403 once any user exists) — **and there is no UI entry point for it at all** (the "First-time setup? Create the Admin account" button/`RegisterScreen`/`AuthManager.register()` were removed from the app on 2026-09-20; the very first Admin is now bootstrapped only via a direct API call, e.g. `curl POST /auth/register`, never from the app). All other staff accounts are created by an Admin via `UserManagementScreen` → "Add Staff" → `POST /auth/users` (admin JWT required); default password = the staff member's own phone number, `mustChangePassword=true` forces `StaffChangePasswordScreen` on next login (mirrors the existing Customer flow, via `POST /auth/users/{userId}/change-password`).
- **Login identifier = phone number, not email**: Staff (`Users` table) and Customer (`Customers` table via `/customers/login-phone`) both authenticate with a phone number + password. The `Users` table's DB column is `phone` (the previous note claiming it was stored under an `email` SQL column was checked against production on 2026-09-20 and is incorrect/stale — actual column is `phone`, with a `users_email_unique` constraint name left over from an old rename that doesn't reflect a real `email` column). `User.email` (Android data class) and the `getAllUsers()` map key `"email"` still just hold the phone number (kept the old field/key names to avoid a wider rename). The old `/customers/login` (email+password) backend endpoint still exists but is no longer used by the app UI.
- **Money/collections flow**: Selling a unit creates `Customers` + `UnitCollections` rows; payments are always inserted `PENDING` and only affect `UnitCollections.paidAmount` once audited (`AUDITED`) — see SPEC §5.2.
- **Admin-only actions**: revert unit to available, suspense adjustment, user management — enforced only in UI, not backend.
- **Project deletion safeguard (added 2026-09-22)**: `DELETE /projects/{projectId}` now requires `authenticate(AUTH_JWT)` + `ADMIN` role (previously `ProjectRoutes.kt` had **no auth at all** — anyone could hit it), and refuses (409) to delete a project that already has any `Customers` or `CustomerPayments` rows on record, since the DB-level `ReferenceOption.CASCADE` FKs would otherwise silently wipe out every unit/customer/payment/KYC-doc/agreement/financial/file tied to that project in one shot. Only an empty/never-sold project can still be deleted freely. No "force delete" override exists by design. `ProjectDetailScreen.kt`'s confirm dialog message was also made explicit about what gets destroyed. Note: GET/POST/PUT on `/projects` are still completely unauthenticated — only DELETE was hardened here.
- **`Customers` vs `CustomerAccounts` (split 2026-09-26 — READ THIS before touching login/credential logic)**: `Customers` is now **one row per UNIT PURCHASE**, not one row per person — `projectId`/`unitId`/`name`/`address`/pricing/**KYC fields (`aadharNumber`/`aadharS3Key`/`kycStatus`)** all stay here, deliberately unit-scoped (a person can buy Unit A individually and Unit B jointly with a co-applicant, needing different KYC identities per unit — see `CustomerKycDocuments`, already correctly modeled this way). Login/identity-recovery credentials — `phone` is still on `Customers` too (kept, used for joins/search), but `loginEmail`, `passwordHash`, `mustChangePassword`, `secQuestion`, `secAnswerHash`, `contactEmail` moved OUT into a new `CustomerAccounts` table, **one row per phone number**, joined via plain `Customers.phone == CustomerAccounts.phone` (no FK column, no synthetic id — phone itself is `CustomerAccounts`' primary key). This fixes a real bug: a customer who owns 2+ units used to have their password change only apply to whichever unit's `Customers` row they happened to be logged in as, silently leaving sibling units with a stale default password/`mustChangePassword=true` forever (`login-phone` masked this by trying every sibling row's hash). Now there's exactly one password/flag per phone, full stop — `login-phone` does a single bcrypt check instead of looping. See `backend/migrations/2026-09-26-split-customer-accounts.sql` for the backfill logic/rationale (picks the most-likely-correct password per phone when sibling rows had already diverged pre-migration — a few affected customers, e.g. phone `8074747371`, were also proactively reset to their default password + forced first-login change as a safety net). **Known deploy gap**: on the actual 2026-09-26 prod redeploy, the backend's own `SchemaUtils.createMissingTablesAndColumns()` startup call did NOT auto-create `customer_accounts` despite the new code/table being correctly compiled in (root cause not conclusively found) — had to run the migration SQL by hand via `db-connect.sh`. If you add another brand-new table in the future, verify with `\dt` right after deploy instead of assuming auto-creation worked.

---

## Token-saving workflow tips for this repo specifically

- Don't ask for/paste whole screen files to make small UI tweaks — reference the screen name (e.g. "in `UserManagementScreen.kt`, `ChangeRoleDialog`") and let the agent `read_file`/`grep_search` just that region.
- For backend route changes, reference the specific route file + endpoint from the SPEC §6 table rather than re-explaining the whole API surface.
- For "add a new screen like X" requests, point to the closest existing analog screen by name (list above) instead of describing patterns from scratch.
- Keep `SPEC.md` and this file updated after structural changes (new tables, new routes, new screens) so future turns start from an accurate summary instead of a fresh full-codebase scan.

---

## Infra / Ops (as of 2026-09-07)

- **Prod host**: single EC2 t4g.micro (`13.206.219.67`, `ubuntu@`, key `~/.ssh/construction-key.pem`), SG `sg-0849d4adcbeadcf95` (22 restricted to one IP, 80/443 open). Nginx (host-installed, not dockerized) reverse-proxies `https://13.206.219.67.nip.io` → `127.0.0.1:8080` with Let's Encrypt (auto-renews via `certbot.timer`).
- **DB backups**: `infra/backup-postgres.sh` — daily `pg_dump` (root cron, `17 2 * * *`) → gzip → uploaded to `s3://construction-files-mohan-02569/db-backups/` via a throwaway `amazon/aws-cli` container (no aws-cli installed on host). 30-day retention via S3 lifecycle rule (`infra/s3-lifecycle-policy.json`). **This did NOT exist before 2026-09-07** — Postgres previously had ZERO backup (only a Docker named volume on one instance).
- **S3 bucket** (`construction-files-mohan-02569`): versioning enabled + 90-day noncurrent-version expiry, AES256 default encryption, public access fully blocked. IAM user `constructionapp` — **correction (verified 2026-09-20): this user is NOT actually S3-only** despite `infra/iam-s3-policy.json`'s intent; it also has EC2 `DescribeSecurityGroups`/`AuthorizeSecurityGroupIngress`/`RevokeSecurityGroupIngress` permission (confirmed working live via `aws ec2 ...` calls with these creds). Don't assume it's S3-scoped when reasoning about blast radius — re-audit its actual IAM policy next time you're in the AWS console.
- **Known gaps NOT yet closed** (updated 2026-09-20): CORS `anyHost()`, no WAF/rate-limiting/fail2ban, single EC2 instance (no HA/autoscaling), Postgres self-hosted in Docker (not RDS — no point-in-time recovery, only nightly dumps), TLS cert bound to a `nip.io` IP-based hostname rather than a real domain. (JWT + server-side role enforcement on Auth/Units/Customer/Payment routes, and admin-only staff creation, were fixed 2026-09-20 — see above.)
- **Dependency CVEs**: fixed 2026-09-07 — `org.postgresql:postgresql` bumped 42.7.4→42.7.12 (3 HIGH CVEs: SCRAM channel-binding downgrade/DoS), `org.apache.poi:poi(-ooxml)` bumped 5.3.0→5.4.0 (CVE-2025-31672). Re-run `validate_cves` against `backend/build.gradle.kts` periodically — nothing else currently flagged.
- **Redeploy workflow — now scripted, use `infra/deploy.sh` instead of the manual steps** (added 2026-09-26; manual steps below kept for reference/debugging). **NOT invocable via chat "/" slash-commands** — this chat's `/` list is reserved for the assistant's own BUILT-IN commands (`/explain`, `/fix`, `/tests`, etc.) and can't be extended with arbitrary repo scripts. Instead, exposed as Gradle tasks (root `build.gradle.kts`, group `"ops"`) so they're runnable from the IDE's Gradle tool window / gutter icon OR a plain terminal:
  - `./gradlew deployToAws` (or `./infra/deploy.sh` directly) — builds the backend Docker image (`--platform linux/arm64`), builds the Android debug APK (`jagadhabi-app-latest.apk` at repo root), then deploys to EC2 (opens SSH from your IP only if not already allow-listed, `scp`+`docker load`+`docker compose up -d --no-build`, polls `/health` up to ~60s, prints `docker ps`), and revokes the SSH rule again **only if it added it this run** (never revokes a pre-existing rule). Cleans up temp tarballs on both sides via a `trap ... EXIT`.
  - `./gradlew deployBackendOnly` (`infra/deploy.sh --skip-app`) — backend-only redeploy, skips the Gradle/APK build.
  - `./gradlew compileAll` (`infra/deploy.sh --skip-backend-deploy`) — compile everything locally, don't touch AWS at all (pre-flight compile check).
  - Manual step-by-step (what the script automates — still useful if the script itself needs debugging): build image locally (`docker build --platform linux/arm64 -t construction-backend:latest -f Dockerfile .` — instance is arm64/t4g, always pass `--platform linux/arm64` even when building on an Apple Silicon Mac, to be explicit) → `docker save | gzip` → `scp` to EC2 → `gunzip | docker load` → `docker compose -f docker-compose.prod.yml --env-file .env up -d --no-build` (never `--build` on the instance, too little RAM to compile Gradle). Compose file on the instance lives at `/home/ubuntu/app/docker-compose.prod.yml`.
  - **⚠️ SSH is IP-restricted (SG `sg-0849d4adcbeadcf95`, port 22)** — `infra/deploy.sh`/`infra/ocr-logs.sh` handle the open/revoke dance automatically (checking first whether a rule for your IP already exists, so a pre-existing allow-list entry is never revoked). If doing it manually: `curl -4 -s ifconfig.me` → `aws ec2 authorize-security-group-ingress --group-id sg-0849d4adcbeadcf95 --protocol tcp --port 22 --cidr <YOUR_IP>/32 --region ap-south-1` → ... → `aws ec2 revoke-security-group-ingress` with the same args when done.
- **OCR service logs — use `infra/ocr-logs.sh` (or `./gradlew ocrLogs`)** (added 2026-09-26; same "not a chat slash-command" caveat as above): fetches `docker logs` from the `ocr-service` container on the prod instance, handling the same SSH-allowlist dance as `deploy.sh`. `./infra/ocr-logs.sh` (last 200 lines), `./infra/ocr-logs.sh -n 500`, `./infra/ocr-logs.sh -f` (follow/tail, Ctrl+C to stop), `./infra/ocr-logs.sh --since 1h` — any args are passed straight through to `docker logs` on the instance. The `./gradlew ocrLogs` task form always runs the no-args (last 200 lines) version — pass extra flags via the script directly, not the Gradle task, if you need `-f`/`--since`/etc.
- **`infra/db-connect.sh` (or `./gradlew db` / `./infra/ops.sh db`)** (added 2026-09-26): opens an interactive `psql` session against the prod Postgres database. Handles the same SSH-IP-allowlist dance as the other scripts. Postgres does **not** publish 5432 to the host (see `docker-compose.prod.yml`) — it's only reachable from the `backend` container on the Docker network — so this script SSHes into the EC2 instance and `docker exec -it postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'`, letting the container's own env vars resolve the credentials (no password needed, local-socket trust auth inside the container). `./infra/db-connect.sh` alone drops you into an interactive prompt; extra args are passed through to `psql` inside the container, e.g. `./infra/db-connect.sh -c "SELECT count(*) FROM \"Customers\";"` for a one-shot query. Auto-revokes the SSH rule on exit, same as `connect-aws.sh`/`ocr-logs.sh`.
- **`infra/ops.sh` — single dispatcher with exactly 4 subcommands** (added 2026-09-26, `db` added same day), the practical zero-token "routine task" shortcut requested in place of real chat `/` commands (confirmed not possible — the chat's `/` menu is a fixed client-side list of the assistant's own built-ins, not extensible from repo files):
  - `./infra/ops.sh build` → `infra/deploy.sh` (compile backend+app, build APK, deploy to AWS, validate health). Extra args pass through, e.g. `./infra/ops.sh build --skip-app`.
  - `./infra/ops.sh ocr_logs` → `infra/ocr-logs.sh`. Extra args pass through, e.g. `./infra/ops.sh ocr_logs -f`.
  - `./infra/ops.sh connect_aws` → `infra/connect-aws.sh` — opens SSH (if not already allow-listed) and drops you into an **interactive** shell on the prod instance; auto-revokes the SSH rule (only if it added it) once you `exit` the session. This is the one script in the set that's interactive/blocking rather than one-shot.
  - `./infra/ops.sh db` → `infra/db-connect.sh` — opens SSH (if needed) and drops you into an **interactive** `psql` session against prod Postgres (or runs a one-shot query if extra args like `-c "..."` are passed); also interactive/blocking like `connect_aws`.
  - Matching Gradle task aliases also exist (group `"ops"`): `build_`, `ocr_logs`, `connect_aws`, `db` (underscore names to match `ops.sh` exactly) alongside the original camelCase tasks (`deployToAws`, `deployBackendOnly`, `compileAll`, `ocrLogs`). Root-level plain `build` was deliberately avoided as a task name (reserved lifecycle task name risk if a plugin is ever applied to the root project).
- **App distribution**: no signing config exists in `app/build.gradle.kts` (no keystore in repo) — the shipped `jagadhabi-app-latest.apk` (repo root) is a **debug** build (`./gradlew :app:assembleDebug`, output at `app/build/outputs/apk/debug/app-debug.apk`), not a Play-Store-style release build. As of 2026-09-24 it's also mirrored to S3 at `s3://construction-files-mohan-02569/app-releases/jagadhabi-app-latest.apk` — since the bucket fully blocks public access, share it via a presigned URL (`aws s3 presign s3://construction-files-mohan-02569/app-releases/jagadhabi-app-latest.apk --region ap-south-1 --expires-in 604800`, max 7 days), not a direct link.
- **Deployed 2026-09-24**: backend redeployed with the agreement-templates `.docx` support (`OcrService.extractTextFromDocx`), `NumberToWordsConverter` (`{{TOTAL_AMOUNT_WORDS}}` placeholder), and joint-agreement placeholders (`{{PARTY_TYPE}}`, `{{CO_APPLICANT_*}}`) — confirmed live via `/health` and `/projects` returning 200 post-deploy.


