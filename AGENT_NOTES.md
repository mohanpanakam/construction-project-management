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
- **Redeploy workflow**: build image locally (`docker build --platform linux/arm64 -t construction-backend:latest -f Dockerfile .` — instance is arm64/t4g, always pass `--platform linux/arm64` even when building on an Apple Silicon Mac, to be explicit) → `docker save | gzip` → `scp` to EC2 → `gunzip | docker load` → `docker compose -f docker-compose.prod.yml --env-file .env up -d --no-build` (never `--build` on the instance, too little RAM to compile Gradle). Compose file on the instance lives at `/home/ubuntu/app/docker-compose.prod.yml`.
  - **⚠️ SSH is IP-restricted (SG `sg-0849d4adcbeadcf95`, port 22) — you WILL need to open it temporarily unless deploying from the one already-allow-listed IP.** Steps (don't skip the revoke step — it's easy to forget and leave the SG open):
    1. Get your current IPv4: `curl -4 -s ifconfig.me`
    2. Open it: `aws ec2 authorize-security-group-ingress --group-id sg-0849d4adcbeadcf95 --protocol tcp --port 22 --cidr <YOUR_IP>/32 --region ap-south-1`
    3. Do the `scp`/`ssh`/`docker load`/`docker compose up -d` steps above.
    4. Verify health: `curl -sk https://13.206.219.67.nip.io/health` and `docker ps` on the instance (backend should show `Up ... (healthy)` with a fresh `docker images` timestamp).
    5. **Revoke the rule you added** (mirror the exact `authorize` args): `aws ec2 revoke-security-group-ingress --group-id sg-0849d4adcbeadcf95 --protocol tcp --port 22 --cidr <YOUR_IP>/32 --region ap-south-1`
    6. Clean up the local/remote `image.tar.gz` temp files.


