#!/bin/bash
# One-off setup script — creates an AWS Budget that emails you when spend crosses
# 80% and 100% of a monthly threshold, so free-tier/credit expiry never turns into
# a surprise bill.
#
# WHY THIS ISN'T RUN BY THE APP'S NORMAL IAM USER: `constructionapp` (used by
# infra/deploy.sh, infra/ocr-logs.sh, etc.) is deliberately scoped to S3 + a
# narrow slice of EC2 (see AGENT_NOTES.md) and has NO billing/budgets permission
# — that's correct least-privilege design, not a bug. Budgets/Cost Explorer are
# account-level billing concerns, so this script must be run with a DIFFERENT,
# more privileged credential (root, or an IAM user/role with the
# `budgets:ViewBudget`/`budgets:ModifyBudget` + `ce:GetCostAndUsage` actions —
# e.g. the AWS-managed `Billing` policy), NOT by adding billing permissions to
# the app's service account.
#
# Usage:
#   ./infra/setup-billing-budget.sh <your-email@example.com> [monthly-limit-usd]
#
# Example:
#   ./infra/setup-billing-budget.sh me@example.com 15
set -euo pipefail

EMAIL="${1:?Usage: $0 <email> [monthly-limit-usd]}"
LIMIT="${2:-15}"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
BUDGET_NAME="construction-monthly-spend"

log() { echo "[setup-billing-budget] $*" >&2; }

log "Account: $ACCOUNT_ID | Budget: \$$LIMIT/month | Alerts to: $EMAIL"

BUDGET_JSON=$(cat <<EOF
{
  "BudgetName": "$BUDGET_NAME",
  "BudgetLimit": { "Amount": "$LIMIT", "Unit": "USD" },
  "TimeUnit": "MONTHLY",
  "BudgetType": "COST"
}
EOF
)

NOTIFICATIONS_JSON=$(cat <<EOF
[
  {
    "Notification": {
      "NotificationType": "ACTUAL",
      "ComparisonOperator": "GREATER_THAN",
      "Threshold": 80,
      "ThresholdType": "PERCENTAGE"
    },
    "Subscribers": [ { "SubscriptionType": "EMAIL", "Address": "$EMAIL" } ]
  },
  {
    "Notification": {
      "NotificationType": "ACTUAL",
      "ComparisonOperator": "GREATER_THAN",
      "Threshold": 100,
      "ThresholdType": "PERCENTAGE"
    },
    "Subscribers": [ { "SubscriptionType": "EMAIL", "Address": "$EMAIL" } ]
  },
  {
    "Notification": {
      "NotificationType": "FORECASTED",
      "ComparisonOperator": "GREATER_THAN",
      "Threshold": 100,
      "ThresholdType": "PERCENTAGE"
    },
    "Subscribers": [ { "SubscriptionType": "EMAIL", "Address": "$EMAIL" } ]
  }
]
EOF
)

aws budgets create-budget \
  --account-id "$ACCOUNT_ID" \
  --budget "$BUDGET_JSON" \
  --notifications-with-subscribers "$NOTIFICATIONS_JSON"

log "Done. You'll get an email at $EMAIL at 80% actual, 100% actual, and 100% forecasted spend each month."
log "Check it anytime in the console: Billing and Cost Management -> Budgets -> $BUDGET_NAME"

