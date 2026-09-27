#!/bin/bash
# Opens an interactive SSH session to the prod EC2 instance, handling the
# SSH-IP-allowlist dance itself (same pattern as infra/deploy.sh / infra/ocr-logs.sh):
# temporarily authorizes port 22 from your current public IP on the prod security
# group (only if a rule for it doesn't already exist), then drops you into a normal
# interactive shell on the instance. When you `exit` that shell, this script
# automatically revokes the rule again — but ONLY if it was the one that added it
# (a pre-existing allow-list entry, e.g. your permanently-allow-listed office/home
# IP, is never touched).
#
# Usage:
#   ./infra/connect-aws.sh
#
# This is the "just SSH in and poke around" tool — for routine redeploys use
# infra/deploy.sh instead, and for OCR container logs use infra/ocr-logs.sh (both
# are one-shot/non-interactive and don't need this script's interactive shell).
set -euo pipefail

EC2_HOST="13.206.219.67"
EC2_USER="ubuntu"
SSH_KEY="$HOME/.ssh/construction-key.pem"
SG_ID="sg-0849d4adcbeadcf95"
AWS_REGION="ap-south-1"

log() { echo "[connect-aws] $*" >&2; }

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
    log "Session ended — revoking the SSH rule this script added..."
    aws ec2 revoke-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 22 \
      --cidr "${MY_IP}/32" --region "$AWS_REGION" >/dev/null || true
  fi
}
trap cleanup EXIT

log "Connecting to $EC2_USER@$EC2_HOST ... (type 'exit' to disconnect and auto-revoke SSH access)"
ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$EC2_USER@$EC2_HOST"

