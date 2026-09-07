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
  - `LoginScreen.kt`, `RegisterScreen.kt`, `ForgotPasswordScreen.kt`
  - `HomeScreen.kt`
  - `UserManagementScreen.kt` — admin user CRUD, role change, link staff↔customer
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

---

## Conventions to remember (avoid re-deriving from source each time)

- **UI pattern**: Compose screens use `Scaffold` + `TopAppBar` + `LazyColumn`; dialogs (`AlertDialog`) are used for create/edit/confirm actions, gated by nullable `remember { mutableStateOf<T?>(null) }` state (e.g. `userToDelete`, `userToEdit`).
- **AuthManager API style**: methods take `onSuccess`/`onFailure` lambda callbacks (no coroutines/suspend at call site), return raw `Map<String, String>` for entities rather than typed data classes in many screens.
- **Roles**: `UserRole` enum drives both UI gating (client-side only — **no server-side role enforcement**, see SPEC §9) and role-name display via local `roleName()` helpers duplicated per screen.
- **No auth tokens**: login just returns user data; `AuthManager` holds session client-side. Don't assume JWT/bearer headers exist on API calls.
- **Login identifier = phone number, not email**: Staff (`Users` table) and Customer (`Customers` table via `/customers/login-phone`) both authenticate with a phone number + password. The `Users.phone` Kotlin column is physically stored in the DB's `email` SQL column (renamed at the Exposed/Kotlin level only, to avoid a schema migration) — don't be confused by that mismatch if you inspect the DB directly. `User.email` (Android data class) and the `getAllUsers()` map key `"email"` also just hold the phone number now (kept the old field/key names to avoid a wider rename). The old `/customers/login` (email+password) backend endpoint still exists but is no longer used by the app UI.
- **Money/collections flow**: Selling a unit creates `Customers` + `UnitCollections` rows; payments are always inserted `PENDING` and only affect `UnitCollections.paidAmount` once audited (`AUDITED`) — see SPEC §5.2.
- **Admin-only actions**: revert unit to available, suspense adjustment, user management — enforced only in UI, not backend.

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
- **S3 bucket** (`construction-files-mohan-02569`): versioning enabled + 90-day noncurrent-version expiry, AES256 default encryption, public access fully blocked. IAM user `constructionapp` scoped to S3-only on this bucket (see `infra/iam-s3-policy.json` — file has a placeholder bucket name, real policy in AWS console uses the real name above).
- **Known gaps NOT yet closed** (flagged 2026-09-07, still true unless this section is updated): no JWT/bearer auth or server-side role enforcement (see "No auth tokens" above — anyone with a customerId/paymentId can hit any endpoint), CORS `anyHost()`, no WAF/rate-limiting/fail2ban, single EC2 instance (no HA/autoscaling), Postgres self-hosted in Docker (not RDS — no point-in-time recovery, only nightly dumps), TLS cert bound to a `nip.io` IP-based hostname rather than a real domain.
- **Dependency CVEs**: fixed 2026-09-07 — `org.postgresql:postgresql` bumped 42.7.4→42.7.12 (3 HIGH CVEs: SCRAM channel-binding downgrade/DoS), `org.apache.poi:poi(-ooxml)` bumped 5.3.0→5.4.0 (CVE-2025-31672). Re-run `validate_cves` against `backend/build.gradle.kts` periodically — nothing else currently flagged.
- **Redeploy workflow**: build image locally (`docker build -t construction-backend:latest -f Dockerfile .`) → `docker save | gzip` → `scp` to EC2 → `gunzip | docker load` → `docker compose -f docker-compose.prod.yml --env-file .env up -d --no-build` (never `--build` on the instance, too little RAM to compile Gradle).


