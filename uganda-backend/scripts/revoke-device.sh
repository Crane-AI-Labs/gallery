#!/bin/bash
# Revoke a device token.
#
# When to use: a health worker reports their phone lost/stolen, or an ops
# lead needs to force a specific device out of the system without waiting
# for the user to tap "Delete my data".
#
# Flow:
#   1. Find the device_id in audit_log or device_registry (see examples below).
#   2. Run this script with the device_id.
#   3. Confirm: the next sync from that device returns 401 "Token revoked",
#      and the Android app shows "Sync paused" on its Settings screen.
#
# The device's tier_1 data is NOT deleted by this operation — it ages out
# via the retention worker per the standard DPPA §18 schedule (90 days).
# If full erasure is required, follow with the purge block below.
#
# Usage:
#   ssh easehealth 'bash -s' < scripts/revoke-device.sh '<device_id>' '<reason>'
# Or copy to the VM and run in place.
#
# Finding a device_id:
#   sudo -u postgres psql -d easehealth -c \
#     "SELECT device_token, first_seen, last_seen, device_model, chipset, app_version
#      FROM tier_1_identified.device_registry
#      WHERE revoked_at IS NULL ORDER BY last_seen DESC LIMIT 20;"
#
set -euo pipefail

DEVICE_ID="${1:-}"
REASON="${2:-ops_revoke}"

if [[ -z "$DEVICE_ID" ]]; then
    cat >&2 <<EOF
Usage: $0 <device_id> [reason]

Examples:
  $0 3d7f8e21-1234-5678-90ab-cdef12345678 'stolen_phone_reported_by_nurse'
  $0 $(uuidgen | tr '[:upper:]' '[:lower:]') 'decommissioned'

To list active devices:
  sudo -u postgres psql -d easehealth -c \\
    "SELECT device_token, last_seen, device_model, app_version \\
     FROM tier_1_identified.device_registry \\
     WHERE revoked_at IS NULL ORDER BY last_seen DESC LIMIT 20;"
EOF
    exit 1
fi

# Validate UUID shape (prevents typos like "CM98ABCDE" from executing).
if ! [[ "$DEVICE_ID" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
    echo "ERROR: '$DEVICE_ID' is not a valid UUID" >&2
    exit 1
fi

echo "Revoking device_token=$DEVICE_ID reason='$REASON'..."

sudo -u postgres psql -d easehealth -v ON_ERROR_STOP=1 <<SQL
-- Revocation is idempotent — re-running leaves revoked_at at the first value.
UPDATE tier_1_identified.device_registry
SET revoked_at = COALESCE(revoked_at, now()),
    revoke_reason = COALESCE(revoke_reason, '$REASON')
WHERE device_token = '$DEVICE_ID';

-- Audit trail — regulators can see who did what and why.
INSERT INTO tier_1_identified.audit_log (actor, action, subject_id, reason, meta)
VALUES ('ops', 'admin_revoke',
        '$DEVICE_ID'::uuid,
        '$REASON',
        jsonb_build_object('invoked_from', 'revoke-device.sh'));

-- Show the current state so the operator can eyeball it.
SELECT device_token, revoked_at, revoke_reason, last_seen
FROM tier_1_identified.device_registry
WHERE device_token = '$DEVICE_ID';
SQL

cat <<EOF

Revoke complete. The device's next sync will return 401 and the app will
show "Sync paused" on the Settings screen.

To ALSO purge this device's tier_1 records now (DPPA §7 style), run:

  sudo -u postgres psql -d easehealth <<'PURGE'
  BEGIN;
  DELETE FROM tier_1_identified.assessments
      WHERE device_token = '$DEVICE_ID';
  DELETE FROM tier_1_identified.paused_consultations
      WHERE device_token = '$DEVICE_ID';
  INSERT INTO tier_1_identified.audit_log (actor, action, subject_id, reason)
      VALUES ('ops', 'admin_purge', '$DEVICE_ID'::uuid, 'purge_after_revoke');
  COMMIT;
  PURGE

Note: tier_2_analytics pseudonymised records are intentionally not purged —
they can't be linked back to this device or person.
EOF
