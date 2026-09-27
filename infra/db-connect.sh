#!/bin/bash
# Opens an interactive psql session to the prod Postgres database (running in the
# `postgres` container on the EC2 instance — see docker-compose.prod.yml). Handles
# the SSH-IP-allowlist dance itself (same pattern as infra/connect-aws.sh /
# infra/deploy.sh / infra/ocr-logs.sh), so you don't need to remember the
# authorize/revoke security-group steps from AGENT_NOTES.md just to run a query.
#
# NOTE: Postgres intentionally does NOT publish port 5432 to the host (see
# docker-compose.prod.yml comment) — it's only reachable from the `backend`
# container on the same Docker network. So this script doesn't open an SSH tunnel
# for a local psql client; instead it SSHes in and `docker exec -it`s straight into
# the running `postgres` container, using the container's own POSTGRES_USER/
# POSTGRES_DB env vars (local/peer-trusted socket auth inside the container, no
# password prompt needed).
#
# Usage:
#   ./infra/db-connect.sh                 # interactive psql prompt
#   ./infra/db-connect.sh -c "SELECT ..." # run one query and exit (passed to psql)
#
# Any extra args are passed straight through to `psql` inside the container, e.g.:
#   ./infra/db-connect.sh -c "SELECT count(*) FROM \"Customers\";"
set -euo pipefail

EC2_HOST="13.206.219.67"
EC2_USER="ubuntu"
SSH_KEY="$HOME/.ssh/construction-key.pem"
SG_ID="sg-0849d4adcbeadcf95"
AWS_REGION="ap-south-1"
CONTAINER="postgres"

log() { echo "[db-connect] $*" >&2; }

# Build the remote psql command. -q suppresses the "psql (16.x)" banner; the
# container's own POSTGRES_USER/POSTGRES_DB env vars are read via $POSTGRES_USER/
# $POSTGRES_DB inside the exec'd shell so we don't need to know/parse the .env
# file on the instance from here.
PSQL_ARGS=()
if [[ $# -gt 0 ]]; then
  PSQL_ARGS=("$@")
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
    log "Session ended — revoking the SSH rule this script added..."
    aws ec2 revoke-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 22 \
      --cidr "${MY_IP}/32" --region "$AWS_REGION" >/dev/null || true
  fi
}
trap cleanup EXIT

log "Connecting to Postgres inside the '$CONTAINER' container on $EC2_HOST..."

# Build the psql invocation that runs INSIDE the container (via `sh -c`) so that
# $POSTGRES_USER / $POSTGRES_DB expand against the *container's* environment, not
# the ubuntu host shell's (which doesn't have those vars set). Extra args are
# appended and safely quoted with printf %q.
INNER_CMD='psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
for arg in "${PSQL_ARGS[@]:-}"; do
  [[ -z "$arg" ]] && continue
  printf -v quoted '%q' "$arg"
  INNER_CMD+=" $quoted"
done

printf -v INNER_CMD_Q '%q' "$INNER_CMD"
REMOTE_CMD="docker exec -it $CONTAINER sh -c $INNER_CMD_Q"

# shellcheck disable=SC2029
ssh -t -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$EC2_USER@$EC2_HOST" "$REMOTE_CMD"

