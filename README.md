# Construction Project Management App

A full-stack Android application for managing construction projects, built with **Jetpack Compose**, **Ktor**, **PostgreSQL**, **Amazon S3/MinIO**, and **Docker**.

---

## Architecture

```
Android App (Jetpack Compose)
       │
       ▼
Ktor Backend (Kotlin) ──► PostgreSQL (via Exposed ORM)
       │
       └──► Amazon S3 / MinIO (file storage via presigned URLs)
```

---

## Features

### Android App
- 🔐 **Role-based authentication** — Admin, Project Manager, Site Worker
- 🤳 **Biometric login** (fingerprint / face unlock)
- 🔑 **Forgot password** with security question reset (3-step flow)
- 📋 **Project management** — create, view, edit, delete projects
- 🗺️ **Google Maps integration** — tap to open project location in Maps
- 🤝 **Partner/contractor details** — phone & email tap-to-call/mail
- 🖼️ **Project cover photos** — displayed on the project list cards
- ☁️ **S3 file management** — upload photos, documents, transactions per project
- 💰 **Financials & Inventory** tracking per project
- 🎨 **Blue gradient Material 3 theme**

### Backend (Ktor)
- REST API for Projects, Inventory, Financials
- S3 presigned URL generation for secure file uploads/downloads
- PostgreSQL as the data store
- `/health` endpoint for container health checks

### Infrastructure
- **Docker Compose** orchestrates: Ktor backend + PostgreSQL + MinIO (S3-compatible)
- Multi-stage Dockerfile (Gradle build → slim JRE runtime)

---

## Project Structure

```
Construction/
├── app/                        # Android application
│   └── src/main/java/com/panakam/construction/
│       ├── auth/               # AuthManager, UserRole, User
│       ├── data/               # LocalProjectStorage, S3FileManager
│       ├── database/           # DatabaseManager (HTTP → backend)
│       ├── model/              # Project, ProjectFile data classes
│       └── ui/
│           ├── navigation/     # AppNavigation, Routes
│           ├── screens/        # Login, Register, Home, ForgotPassword
│           │   └── projects/   # ProjectList, Detail, AddEdit, Files
│           └── theme/          # Blue gradient theme, colors, typography
├── backend/                    # Ktor server
│   └── src/main/kotlin/        # Routes, PostgreSQL/Exposed, S3 handlers
├── docker-compose.yml          # Full stack: backend + PostgreSQL + MinIO
└── Dockerfile                  # Multi-stage backend image
```

---

## Running Locally

### Prerequisites
- Docker Desktop
- Android Studio / Android SDK
- Java 17+

### Start the backend stack

```bash
docker compose up -d
```

Services:
| Service | URL |
|---|---|
| Ktor Backend | http://localhost:8080 |
| PostgreSQL | localhost:5432 |
| MinIO Console | http://localhost:9001 |

### Build & install the Android app

```bash
# Build APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Physical device**: Change `BASE_URL` in `DatabaseManager.kt` (and `AuthManager.kt`) to your Mac's LAN IP (e.g. `http://192.168.1.2:8080`). Check it with `ipconfig getifaddr en0` — it can change when you reconnect to Wi-Fi.
> **Emulator**: Use `http://10.0.2.2:8080`.

---

## Environment Variables (backend)

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | Server port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/construction` | PostgreSQL JDBC URL |
| `DB_USER` | `postgres` | PostgreSQL username |
| `DB_PASSWORD` | `postgres` | PostgreSQL password |
| `AWS_REGION` | `us-east-1` | AWS region |
| `AWS_ACCESS_KEY_ID` | `local` | AWS access key |
| `AWS_SECRET_ACCESS_KEY` | `local` | AWS secret key |
| `S3_ENDPOINT` | `http://minio:9000` | Internal S3/MinIO endpoint for backend |
| `PUBLIC_S3_URL` | `http://minio:9000` | Public endpoint used for presigned URLs |
| `S3_BUCKET` | `construction-files` | Bucket name for file storage |
| `OCR_PROVIDER` | `NONE` | Image OCR provider (`NONE` = local Tesseract, or `TEXTRACT`) |

Image receipts (screenshots) are OCR'd automatically using **Tesseract** (free, offline, bundled in the
backend Docker image — no cloud cost). Set `OCR_PROVIDER=TEXTRACT` only if you want AWS Textract's
higher-accuracy (but billed) OCR instead; requires valid AWS credentials.

---

## Production Deployment (AWS EC2)

Production uses **real AWS S3** (no MinIO) and runs Postgres + the backend in Docker on a
single EC2 instance via `docker-compose.prod.yml`.

### 1. Create the S3 bucket & IAM user
- Create an S3 bucket, e.g. `construction-files-yourname`, in your chosen region (e.g. `ap-south-1`).
- Create an IAM user with **programmatic access only** and attach a least-privilege policy
  scoped to that bucket — see [`infra/iam-s3-policy.json`](infra/iam-s3-policy.json). Update
  the bucket name in the policy's `Resource` ARNs to match your bucket.
- Save the access key ID / secret — you'll put them in `.env` below.

### 2. Launch the EC2 instance
- AMI: **Ubuntu 22.04/24.04, arm64** (e.g. `t4g.small`/`t4g.medium` — Graviton is cheaper).
- Security group: allow inbound **22** (SSH) and **8080** (backend API) from the internet/your IP.
- Paste [`infra/ec2-user-data-arm.sh`](infra/ec2-user-data-arm.sh) into the **User data** field.
  It installs Docker + the Compose plugin, adds a 2 GB swapfile, and writes
  `/home/ubuntu/user-data-done.txt` when finished — poll for that file (or check
  `cloud-init status`) before proceeding.

### 3. Deploy the stack
```bash
# On your machine — copy the repo to the instance (or git clone it there)
scp -r . ubuntu@<EC2_PUBLIC_IP>:~/construction

# SSH in
ssh ubuntu@<EC2_PUBLIC_IP>
cd ~/construction

# Create .env from the template and fill in real secrets
cp .env.example .env
nano .env

# Build & start (Postgres + backend). Postgres is NOT exposed on the host —
# only the backend container can reach it.
docker compose -f docker-compose.prod.yml --env-file .env up -d --build

# Check health
curl http://localhost:8080/health
```

### 4. Point the Android app at production
In `DatabaseManager.kt` / `AuthManager.kt`, set `BASE_URL` to `http://<EC2_PUBLIC_IP>:8080`
(or put a domain + reverse proxy / TLS in front of it for a real production setup).

### Updating a deployed instance
```bash
git pull   # or re-copy changed files
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```

---

## API Endpoints

```
GET    /health
GET    /projects
POST   /projects
GET    /projects/{id}
PUT    /projects/{id}
DELETE /projects/{id}
GET    /projects/{id}/files
POST   /projects/{id}/files/upload-url
GET    /projects/{id}/files/download-url
DELETE /projects/{id}/files/{fileId}
GET    /inventory/{projectId}
POST   /inventory
GET    /financials/{projectId}
POST   /financials
```

