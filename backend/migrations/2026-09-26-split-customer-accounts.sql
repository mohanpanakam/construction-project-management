-- Split login/identity-recovery credentials off `customers` into a new
-- `customer_accounts` table, one row per PHONE NUMBER (person) instead of one
-- row per unit purchase. See Tables.kt (CustomerAccounts doc comment) and
-- AGENT_NOTES.md for the full rationale.
--
-- SAFE TO RUN MULTIPLE TIMES (idempotent): step 1 is CREATE TABLE IF NOT
-- EXISTS, step 2 uses ON CONFLICT DO NOTHING, step 3's DROP COLUMN IF EXISTS
-- is a no-op once already dropped.
--
-- Run this AFTER deploying the new backend code (so `customer_accounts` is
-- what the app actually reads/writes going forward) but the backfill (step 2)
-- can safely run before OR after — reads/writes to the old columns on
-- `customers` will simply stop happening once the new code is live, and this
-- script only ever READS from those old columns, never writes them.
--
-- Usage (from a machine with SSH access, or paste into `./infra/db-connect.sh`):
--   ./infra/db-connect.sh -f /dev/stdin < backend/migrations/2026-09-26-split-customer-accounts.sql
-- (psql -f isn't directly supported by db-connect.sh's remote docker exec, so in
-- practice this was applied by piping the statements through -c one at a time —
-- see AGENT_NOTES.md for exactly how it was run against prod on 2026-09-26.)
--
-- ⚠️ 2026-09-26 NOTE: on the actual prod redeploy, the backend's own
-- SchemaUtils.createMissingTablesAndColumns() startup call did NOT create this
-- table automatically as expected (confirmed via container restart logs
-- showing zero errors/warnings, yet `\dt` on prod Postgres showed the table
-- genuinely absent afterward — root cause not conclusively identified in the
-- time available; possibly an Exposed edge case with vararg table lists this
-- large, or a stale layer in the built jar despite matching image SHA — TODO
-- revisit if seen again on a future fresh deploy). Steps 1+2 of this script
-- were run MANUALLY via psql instead, and worked correctly + idempotently.
-- Don't assume "just deploy the new code" is sufficient by itself next time
-- this kind of new-table migration is needed — verify with `\dt` after deploy
-- and run this script by hand if the table didn't appear.

-- 1. Create the new table (matches Tables.kt's CustomerAccounts exactly; the
--    backend's own SchemaUtils.createMissingTablesAndColumns() at startup will
--    also create this automatically on next deploy, so this step is mostly
--    useful for applying the backfill BEFORE that deploy finishes, or for
--    running this script standalone against a fresh DB).
CREATE TABLE IF NOT EXISTS customer_accounts (
    phone               VARCHAR(50) PRIMARY KEY,
    login_email         VARCHAR(255) NOT NULL DEFAULT '',
    contact_email       VARCHAR(255) NOT NULL DEFAULT '',
    password_hash       VARCHAR(255) NOT NULL DEFAULT '',
    must_change_password BOOLEAN NOT NULL DEFAULT true,
    sec_question        VARCHAR(500) NOT NULL DEFAULT '',
    sec_answer_hash     VARCHAR(255) NOT NULL DEFAULT '',
    created_at          BIGINT NOT NULL DEFAULT 0
);

-- 2. Backfill one CustomerAccounts row per distinct non-blank phone number found
--    across all existing `customers` rows. Since some phones already had
--    DIVERGENT credentials across sibling rows (the very bug this migration
--    fixes — see AGENT_NOTES.md), we can't perfectly guess which row's password
--    is "the real one" the customer actually knows. Preference order per phone:
--      1. A row where must_change_password = false (implies the customer
--         successfully completed a real password change on THAT row).
--      2. Else, a row with a non-blank password_hash (has SOME password set).
--      3. Else, tie-break by most recently created row.
--    IMPORTANT: after running this, use `./infra/db-connect.sh` to spot-check
--    (or proactively reset, as was done for a few known-affected phones on
--    2026-09-26) any customer whose sibling rows had diverged, since this
--    migration can only pick ONE of possibly several different passwords —
--    the customer may need a fresh reset if we picked wrong.
INSERT INTO customer_accounts (phone, login_email, contact_email, password_hash, must_change_password, sec_question, sec_answer_hash, created_at)
SELECT DISTINCT ON (phone)
    phone, login_email, contact_email, password_hash, must_change_password, sec_question, sec_answer_hash, created_at
FROM customers
WHERE phone <> ''
ORDER BY phone,
    (must_change_password = false) DESC,  -- prefer a row that already completed first-login change
    (password_hash <> '') DESC,           -- else prefer any row with a password set at all
    created_at DESC                       -- else prefer the most recently created row
ON CONFLICT (phone) DO NOTHING;

-- 3. OPTIONAL cleanup — drop the now-unused legacy columns from `customers`.
--    Only run this AFTER confirming the new backend code is deployed and
--    working (step 2's backfill has been spot-checked). Commented out by
--    default so this script is safe to run standalone for just the backfill;
--    uncomment and re-run once you're confident, or run these lines manually.
-- ALTER TABLE customers DROP COLUMN IF EXISTS contact_email;
-- ALTER TABLE customers DROP COLUMN IF EXISTS login_email;
-- ALTER TABLE customers DROP COLUMN IF EXISTS password_hash;
-- ALTER TABLE customers DROP COLUMN IF EXISTS must_change_password;
-- ALTER TABLE customers DROP COLUMN IF EXISTS sec_question;
-- ALTER TABLE customers DROP COLUMN IF EXISTS sec_answer_hash;


