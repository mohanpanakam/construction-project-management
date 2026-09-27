#!/bin/bash
# Fetches logs from the `ocr-service` container (RapidOCR sidecar — see
# docker-compose.prod.yml) on the prod EC2 instance. Handles the SSH-IP-allowlist
# dance itself (same as infra/deploy.sh), so you don't need to remember the
# authorize/revoke security-group steps from AGENT_NOTES.md just to check a log.
#
# Usage:
#   ./infra/ocr-logs.sh                 # last 200 lines
#   ./infra/ocr-logs.sh -n 500          # last 500 lines
#   ./infra/ocr-logs.sh -f              # follow (tail -f style), Ctrl+C to stop
#   ./infra/ocr-logs.sh --since 1h      # only logs from the last hour
#
# Any extra args are passed straight through to `docker logs` on the instance, e.g.:
#   ./infra/ocr-logs.sh -f --since 10m
set -euo pipefail

EC2_HOST="13.206.219.67"
EC2_USER="ubuntu"
SSH_KEY="$HOME/.ssh/construction-key.pem"
SG_ID="sg-0849d4adcbeadcf95"
AWS_REGION="ap-south-1"
CONTAINER="ocr-service"

log() { echo "[ocr-logs] $*" >&2; }

DOCKER_LOG_ARGS=("--tail" "200")
if [[ $# -gt 0 ]]; then
  DOCKER_LOG_ARGS=("$@")
fi

MY_IP="$(curl -4 -s ifconfig.me)"
log "Current public IP: $MY_IP"

ADDED_SG_RULE=false
if ! aws ec2 describe-security-groups --group-ids "$SG_ID" --region "$AWS_REGION" \
    --query "SecurityGroups[0].IpPermissions[?ToPort==\`22\`].IpRanges[?CidrIp=='${MY_IP}/32']" \
    --output text | grep -q "$MY_IP"; then
  log "Opening SSH (22) from $MY_IP/32 on $SG_ID..."
  aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 22 \
    --cidr "${MY_IP}/32" --region "$AWS_REGION" >/dev/null
  ADDED_SG_RULE=true
else
  log "SSH already allow-listed for $MY_IP."
fi

cleanup() {
  if [[ "$ADDED_SG_RULE" == true ]]; then
    log "Revoking the SSH rule this script added..."
    aws ec2 revoke-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 22 \
      --cidr "${MY_IP}/32" --region "$AWS_REGION" >/dev/null || true
  fi
}
trap cleanup EXIT

log "Fetching '$CONTAINER' logs from $EC2_HOST (docker logs ${DOCKER_LOG_ARGS[*]} $CONTAINER)..."
# shellcheck disable=SC2029
ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$EC2_USER@$EC2_HOST" \
  "docker logs ${DOCKER_LOG_ARGS[*]} $CONTAINER"

