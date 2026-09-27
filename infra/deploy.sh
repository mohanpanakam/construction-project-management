#!/bin/bash
# End-to-end local -> AWS EC2 redeploy for the backend, plus a local Android debug APK
# build (see AGENT_NOTES.md "Redeploy workflow" / "App distribution" for background).
#
# What this does, in order:
#   1. Compiles the backend (Gradle, via the Dockerfile's own `gradle buildFatJar`) by
#      building the `construction-backend:latest` Docker image for linux/arm64 (the
#      EC2 instance is a t4g.micro/arm64 — never build/pull an amd64 image for it).
#   2. Compiles the Android app (`./gradlew :app:assembleDebug`) and copies the output
#      APK to `jagadhabi-app-latest.apk` at the repo root (no release signing config
#      exists in this repo — see AGENT_NOTES.md — so this is always a debug build).
#   3. Temporarily opens SSH (port 22) on the prod security group from this machine's
#      current public IP (only if a rule for it doesn't already exist), transfers the
#      built image, loads + restarts the docker compose stack on the instance with
#      --no-build (the free-tier instance has too little RAM to compile Gradle itself).
#   4. Validates the deployment: `/health` must return HTTP 200 with `"status":"ok"`,
#      and `docker ps` on the instance must show `construction-backend` as `healthy`.
#   5. Revokes the SSH rule again ONLY if this script is the one that added it (never
#      revokes a rule that already pre-existed before this run), and cleans up the
#      local/remote image tarball either way.
#
# Usage:
#   ./infra/deploy.sh              # compile + build APK + deploy backend + validate
#   ./infra/deploy.sh --skip-app    # skip the Android APK build (backend-only redeploy)
#   ./infra/deploy.sh --skip-backend-deploy   # only compile/build, don't touch AWS at all
#
# Requires: docker (daemon running), aws CLI (configured with the `constructionapp`
# IAM user's credentials — see AGENT_NOTES.md), the SSH key at ~/.ssh/construction-key.pem,
# and the Android SDK for the Gradle app build.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

EC2_HOST="13.206.219.67"
EC2_USER="ubuntu"
SSH_KEY="$HOME/.ssh/construction-key.pem"
SG_ID="sg-0849d4adcbeadcf95"
AWS_REGION="ap-south-1"
HEALTH_URL="https://13.206.219.67.nip.io/health"

SKIP_APP=false
SKIP_DEPLOY=false
for arg in "$@"; do
  case "$arg" in
    --skip-app) SKIP_APP=true ;;
    --skip-backend-deploy) SKIP_DEPLOY=true ;;
    *) echo "[deploy] Unknown argument: $arg" >&2; exit 1 ;;
  esac
done

log() { echo "[deploy] $*"; }

# ── 1. Compile + build the backend Docker image ─────────────────────────────
log "Building backend Docker image (linux/arm64)..."
docker build --platform linux/arm64 -t construction-backend:latest -f Dockerfile .
log "Backend image built OK."

# ── 2. Compile the Android app + produce the distributable APK ──────────────
if [[ "$SKIP_APP" == false ]]; then
  log "Compiling Android app (assembleDebug)..."
  ./gradlew :app:assembleDebug --console=plain
  cp -v app/build/outputs/apk/debug/app-debug.apk jagadhabi-app-latest.apk
  log "APK built OK: jagadhabi-app-latest.apk ($(du -h jagadhabi-app-latest.apk | cut -f1))"
else
  log "Skipping Android app build (--skip-app)."
fi

if [[ "$SKIP_DEPLOY" == true ]]; then
  log "Skipping AWS deploy (--skip-backend-deploy). Done."
  exit 0
fi

# ── 3. Transfer + deploy to EC2 ──────────────────────────────────────────────
MY_IP="$(curl -4 -s ifconfig.me)"
log "Current public IP: $MY_IP"

ADDED_SG_RULE=false
if ! aws ec2 describe-security-groups --group-ids "$SG_ID" --region "$AWS_REGION" \
    --query "SecurityGroups[0].IpPermissions[?ToPort==\`22\`].IpRanges[?CidrIp=='${MY_IP}/32']" \
    --output text | grep -q "$MY_IP"; then
  log "Opening SSH (22) from $MY_IP/32 on $SG_ID..."
  aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 22 \
    --cidr "${MY_IP}/32" --region "$AWS_REGION"
  ADDED_SG_RULE=true
else
  log "SSH already allow-listed for $MY_IP — not adding/won't revoke a new rule."
fi

cleanup() {
  ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$EC2_USER@$EC2_HOST" \
    "rm -f /home/ubuntu/image.tar.gz" 2>/dev/null || true
  rm -f /tmp/construction-image.tar.gz
  if [[ "$ADDED_SG_RULE" == true ]]; then
    log "Revoking the SSH rule this script added..."
    aws ec2 revoke-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 22 \
      --cidr "${MY_IP}/32" --region "$AWS_REGION" || true
  fi
}
trap cleanup EXIT

log "Saving + compressing image for transfer..."
docker save construction-backend:latest | gzip > /tmp/construction-image.tar.gz
log "Image size: $(du -h /tmp/construction-image.tar.gz | cut -f1)"

log "Transferring image to $EC2_HOST..."
scp -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new /tmp/construction-image.tar.gz \
  "$EC2_USER@$EC2_HOST:/home/ubuntu/image.tar.gz"

log "Loading image + restarting stack on $EC2_HOST..."
ssh -i "$SSH_KEY" "$EC2_USER@$EC2_HOST" \
  "gunzip -c image.tar.gz | docker load && cd app && docker compose -f docker-compose.prod.yml --env-file .env up -d --no-build"

# ── 4. Validate ───────────────────────────────────────────────────────────
log "Waiting for backend to become healthy..."
HEALTHY=false
for i in $(seq 1 15); do
  sleep 4
  CODE="$(curl -sk -o /tmp/construction-health.json -w '%{http_code}' "$HEALTH_URL" || true)"
  if [[ "$CODE" == "200" ]] && grep -q '"status":"ok"' /tmp/construction-health.json 2>/dev/null; then
    HEALTHY=true
    break
  fi
  log "  ...attempt $i: HTTP $CODE, retrying"
done
rm -f /tmp/construction-health.json

if [[ "$HEALTHY" != true ]]; then
  echo "[deploy] ERROR: backend did not report healthy at $HEALTH_URL after deploy!" >&2
  ssh -i "$SSH_KEY" "$EC2_USER@$EC2_HOST" "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'" || true
  exit 1
fi

log "Health check OK: $HEALTH_URL"
ssh -i "$SSH_KEY" "$EC2_USER@$EC2_HOST" "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'"

log "Deploy complete."

