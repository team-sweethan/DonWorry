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

    def assertNotStuck(self):
        # 서비스를 바꾸지 않은 사전 조건 거절은 실행 기록·PAUSE_CD를 남기지 않는다.
        self.assertEqual(self.docker.mutations(), [])
        self.assertFalse((self.root / 'PAUSE_CD').exists())
        self.assertEqual(self.cd.unresolved(), [])

    def test_no_previous_record_blocks_deploy_before_compose(self):
        (self.cd.store / 'current.json').unlink()
        with self.assertRaisesRegex(release.Rejected, 'register-current'):
            self.candidate()
        self.assertNotStuck()

    def test_prerequisite_lost_after_approval_rejects_without_pause(self):
        bundle = self.candidate()
        (self.cd.store / 'current.json').unlink()
        with self.assertRaisesRegex(release.Rejected, 'register-current'):
            self.cd.apply(bundle, 'update-1')
        self.assertNotStuck()
        self.assertFalse((self.cd.store / bundle / 'result.json').exists())
        # 멈춤이 남지 않아 운영자가 바로 현재 상태를 다시 등록할 수 있다.
        self.cd.register_current(fixtures.SHA, 'refresh', 'operator', 'INC-2')

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

    def test_recovery_approval_without_backup_record_rejects_clearly(self):
        bundle = self.candidate()
        self.cd.apply(bundle, 'update-1')
        result_path = self.cd.store / bundle / 'result.json'
        result = json.loads(result_path.read_bytes())
        result['status'] = 'failed'
        result.pop('backup')
        result_path.write_bytes(release.encoded(result))
        with self.assertRaisesRegex(release.Rejected, '성공한 배포 전 백업 없음'):
            self.cd.approve_recovery(bundle, 'update-1', 'operator', 'INC-2')

    def test_missing_functional_policy_rejects_clearly_without_pause(self):
        (self.root / 'functional-check.json').unlink()
        self.cd.register_current(fixtures.SHA, 'refresh', 'operator', 'INC-2')
        with self.assertRaisesRegex(release.Rejected, '기능 검사 정책 없음'):
            self.candidate()
        self.assertNotStuck()

    def test_missing_functional_implementation_blocks_before_service_change(self):
        (self.root / 'functional_check.py').unlink()
        self.cd.register_current(fixtures.SHA, 'refresh', 'operator', 'INC-2')
        with self.assertRaisesRegex(release.Rejected, '기능 검사 구현 없음'):
            self.candidate()
        self.assertNotStuck()

    def strip_current_revision(self):
        # #480 이전 빌드처럼 현재 이미지에 revision label이 없는 상태를 만든다. marker는 그대로 둔다.
        for ref, config in self.docker.configs.items():
            if not ref.startswith(release.MARKER):
                config['config'].get('Labels', {}).pop(release.REVISION, None)
        for image in self.docker.local.values():
            image['Config'].get('Labels', {}).pop(release.REVISION, None)
        (self.cd.store / 'current.json').unlink()

    def test_unlabeled_current_requires_explicit_exception(self):
        self.strip_current_revision()
        with self.assertRaisesRegex(release.Rejected, 'revision'):
            self.cd.register_current(fixtures.SHA, 'legacy', 'operator', 'INC-480')
        self.assertFalse((self.root / 'PAUSE_CD').exists())

    def test_unlabeled_current_registers_with_exception_and_next_deploy_works(self):
        self.strip_current_revision()
        registered = self.cd.register_current(fixtures.SHA, 'legacy', 'operator', 'INC-480',
                                              without_revision=True)
        self.assertFalse(registered['revision_verified'])
        saved = json.loads((self.cd.store / registered['bundle_id'] / 'bundle.json').read_bytes())
        self.assertIs(saved['revision_verified'], False)
        # 예외 등록한 현재 묶음을 백업 기준으로 삼아 새 배포가 진행된다. 새 이미지는 label이 필요하다.
        bundle = self.candidate()
        result = self.cd.apply(bundle, 'update-1')
        self.assertEqual(result['backup']['previous_bundle'], registered['bundle_id'])
        self.assertEqual(result['status'], 'success')

    def test_exception_never_accepts_label_for_another_sha(self):
        for ref, config in self.docker.configs.items():
            if not ref.startswith(release.MARKER):
                config['config'].setdefault('Labels', {})[release.REVISION] = 'c' * 40
        (self.cd.store / 'current.json').unlink()
        with self.assertRaisesRegex(release.Rejected, 'revision'):
            self.cd.register_current(fixtures.SHA, 'legacy', 'operator', 'INC-480', without_revision=True)

    def test_recovery_to_unlabeled_registration_records_verification_level(self):
        self.strip_current_revision()
        self.cd.register_current(fixtures.SHA, 'legacy', 'operator', 'INC-480', without_revision=True)
        bundle = self.failed()
        approval = self.cd.approve_recovery(bundle, 'update-1', 'operator', 'INC-2')
        self.assertIs(approval['previous_revision_verified'], False)
        recovered = self.cd.recover(bundle, 'update-1')
        self.assertEqual(recovered['status'], 'success')

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
        # 승인되지 않은 환경 정책은 준비 단계에서 거절하고 멈춤을 남기지 않는다.
        with self.assertRaisesRegex(release.Rejected, '승인된 테스트 환경'):
            self.candidate()
        self.assertNotStuck()

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
