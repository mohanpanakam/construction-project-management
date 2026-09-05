# Construction Project Management — System Specification

> Generated from the current codebase (`app/`, `backend/`) as of 2026-09-03.
> This document reflects **what is actually implemented**, not aspirational features.

---

## 1. Overview

A full-stack construction project management system with:

- **Android app** (Kotlin, Jetpack Compose) — used by Admin, Project Manager, Site Worker, Auditor, and Customer roles.
- **Backend API** (Kotlin, Ktor) — REST JSON API backed by PostgreSQL (via Exposed ORM) and S3/MinIO for file storage.
- **Infrastructure**: Docker Compose (Ktor backend + PostgreSQL + MinIO).

```
Android App (Jetpack Compose)
       │  HTTP/JSON
       ▼
Ktor Backend (Kotlin) ──► PostgreSQL (Exposed ORM, HikariCP pool)
       │
       └──► Amazon S3 / MinIO (file storage via presigned URLs)
       └──► AWS Textract (optional, OCR_PROVIDER=TEXTRACT only)
```

---

## 2. Roles & Access Model

Defined in `app/src/main/java/com/panakam/construction/auth/UserRole.kt`:

| Role | Description |
|---|---|
| `ADMIN` | Full access; only role that can revert sold units and adjust suspense entries |
| `PROJECT_MANAGER` | Manages projects, units, inventory, financials, customers |
| `SITE_WORKER` | Field-level access; cannot write payments |
| `AUDITOR` | Views and audits all customer payments across projects (read-only elsewhere) |
| `CUSTOMER` | Portal-only access; views own unit(s), uploads own payment receipts |

`Users` table (staff: Admin/PM/Site Worker/Auditor) is separate from `Customers` table (buyers). Staff self-register via `/auth/register`; customer accounts are created internally when a unit is sold (no public customer self-registration).

---

## 3. Authentication

### Staff auth (`Users` table) — `backend/routes/AuthRoutes.kt`

| Endpoint | Method | Purpose |
|---|---|---|
| `/auth/register` | POST | Create staff account (name, phone number, password ≥6 chars, role, security Q&A) |
| `/auth/login` | POST | Phone number + password login (BCrypt) |
| `/auth/biometric` | POST | Re-validate account exists by phone number (no password check) — used after device biometric unlock |
| `/auth/security-question` | GET | Fetch security question by phone number |
| `/auth/reset-password` | POST | Reset password via security answer |
| `/auth/users` | GET | Admin: list all staff users |
| `/auth/users/{userId}/role` | PUT | Admin: change a user's role |
| `/auth/users/{userId}` | DELETE | Admin: delete a staff user |

### Customer auth (`Customers` table) — `backend/routes/CustomerRoutes.kt`

| Endpoint | Method | Purpose |
|---|---|---|
| `/customers/login` | POST | Login by `loginEmail` + password (active customers only) — backend endpoint retained, but the Android app UI no longer exposes an email login option; the app always uses phone-based login |
| `/customers/login-phone` | POST | **Primary customer login path used by the app.** Login by phone + password; supports multi-unit customers (same phone, multiple `Customers` rows) |
| `/customers/{customerId}/change-password` | POST | Change password, clears `mustChangePassword` flag |

**First-login password policy**: when a customer is created without an explicit password, the **phone number becomes the default password** and `mustChangePassword = true`. The app force-navigates to `CustomerChangePasswordScreen` on first login (back navigation blocked) until the password is changed.

---

## 4. Domain Model (PostgreSQL tables — `backend/db/Tables.kt`)

| Table | Key Fields | Purpose |
|---|---|---|
| `Users` | userId, name, phone(unique, stored in the `email` SQL column for backward compatibility), passwordHash, role, secQuestion/secAnswerHash | Staff accounts. Login identifier is the phone number, not an email address. |
| `Projects` | projectId, name, location, status, budget, projectType (`Builder Owned`/`Joint Development`), landOwnerName/Share | Construction projects |
| `Units` | unitId, projectId, unitNumber, floor, type, sba, status, availability (`Available`/`Blocked`/`Sold`), owner (`Builder`/`LandOwner`) | Sellable units per project |
| `Inventory` | itemId, projectId, name, quantity, availableQuantity, category, status | Material/inventory tracking |
| `Financials` | recordId, projectId, type (`Expense`/etc.), category, amount, date | Project-level financial ledger |
| `ProjectFiles` | fileId, projectId, fileName, folder, s3Key, contentType | Project documents/photos in S3 |
| `Customers` | customerId, projectId, unitId, name, phone, loginEmail, passwordHash, perSftPrice, gstPercentage, totalCost, **isActive**, **mustChangePassword** | Unit buyers; one row per unit purchased (same phone can have multiple rows) |
| `CustomerPayments` | paymentId, customerId, amount, paymentDate, transactionType, transactionId, chequeNumber/Date, payer/beneficiary bank details, receiptS3Key, **auditStatus** (`PENDING`/`AUDITED`/`REJECTED`) | Payment ledger with audit workflow |
| `AuditLogs` | logId, tableRef, recordId, action, changedBy, changedAt, oldValues, newValues | Generic audit trail for all mutating operations |
| `UnitCollections` | collectionId, projectId, unitId, perSftPrice, gstPercentage, baseAmount, gstAmount, totalAmount, paidAmount, pendingAmount, paymentStatus, **status** (`Active`/`Reverted`) | Sale-time pricing snapshot + running payment reconciliation per unit sale |
| `SuspenseEntries` | suspenseId, projectId, unitId, originalCustomerName/Phone, saleAmount, collectedAmount, reason, revertedBy, **status** (`Holding`/`Adjusted`) | Holds funds when a sold unit is reverted to Available |

### Key relationships
- `Units.projectId -> Projects` (cascade delete)
- `Customers.unitId -> Units`, `Customers.projectId -> Projects` (cascade delete)
- `CustomerPayments.customerId -> Customers` (cascade delete)
- `UnitCollections`/`SuspenseEntries` reference `unitId`/`projectId` by convention (not FK-enforced to `Units` directly for collections/suspense, allowing history to persist after reverts)

---

## 5. Core Workflows

### 5.1 Unit Sale → Customer + Collection

1. PM/Admin sells a unit from `ProjectUnitsScreen` (rate/sft + GST captured, total auto-calculated).
2. Backend creates/reuses `Customers` row (default password = phone if none given).
3. Backend creates a `UnitCollections` row: `baseAmount = perSft × sba`, `gstAmount = base × gst%`, `totalAmount = base + gst`, `paidAmount = 0`, `pendingAmount = total`, `paymentStatus = "Unpaid"`, `status = "Active"`.
4. `Units.availability` set to `Sold`.

### 5.2 Payment Recording (Customer or Staff) → Audit → Reconciliation

1. Payment recorded via `POST /payments` or the receipt-confirm flow (`POST /payments/confirm`) — always inserted as `auditStatus = "PENDING"`.
2. Auditor/Admin reviews and calls `PUT /payments/{id}/audit` with `AUDITED` or `REJECTED`.
3. On transition to `AUDITED` (`PaymentRoutes.kt`):
   - `delta = +paymentAmount` applied to the unit's active `UnitCollections.paidAmount`.
   - `pendingAmount = totalAmount - paidAmount` (floor 0).
   - `paymentStatus` recomputed: `Unpaid` / `Partial` / `Fully Paid`.
4. On transition **away** from `AUDITED` (un-audit): `delta = -paymentAmount` is reversed symmetrically.
5. Every reconciliation writes an `AuditLogs` entry (`PAYMENT_RECONCILE`).

### 5.3 Receipt Upload → Extraction → Customer Confirm (transaction images/PDFs)

Backend-first OCR/extraction pipeline (`backend/routes/PaymentRoutes.kt`, `backend/service/OcrService.kt`):

1. `POST /payments/receipt/upload-url` — presigned S3 PUT URL for the receipt file.
2. Client uploads file directly to S3/MinIO.
3. `POST /payments/extract` — backend fetches the object and extracts fields:
   - **PDF** → Apache PDFBox text extraction (`OcrService.extractTextFromPdf`).
   - **Image** → AWS Textract `DetectDocumentText` (`OcrService.extractTextFromImage`) — **only when `OCR_PROVIDER=TEXTRACT`**; otherwise returns a review warning and skips OCR (no cost).
   - Regex-based field parser extracts: amount, paymentDate, transactionId, transactionType (UPI/NEFT/RTGS/IMPS/Cheque/DD/Cash), cheque/DD number & date, payer/beneficiary name/bank/account.
   - Response includes `draftId`, `documentType`, `source`, `confidence` (0–1 heuristic), `needsReview`, `missingFields`, `warnings`.
4. App (`AddPaymentDialog` in `CustomerPaymentsScreen.kt`) prefills the form from the draft, shows confidence/warnings/missing fields, and lets the user edit **any** field (including cheque/DD number & date) before submitting.
5. `POST /payments/confirm` — persists the user-confirmed payload as a new `CustomerPayments` row with `auditStatus = "PENDING"` (always unaudited on save).

### 5.4 Admin Revert: Sold → Available (with Suspense + Customer Cleanup)

`POST /projects/{projectId}/units/{unitId}/revert-to-available` (Admin-only in the app UI):

1. Find the unit's active `UnitCollections` row; capture `paidAmount`, `totalAmount`, customer name/phone.
2. Mark that collection `status = "Reverted"`.
3. Insert a `SuspenseEntries` row capturing the sale amount, collected amount, reason, and who reverted it. `status = "Holding"` if money was collected, else `"Adjusted"`.
4. Set `Units.availability = "Available"` (unit becomes sellable again).
5. Deactivate (`isActive = false`) all `Customers` rows tied to that `unitId`.
6. **Cross-project cleanup**: if that phone number now has **zero** active unit allocations across *all* projects, permanently delete all `Customers` rows for that phone (full removal, not soft-delete) and write an `AUTO_DELETE_NO_ALLOCATIONS` audit log entry per deleted row.
7. Response includes `suspenseId`, `collectedAmount`, `autoDeletedCustomers` count.

### 5.5 Suspense Adjustment

`PUT /suspense/{suspenseId}/adjust` — Admin marks a `Holding` suspense entry as `Adjusted` with mandatory notes; logged via `AuditService`.

---

## 6. Backend API Reference

Base path defaults to `http://<host>:8080` (`app/database/DatabaseManager.kt` — `BASE_URL`).

### Health
- `GET /health`

### Projects (`ProjectRoutes.kt`)
- `GET /projects`, `GET /projects/{id}`, `POST /projects`, `PUT /projects/{id}`, `DELETE /projects/{id}`

### Units (`UnitsRoutes.kt`)
- `GET /projects/{projectId}/units?availability=&owner=`
- `GET /projects/{projectId}/units/summary`
- `POST /projects/{projectId}/units`
- `POST /projects/{projectId}/units/upload` (multipart Excel bulk import; header-flexible column mapping)
- `PUT /projects/{projectId}/units/{unitId}`
- `POST /projects/{projectId}/units/{unitId}/revert-to-available`
- `DELETE /projects/{projectId}/units/{unitId}`

### Inventory (`InventoryRoutes.kt`)
- `GET /inventory/{projectId}`, `POST /inventory`, `PUT /inventory/{projectId}/{itemId}`, `DELETE /inventory/{projectId}/{itemId}`

### Financials (`FinancialRoutes.kt`)
- `GET /financials/{projectId}`, `POST /financials`, `DELETE /financials/{projectId}/{recordId}`

### Files (`FileRoutes.kt`)
- `GET /projects/{projectId}/files?folder=`
- `POST /projects/{projectId}/files/upload-url`
- `GET /projects/{projectId}/files/download-url?fileId=`
- `DELETE /projects/{projectId}/files/{fileId}`

### Customers (`CustomerRoutes.kt`)
- `POST /customers/login`, `POST /customers/login-phone`
- `GET /customers/by-phone/{phone}` (all units for a phone, joined with `Units`)
- `GET /customers/project/{projectId}`, `GET /customers/unit/{unitId}`, `GET /customers/{customerId}`
- `POST /customers`, `PUT /customers/{customerId}`, `POST /customers/{customerId}/change-password`, `DELETE /customers/{customerId}`

### Payments (`PaymentRoutes.kt`)
- `GET /payments/customer/{customerId}`, `GET /payments/all?status=&projectId=`
- `POST /payments` (direct manual entry)
- `POST /payments/receipt/upload-url`
- `POST /payments/extract` (OCR/parse draft; PDF always, image only if `OCR_PROVIDER=TEXTRACT`)
- `POST /payments/confirm` (save customer-confirmed draft as `PENDING`)
- `PUT /payments/{paymentId}` (edit)
- `PUT /payments/{paymentId}/audit` (`AUDITED`/`REJECTED`/`PENDING`; triggers `UnitCollections` reconciliation)
- `DELETE /payments/{paymentId}`

### Collections (`CollectionRoutes.kt`)
- `GET /collections?projectId=`, `GET /collections/summary?projectId=`, `GET /collections/project/{projectId}`, `GET /collections/{collectionId}`
- `POST /collections`, `PUT /collections/{collectionId}`, `DELETE /collections/{collectionId}`

### Suspense (`SuspenseRoutes.kt`)
- `GET /suspense?projectId=`, `GET /suspense/summary?projectId=`, `GET /suspense/project/{projectId}`, `GET /suspense/{suspenseId}`
- `PUT /suspense/{suspenseId}/adjust`

### Audit (`AuditRoutes.kt`)
- `GET /audit/{tableName}?limit=100`
- `GET /audit/record/{tableName}/{recordId}`

---

## 7. Android App Structure

```
app/src/main/java/com/panakam/construction/
├── auth/            AuthManager, User, UserRole
├── data/            LocalProjectStorage, S3FileManager
├── database/        DatabaseManager (HTTP client → backend)
├── model/           Project, ProjectFile
└── ui/
    ├── navigation/  AppNavigation, Routes (single-Activity NavHost)
    ├── screens/     Login, Register, ForgotPassword, Home, UserManagement,
    │                Collections, Suspense
    │   └── projects/  ProjectList/Detail/AddEdit/Files/Inventory/Financials,
    │                  ProjectUnits, CustomerDetail, CustomerPayments,
    │                  CustomerPortal, CustomerChangePassword, AuditorPayments
    └── theme/       Material 3 blue gradient theme
```

### Key screens & responsibilities
- **`ProjectUnitsScreen`** — unit grid, sell/block/revert actions (Admin-only revert), sale pricing dialog.
- **`CustomerPaymentsScreen`** — payment list + `AddPaymentDialog` with receipt upload → extract → confirm flow.
- **`AuditorPaymentsScreen`** — cross-project payment audit queue.
- **`CollectionsScreen`** — paid/pending/progress view per unit/project.
- **`SuspenseScreen`** — Admin-only; holding/adjusted suspense entries with "Mark Adjusted" action.
- **`CustomerPortalScreen`** — customer's own unit(s) across all projects (by phone).
- **`CustomerChangePasswordScreen`** — mandatory first-login password reset (back-navigation blocked).

---

## 8. Infrastructure & Environment

### Docker Compose stack
- Ktor backend
- PostgreSQL
- MinIO (S3-compatible)

### Backend environment variables

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | Server port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/construction` | PostgreSQL JDBC URL |
| `DB_USER` | `postgres` | PostgreSQL username |
| `DB_PASSWORD` | `postgres` | PostgreSQL password |
| `AWS_REGION` | `us-east-1` | AWS region (used for S3 client + optional Textract) |
| `AWS_ACCESS_KEY_ID` | `local` | AWS/MinIO access key |
| `AWS_SECRET_ACCESS_KEY` | `local` | AWS/MinIO secret key |
| `S3_ENDPOINT` | `http://minio:9000` | Internal S3/MinIO endpoint |
| `PUBLIC_S3_URL` | `http://minio:9000` | Public endpoint for presigned URLs |
| `S3_BUCKET` | `construction-files` | Bucket name |
| `OCR_PROVIDER` | `NONE` | `NONE` (no cloud OCR cost) or `TEXTRACT` (billed AWS OCR) |

### Build targets
- Backend: Kotlin/JVM 22 (Ktor 2.3.12, Exposed 0.55.0, Gradle 8.11.1 wrapper).
- Android: compileSdk 36, minSdk 35, Jetpack Compose, Kotlin 2.x.

---

## 9. Known Gaps / Not Implemented

- No JWT/session token auth — login responses return user data directly; the app manages session state locally (`AuthManager`), no bearer-token authorization on API calls.
- No role-based server-side authorization checks on most routes (enforcement is client-side only, e.g. Admin-only revert/suspense UI gating).
- No pagination on list endpoints (`/payments/all`, `/collections`, `/suspense`, etc.).
- `OCR_PROVIDER=TEXTRACT` requires valid AWS credentials/permissions in the deployment environment; not verified end-to-end in this environment.
- No automated test suite currently present for backend routes (`testImplementation(kotlin("test"))` declared but no test sources found under `backend/src/test`).

