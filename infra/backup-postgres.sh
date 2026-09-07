#!/bin/bash
# Daily Postgres backup -> S3. Runs pg_dump inside the existing `postgres` container (no
# extra install needed on the host), compresses it, and uploads to the SAME S3 bucket the
# app already has IAM access to (under a `db-backups/` prefix) using a throwaway
# `amazon/aws-cli` container — so nothing needs to be installed on the host itself.
#
# Retention: an S3 Lifecycle rule (see infra/s3-lifecycle-policy.json, prefix
# "db-backups/") auto-expires objects older than 30 days, so this script doesn't need to
# do its own remote pruning. Local copies are deleted immediately after a successful
# upload (only ever a temp file on disk).
#
# Install (one-time): scp this file to the EC2 instance, then run:
#   sudo cp backup-postgres.sh /usr/local/bin/backup-postgres.sh
#   sudo chmod +x /usr/local/bin/backup-postgres.sh
#   ( crontab -l 2>/dev/null; echo "17 2 * * * /usr/local/bin/backup-postgres.sh >> /home/ubuntu/backup.log 2>&1" ) | crontab -
set -euo pipefail

ENV_FILE="/home/ubuntu/app/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "[backup] ERROR: $ENV_FILE not found" >&2
  exit 1
fi
# shellcheck disable=SC1090
source "$ENV_FILE"

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="/home/ubuntu/backups"
BACKUP_FILE="$BACKUP_DIR/construction-$TIMESTAMP.sql.gz"
mkdir -p "$BACKUP_DIR"

echo "[backup] Dumping database..."
docker exec postgres pg_dump -U "${DB_USER:-postgres}" -d construction | gzip -9 > "$BACKUP_FILE"

SIZE=$(stat -c%s "$BACKUP_FILE" 2>/dev/null || stat -f%z "$BACKUP_FILE")
if [[ "$SIZE" -lt 100 ]]; then
  echo "[backup] ERROR: dump file is suspiciously small ($SIZE bytes) — aborting upload" >&2
  exit 1
fi
echo "[backup] Dump complete: $BACKUP_FILE ($SIZE bytes)"

echo "[backup] Uploading to s3://${S3_BUCKET}/db-backups/construction-$TIMESTAMP.sql.gz ..."
docker run --rm \
  -e AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
  -e AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
  -e AWS_DEFAULT_REGION="${AWS_REGION:-ap-south-1}" \
  -v "$BACKUP_DIR:/aws-backups" \
  amazon/aws-cli:2.17.62 \
  s3 cp "/aws-backups/$(basename "$BACKUP_FILE")" "s3://${S3_BUCKET}/db-backups/construction-$TIMESTAMP.sql.gz"

echo "[backup] Upload OK — removing local copy"
rm -f "$BACKUP_FILE"
echo "[backup] Done."

