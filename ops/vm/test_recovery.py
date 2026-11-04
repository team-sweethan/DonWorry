"""복구 경계 통합 대역 검사. 실제 MariaDB/VM/업무 기능 검증을 대신하지 않는다."""

import json
import unittest
from unittest import mock

import test_release as fixtures

release = fixtures.release


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        fixtures.ReleaseTests.setUp(self)
        for patch in self.recovery_patches:
            patch.stop()
        release.write_new(self.root / 'functional-check.json', {
            'environment': 'approved-test', 'evidence': 'TEST-483', 'test_data': 'FIXTURE-1'})
        (self.root / 'functional_check.py').write_text('# approved test fixture\n')
        self.initial = self.cd.register_current(fixtures.SHA, 'initial', 'operator', 'INC-1')['bundle_id']

    def candidate(self):
        sha = 'b' * 40
        self.docker.make_release(revision=sha, image_revision=sha)
        self.docker.main = sha
        bundle = self.cd.prepare(sha, 'update-1')['bundle_id']
        self.cd.approve(bundle, 'update-1', 'jenkins-admin')
        return bundle

    def failed(self):
        bundle = self.candidate()
        self.docker.on_up = lambda: setattr(self.docker, 'runtime_health_bad', True)
        with self.assertRaises(release.Rejected):
            self.cd.apply(bundle, 'update-1')
        self.docker.on_up = lambda: None
        self.docker.runtime_health_bad = False
        return bundle

    def result(self, bundle):
        return json.loads((self.cd.store / bundle / 'result.json').read_bytes())

    def test_registration_pins_observed_digests_without_selecting_main_or_marker(self):
        bundle = json.loads((self.cd.store / self.initial / 'bundle.json').read_bytes())
        self.assertEqual(bundle['origin'], 'observed')
        self.assertNotIn('marker', bundle)
        self.assertEqual(self.docker.mutations(), [])
        self.assertEqual(self.result(self.initial)['operation'], 'registration')
        self.assertFalse(any(args[0] == 'git' for args in self.docker.calls))
        with self.assertRaises(release.Rejected):
            self.cd.register_current(fixtures.SHA, 'again', 'operator', 'INC-2')

    def test_deploy_records_previous_bundle_and_private_backup_before_compose(self):
        bundle = self.candidate()
        result = self.cd.apply(bundle, 'update-1')
        backup = result['backup']
        self.assertEqual(backup['previous_bundle'], self.initial)
        self.assertEqual(backup['status'], 'success')
        self.assertFalse(backup['restore_verified'])
        path = self.cd.store / bundle / backup['file']
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(release.digest(path.read_bytes()), backup['digest'])
        phases = [v['phase'] for v in result['history']]
        self.assertLess(phases.index('backed_up'), phases.index('applying'))
        self.assertEqual(result['functional_check']['status'], 'success')

    def test_no_previous_record_blocks_deploy_before_compose(self):
        (self.cd.store / 'current.json').unlink()
        bundle = self.candidate()
        with self.assertRaises(OSError):
            self.cd.apply(bundle, 'update-1')
        self.assertEqual(self.docker.mutations(), [])
        self.assertTrue((self.root / 'PAUSE_CD').exists())

    def test_dump_failure_leaves_durable_failure_and_never_replaces_services(self):
        bundle = self.candidate()
        self.docker.fail = lambda args: any('exec mariadb-dump ' in a for a in args)
        with self.assertRaises(release.Rejected):
            self.cd.apply(bundle, 'update-1')
        self.assertEqual(self.result(bundle)['backup']['status'], 'failed')
        self.assertEqual(self.result(bundle)['status'], 'failed')
        self.assertEqual(self.docker.mutations(), [])
        with self.assertRaises(release.Rejected):
            self.cd.approve_recovery(bundle, 'update-1', 'operator', 'INC-2')

    def test_failed_migration_or_unsupported_database_blocks_before_apply(self):
        for rows in [
            [['flyway', 1, '1', 'SQL', 'V1.sql', 123, 0], ['unsupported', 0, 0, 0]],
            [['flyway', 1, '1', 'SQL', 'V1.sql', 123, 1], ['unsupported', 1, 0, 0]],
        ]:
            self.docker.database_rows = rows
            with self.assertRaises(release.Rejected):
                self.cd.database_state()

    def test_explicit_image_recovery_preserves_failed_result_and_does_not_restore_db(self):
        bundle = self.failed()
        original = (self.cd.store / bundle / 'result.json').read_bytes()
        self.cd.approve_recovery(bundle, 'update-1', 'operator', 'INC-2')
        result = self.cd.recover(bundle, 'update-1')
        self.assertEqual(result['status'], 'success')
        self.assertFalse(result['database_restored'])
        self.assertEqual((self.cd.store / bundle / 'result.json').read_bytes(), original)
        self.assertFalse((self.root / 'PAUSE_CD').exists())
        self.assertEqual(self.cd.unresolved(), [])
        self.assertEqual(self.result(result['bundle_id'])['operation'], 'recovery')
        self.assertEqual(json.loads((self.cd.store / 'current.json').read_bytes())['bundle_id'],
                         result['bundle_id'])
        self.assertEqual(release.image_tag((self.root / '.env').read_bytes())[0], 'sha-' + fixtures.SHA)
        self.assertFalse(any('exec mariadb ' in ' '.join(a) and 'db-state' not in a
                             for a in self.docker.calls))
        mutations = len(self.docker.mutations())
        self.cd.recover(bundle, 'update-1')
        self.assertEqual(len(self.docker.mutations()), mutations)

    def test_successful_recovery_publication_can_resume_without_reapplying_services(self):
        bundle = self.failed()
        self.cd.approve_recovery(bundle, 'update-1', 'operator', 'INC-2')
        original = release.write_atomic
        def interrupted(path, value):
            if path == self.cd.store / 'current.json':
                raise OSError('publication interrupted')
            return original(path, value)
        with mock.patch.object(release, 'write_atomic', side_effect=interrupted):
            with self.assertRaises(OSError):
                self.cd.recover(bundle, 'update-1')
        self.assertTrue((self.root / 'PAUSE_CD').exists())
        mutations = len(self.docker.mutations())
        self.cd.recover(bundle, 'update-1')
        self.assertEqual(len(self.docker.mutations()), mutations)
        self.assertFalse((self.root / 'PAUSE_CD').exists())

    def test_database_change_after_approval_blocks_recovery_before_up(self):
        bundle = self.failed()
        self.cd.approve_recovery(bundle, 'update-1', 'operator', 'INC-2')
        calls = len(self.docker.mutations())
        self.docker.database_rows = [['flyway', 2, '2', 'SQL', 'V2.sql', 456, 1],
                                     ['column', 'new'], ['unsupported', 0, 0, 0]]
        with self.assertRaises(release.Rejected):
            self.cd.recover(bundle, 'update-1')
        self.assertEqual(len(self.docker.mutations()), calls)
        self.assertTrue((self.root / 'PAUSE_CD').exists())

    def test_partial_schema_change_with_unchanged_flyway_blocks_approval(self):
        bundle = self.failed()
        self.docker.database_rows = [['flyway', 1, '1', 'SQL', 'V1__test.sql', 123, 1],
                                     ['column', 'test', 'changed'], ['unsupported', 0, 0, 0]]
        with self.assertRaises(release.Rejected):
            self.cd.approve_recovery(bundle, 'update-1', 'operator', 'INC-2')

    def test_recovery_health_failure_keeps_pause_and_all_failure_records(self):
        bundle = self.failed()
        self.cd.approve_recovery(bundle, 'update-1', 'operator', 'INC-2')
        self.docker.on_up = lambda: setattr(self.docker, 'runtime_health_bad', True)
        with self.assertRaises(release.Rejected):
            self.cd.recover(bundle, 'update-1')
        self.assertTrue((self.root / 'PAUSE_CD').exists())
        self.assertEqual(self.result(bundle)['status'], 'failed')
        self.assertEqual(len(self.cd.unresolved()), 2)
        calls = len(self.docker.mutations())
        with self.assertRaises(release.Rejected):
            self.cd.recover(bundle, 'update-1')
        self.assertEqual(len(self.docker.mutations()), calls)

    def test_stale_recovery_cannot_replace_newer_success_pointer(self):
        bundle = self.failed()
        self.cd.approve_recovery(bundle, 'update-1', 'operator', 'INC-2')
        release.write_atomic(self.cd.store / 'current.json', {'bundle_id': 'c' * 64})
        calls = len(self.docker.mutations())
        with self.assertRaises(release.Rejected):
            self.cd.recover(bundle, 'update-1')
        self.assertEqual(len(self.docker.mutations()), calls)

    def test_configuration_change_cannot_be_hidden_by_recovery_approval(self):
        bundle = self.failed()
        self.cd.approve_recovery(bundle, 'update-1', 'operator', 'INC-2')
        with (self.root / '.env').open('ab') as stream:
            stream.write(b'NEW_SETTING=unapproved\n')
        calls = len(self.docker.mutations())
        with self.assertRaises(release.Rejected):
            self.cd.recover(bundle, 'update-1')
        self.assertEqual(len(self.docker.mutations()), calls)

    def test_backup_then_database_change_during_final_verification_blocks_compose(self):
        bundle = self.candidate()
        original = self.cd.verify
        def drift(value):
            original(value)
            if (self.cd.store / bundle / 'database.sql').exists():
                self.docker.database_rows = [['flyway', 1, '1', 'SQL', 'V1__test.sql', 123, 1],
                                             ['column', 'unexpected'], ['unsupported', 0, 0, 0]]
        self.cd.verify = drift
        with self.assertRaises(release.Rejected):
            self.cd.apply(bundle, 'update-1')
        self.assertEqual(self.docker.mutations(), [])
        self.assertEqual(self.result(bundle)['backup']['status'], 'success')

    def test_missing_functional_implementation_blocks_before_service_change(self):
        (self.root / 'functional_check.py').unlink()
        self.cd.register_current(fixtures.SHA, 'refresh', 'operator', 'INC-2')
        bundle = self.candidate()
        with self.assertRaises(release.Rejected):
            self.cd.apply(bundle, 'update-1')
        self.assertEqual(self.docker.mutations(), [])

    def test_backup_and_functional_timeouts_are_bounded(self):
        self.assertEqual(release.command_timeout(('docker', 'compose', 'exec', 'db',
                                                  'exec mariadb-dump --single-transaction')), 900)
        self.assertEqual(release.command_timeout(('docker', 'compose', 'exec', 'db', 'db-state')), 30)
        self.assertEqual(release.command_timeout(('python3', '/srv/donworry/functional_check.py')), 60)

    def test_functional_failure_is_recorded_and_requires_explicit_recovery(self):
        bundle = self.candidate()
        self.docker.fail = lambda args: args[0] == 'python3'
        with self.assertRaises(release.Rejected):
            self.cd.apply(bundle, 'update-1')
        self.assertEqual(self.result(bundle)['phase'], 'functional_check')
        self.assertEqual(len(self.docker.mutations()), 1)
        self.assertTrue((self.root / 'PAUSE_CD').exists())

    def test_functional_policy_requires_approved_environment(self):
        policy = self.root / 'functional-check.json'
        release.write_atomic(policy, {'environment': 'production', 'evidence': 'INC-2', 'test_data': 'X'})
        # 이미 등록한 구성의 변경은 새 현재 상태 확인 없이는 배포할 수 없다.
        bundle = self.candidate()
        with self.assertRaises(release.Rejected):
            self.cd.apply(bundle, 'update-1')
        self.assertEqual(self.docker.mutations(), [])

    def test_recovery_and_manual_commands_share_lock(self):
        bundle = self.failed()
        self.cd.approve_recovery(bundle, 'update-1', 'operator', 'INC-2')
        original = self.cd.local_images
        def checking(*args, **kwargs):
            other = release.Release(self.root, self.docker)
            with self.assertRaises(release.Rejected):
                with other.exclusive('manual-recovery', wait=0):
                    pass
            return original(*args, **kwargs)
        self.cd.local_images = checking
        self.cd.recover(bundle, 'update-1')


if __name__ == '__main__':
    unittest.main()
