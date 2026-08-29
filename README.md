# Construction Project Management App

A full-stack Android application for managing construction projects, built with **Jetpack Compose**, **Ktor**, **AWS DynamoDB**, **Amazon S3**, and **Docker**.

---

## Architecture

```
Android App (Jetpack Compose)
       │
       ▼
Ktor Backend (Kotlin) ──► DynamoDB Local / AWS DynamoDB
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
- DynamoDB as the data store
- `/health` endpoint for container health checks

### Infrastructure
- **Docker Compose** orchestrates: Ktor backend + DynamoDB Local + MinIO (S3-compatible)
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
│   └── src/main/kotlin/        # Routes, DynamoDB, S3 handlers
├── docker-compose.yml          # Full stack: backend + DynamoDB + MinIO
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
| DynamoDB Local | http://localhost:8000 |
| MinIO Console | http://localhost:9001 |

### Build & install the Android app

```bash
# Build APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Physical device**: Change `BASE_URL` in `DatabaseManager.kt` to your Mac's LAN IP (e.g. `http://192.168.1.21:8080`).  
> **Emulator**: Use `http://10.0.2.2:8080`.

---

## Environment Variables (backend)

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | Server port |
| `DYNAMO_ENDPOINT` | `http://dynamodb-local:8000` | DynamoDB endpoint |
| `AWS_REGION` | `us-east-1` | AWS region |
| `AWS_ACCESS_KEY_ID` | `local` | AWS access key |
| `AWS_SECRET_ACCESS_KEY` | `local` | AWS secret key |

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

