#!/bin/bash
# Single entry point for the routine ops tasks, exposing exactly these subcommands:
#
#   ./infra/ops.sh build         -> infra/deploy.sh          (compile + build APK + deploy + validate health)
#   ./infra/ops.sh ocr_logs      -> infra/ocr-logs.sh         (fetch ocr-service container logs)
#   ./infra/ops.sh connect_aws   -> infra/connect-aws.sh      (enable SSH + open an interactive session)
#
# WHY THIS EXISTS (read before asking the AI chat to "add a / command"): the chat's
# "/" menu is a fixed, client-side list of the assistant's own BUILT-IN commands —
# there is no supported way, from repo files, to add a *new* entry to that menu that
# invokes an arbitrary shell script. This dispatcher is the practical equivalent:
# one short, memorable, TAB-completable command you type directly in a terminal —
# zero LLM tokens spent, works identically whether or not the AI chat is even open.
#
# Any extra arguments are passed straight through to the underlying script, e.g.:
#   ./infra/ops.sh build --skip-app
#   ./infra/ops.sh ocr_logs -f --since 10m
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUBCOMMAND="${1:-}"
shift || true

usage() {
  cat >&2 <<'EOF'
Usage: infra/ops.sh <command> [args...]

Commands:
  build         Compile backend+app, build the APK, deploy the backend to AWS, validate health.
                (passes extra args to infra/deploy.sh, e.g. --skip-app / --skip-backend-deploy)
  ocr_logs      Fetch logs from the ocr-service container on the prod EC2 instance.
                (passes extra args to infra/ocr-logs.sh, e.g. -f / -n 500 / --since 1h)
  connect_aws   Enable SSH access (if needed) and open an interactive session to the prod instance.
  db            Enable SSH access (if needed) and open an interactive psql session to prod Postgres.
                (passes extra args to infra/db-connect.sh, e.g. -c "SELECT 1;")

Examples:
  ./infra/ops.sh build
  ./infra/ops.sh build --skip-app
  ./infra/ops.sh ocr_logs -f
  ./infra/ops.sh connect_aws
  ./infra/ops.sh db
  ./infra/ops.sh db -c "SELECT count(*) FROM \"Customers\";"
EOF
}

case "$SUBCOMMAND" in
  build)
    exec "$SCRIPT_DIR/deploy.sh" "$@"
    ;;
  ocr_logs)
    exec "$SCRIPT_DIR/ocr-logs.sh" "$@"
    ;;
  connect_aws)
    exec "$SCRIPT_DIR/connect-aws.sh" "$@"
    ;;
  db)
    exec "$SCRIPT_DIR/db-connect.sh" "$@"
    ;;
  ""|-h|--help|help)
    usage
    exit 0
    ;;
  *)
    echo "[ops] Unknown command: $SUBCOMMAND" >&2
    usage
    exit 1
    ;;
esac

