#!/usr/bin/env python3
"""Reset only the local boss scenario state; it never deletes the account or merchant."""
import argparse
import subprocess
import sys


def sql_for(login_id: str) -> str:
    escaped = login_id.replace("'", "''")
    return f"""
START TRANSACTION;
SET @user_id := (SELECT user_id FROM platform_users WHERE login_id = '{escaped}');
SET @was_inactive := (SELECT status = 'INACTIVE' FROM platform_users WHERE user_id = @user_id);
DELETE evidence FROM fds_operator_access_detection_evidences evidence
 JOIN fds_operator_access_detections detection ON detection.operator_access_detection_id = evidence.detection_id
 WHERE detection.user_id = @user_id;
DELETE FROM fds_operator_access_detections WHERE user_id = @user_id;
DELETE FROM fds_operator_access_events WHERE user_id = @user_id;
DELETE FROM operator_access_denials WHERE user_id = @user_id;
UPDATE platform_users SET status = 'ACTIVE' WHERE user_id = @user_id;
INSERT INTO admin_change_histories(actor_type, actor_user_id, target_user_id, target_merchant_id, change_type, before_data, after_data, created_at)
SELECT 'SYSTEM', NULL, @user_id, NULL, 'USER_ENABLED', '{{\"status\":\"INACTIVE\"}}', '{{\"status\":\"ACTIVE\"}}', UTC_TIMESTAMP(6)
WHERE @was_inactive = 1;
COMMIT;
"""


def main() -> int:
    parser = argparse.ArgumentParser(description='Reset local repeated-access scenario data.')
    parser.add_argument('--login-id', default='boss')
    parser.add_argument('--confirm', action='store_true', help='required before changing the local DB')
    args = parser.parse_args()
    if not args.confirm:
        parser.error('--confirm is required; this changes only the selected local scenario account state.')
    command = ['docker', 'compose', 'exec', '-T', 'mariadb', 'mariadb', '-udonworry', '-pdonworry', 'donworry']
    completed = subprocess.run(command, input=sql_for(args.login_id), text=True)
    if completed.returncode:
        return completed.returncode
    print(f"Reset completed for {args.login_id}: P2/P1 scenario rows cleared and account set ACTIVE.")
    print('Existing browser sessions are not restored; log in again before running the scenario.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
