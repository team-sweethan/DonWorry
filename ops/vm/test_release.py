import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('vm_release', Path(__file__).with_name('release.py'))
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
SHA = 'a' * 40
EXECUTION = 'jenkins-23-test'
PLATFORM = {'os': 'linux', 'architecture': 'amd64'}


class Docker:
    """Docker/Git 명령 경계만 대체한다. 승인 파일과 구성 해시는 실제 임시 파일로 검사한다."""

    def __init__(self):
        self.calls = []
        self.raw = {}
        self.configs = {}
        self.local = {}
        self.main = SHA
        self.fail = None
        self.on_pull = lambda: None
        self.runtime_bad = False
        self.runtime_health_bad = False
        self.containerd = False
        self.compose_config = {'name': 'donworry', 'services': {
            'db': {'image': 'mariadb:11.8'},
            **{s: {'image': repo + ':sha-old', 'environment': {'PASSWORD': 'test$$SECRET'}}
               for s, repo in release.REPOS.items()}}}
        self.make_release()

    def manifest(self, repo, value):
        raw = release.encoded(value)
        d = release.digest(raw)
        self.raw[f'{repo}@{d}'] = raw
        return d

    def make_release(self, revision=SHA, schema='1', image_revision=SHA,
                     platform=PLATFORM, duplicate=False, salt=''):
        images = {}
        for service, repo in release.REPOS.items():
            config_id = release.digest((repo + image_revision + salt).encode())
            child = self.manifest(repo, {'schemaVersion': 2, 'mediaType': next(iter(release.MANIFESTS)),
                                        'config': {'digest': config_id}})
            config = {**platform, 'config': {'Labels': {release.REVISION: image_revision}}}
            self.configs[f'{repo}@{child}'] = config
            descriptor = {'mediaType': next(iter(release.MANIFESTS)), 'digest': child, 'platform': platform}
            manifests = [descriptor, {'digest': release.digest(b'attestation'),
                                     'platform': {'os': 'unknown', 'architecture': 'unknown'},
                                     'annotations': {'vnd.docker.reference.type': 'attestation-manifest'}}]
            if duplicate:
                manifests.append(copy.deepcopy(descriptor))
            index = self.manifest(repo, {'schemaVersion': 2, 'mediaType': next(iter(release.INDEXES)),
                                        'manifests': manifests})
            self.local[f'{repo}@{index}'] = {'Id': config_id, 'Os': platform['os'],
                                            'Architecture': platform['architecture'],
                                            'Config': {'Labels': {release.REVISION: image_revision}}}
            images[service] = index
        labels = {release.REVISION: revision, release.PREFIX + 'schema-version': schema}
        for s, n in [('backend', 'backend'), ('nginx', 'frontend'), ('ai', 'ai')]:
            labels[release.PREFIX + n + '.digest'] = images[s]
        self.prod = self.manifest(release.MARKER, {'schemaVersion': 2,
                                                 'mediaType': next(iter(release.MANIFESTS)),
                                                 'config': {'digest': release.digest(release.encoded(labels))}})
        self.configs[f'{release.MARKER}@{self.prod}'] = {'config': {'Labels': labels}}

    def __call__(self, *args):
        self.calls.append(args)
        if self.fail and self.fail(args):
            raise release.Rejected('원격 호출 실패')
        if args[0] == 'git':
            return f'{self.main}\trefs/heads/main\n'.encode()
        if args[:2] == ('docker', 'info'):
            # 기존 저장소 값은 VM1의 실제 `docker info` 출력(2026-11-03)이다.
            status = ([['driver-type', 'io.containerd.snapshotter.v1']] if self.containerd
                      else [['Backing Filesystem', 'extfs'], ['Supports d_type', 'true'],
                            ['Using metacopy', 'false'], ['Native Overlay Diff', 'true'],
                            ['userxattr', 'false']])
            return release.encoded({'OSType': 'linux', 'Architecture': 'x86_64',
                                    'DriverStatus': status})
        if args[:4] == ('docker', 'buildx', 'imagetools', 'inspect'):
            ref = args[-1]
            if ref.endswith(':prod'):
                return release.encoded({'digest': self.prod})
            if '--raw' in args:
                return self.raw[ref]
            return release.encoded(self.configs[ref])
        if args[:2] == ('docker', 'pull'):
            self.on_pull()
            return b''
        if args[:3] == ('docker', 'image', 'inspect'):
            return release.encoded([self.local[args[-1]]])
        if args[:2] == ('docker', 'inspect'):
            cid = args[-1]
            if cid == 'db-container':
                return release.encoded([{'State': {'Health': {'Status': 'healthy'}}}])
            service = cid.removesuffix('-container')
            selected = self.saved_compose['services'][service]['image']
            return release.encoded([{'Image': 'wrong' if self.runtime_bad else self.local[selected]['Id'],
                                     'State': {'Running': True}, 'Config': {'Image': selected}}])
        if args[:2] == ('docker', 'compose'):
            file = Path(args[args.index('-f') + 1])
            command = args[args.index('-f') + 2:]
            if command == ('config', '--format', 'json'):
                return release.encoded(self.compose_config)
            if command == ('config', '--quiet'):
                self.saved_compose = json.loads(file.read_bytes())
                return b''
            if command[:3] == ('ps', '-a', '-q'):
                return (command[3] + '-container').encode()
            if command[:2] == ('ps', '-q'):
                return (command[2] + '-container').encode()
            if command[:1] == ('up',):
                return b''
            if command[:4] == ('exec', '-T', 'nginx', 'sh'):
                return b'HTTP/1.1 500 Error' if self.runtime_health_bad else b'HTTP/1.1 401 Unauthorized\n'
            if command[:3] == ('exec', '-T', 'ai'):
                return b''
        if args[0] == 'curl':
            return b'ok'
        raise AssertionError(args)

    def use_containerd(self):
        # containerd 이미지 저장소에서는 이미지 ID가 pull한 index digest다.
        self.containerd = True
        for ref, image in self.local.items():
            image['Id'] = ref.split('@', 1)[1]

    def mutations(self):
        return [args for args in self.calls if args[:2] == ('docker', 'compose') and 'up' in args]


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / '.env').write_text('IMAGE_TAG=sha-' + SHA + '\nPASSWORD=test$SECRET\n')
        (self.root / 'compose.yaml').write_text('reference compose input')
        (self.root / 'backups').mkdir()
        (self.root / 'backups/deployed-image-tag').write_text('sha-' + SHA)
        (self.root / 'ENABLE_UPDATE_CD').touch()
        self.docker = Docker()
        self.sleeps = []
        self.cd = release.Release(self.root, self.docker, self.sleeps.append)

    def prepare(self):
        return self.cd.prepare(SHA, EXECUTION)['bundle_id']

    def approved(self):
        bundle = self.prepare()
        self.cd.approve(bundle, EXECUTION, 'jenkins-admin')
        return bundle

    def blocked(self, bundle):
        with self.assertRaises((release.Rejected, OSError, KeyError)):
            self.cd.apply(bundle, EXECUTION)
        self.assertEqual(self.docker.mutations(), [])

    def test_normal_pins_only_approved_indexes_and_checks_running_config_ids(self):
        bundle = self.approved()
        result = self.cd.apply(bundle, EXECUTION)
        self.assertEqual(result['status'], 'success')
        self.assertEqual(result['approval']['approver'], 'jenkins-admin')
        config = self.docker.saved_compose
        saved = json.loads((self.cd.store / bundle / 'bundle.json').read_bytes())
        for service, image in saved['images'].items():
            self.assertEqual(config['services'][service]['image'], image['repository'] + '@' + image['index'])
            self.assertEqual(config['services'][service]['platform'], 'linux/amd64')
            self.assertEqual(config['services'][service]['environment']['PASSWORD'], 'test$$SECRET')
        change = self.docker.mutations()[0]
        before_change = self.docker.calls[self.docker.calls.index(change) - 1]
        self.assertEqual(before_change[0], 'git')
        self.assertIn('--no-deps', change)
        self.assertIn('--no-build', change)
        self.assertIn('never', change)
        self.assertEqual(change[-3:], ('backend', 'ai', 'nginx'))
        self.assertEqual((self.root / '.env').read_text().splitlines()[0], 'IMAGE_TAG=sha-' + SHA)
        self.assertEqual(json.loads((self.cd.store / 'current.json').read_bytes())['bundle_id'], bundle)

    def test_stale_marker_is_rejected_before_approval(self):
        self.docker.make_release(revision='b' * 40)
        with self.assertRaisesRegex(release.Rejected, 'stale marker'):
            self.prepare()
        self.assertEqual(self.docker.mutations(), [])

    def test_final_main_change_during_image_download_blocks_apply(self):
        bundle = self.approved()
        self.docker.on_pull = lambda: setattr(self.docker, 'main', 'b' * 40)
        self.blocked(bundle)

    def test_final_main_query_failure_blocks_apply(self):
        bundle = self.approved()
        self.docker.fail = lambda args: args[0] == 'git'
        self.blocked(bundle)

    def test_marker_and_image_tags_are_never_reselected_after_approval(self):
        bundle = self.approved()
        self.docker.make_release(revision='b' * 40, image_revision='b' * 40)
        self.docker.calls.clear()
        self.cd.apply(bundle, EXECUTION)
        self.assertFalse(any(':prod' in arg or ':sha-' in arg for args in self.docker.calls for arg in args))

    def test_same_sha_rebuild_does_not_replace_approved_bundle(self):
        bundle = self.approved()
        original = json.loads((self.cd.store / bundle / 'bundle.json').read_bytes())
        self.docker.make_release(salt='rebuilt-from-same-sha')
        self.assertNotEqual(self.docker.prod, original['marker'])
        self.cd.apply(bundle, EXECUTION)
        for service, image in original['images'].items():
            self.assertEqual(self.docker.saved_compose['services'][service]['image'],
                             image['repository'] + '@' + image['index'])

    def test_approval_cannot_be_reused_for_another_execution(self):
        bundle = self.approved()
        with self.assertRaisesRegex(release.Rejected, '실행 ID'):
            self.cd.apply(bundle, 'other-24')
        self.assertEqual(self.docker.mutations(), [])

    def test_new_candidate_requires_new_approval(self):
        self.approved()
        second = self.prepare()
        self.blocked(second)

    def test_bundle_tampering_invalidates_approval(self):
        bundle = self.approved()
        path = self.cd.store / bundle / 'bundle.json'
        data = json.loads(path.read_bytes())
        data['sha'] = 'b' * 40
        path.write_bytes(release.encoded(data))
        self.blocked(bundle)

    def test_saved_compose_tampering_blocks_apply(self):
        bundle = self.approved()
        (self.cd.store / bundle / 'compose.json').write_text('{}')
        self.blocked(bundle)

    def test_source_compose_and_env_and_bind_changes_require_new_approval(self):
        for name in ['compose.yaml', '.env', 'realip.conf']:
            with self.subTest(name=name):
                bind = self.root / 'realip.conf'
                bind.write_text('initial')
                self.docker.compose_config['services']['nginx']['volumes'] = [
                    {'type': 'bind', 'source': str(bind), 'target': '/etc/realip.conf', 'read_only': True}]
                bundle = self.approved()
                path = self.root / name
                old = path.read_bytes()
                path.write_text('modified')
                self.blocked(bundle)
                path.write_bytes(old)

    def test_environment_resolution_change_blocks_apply(self):
        bundle = self.approved()
        self.docker.compose_config['services']['backend']['environment']['PASSWORD'] = 'changed'
        self.blocked(bundle)

    def test_config_change_during_download_blocks_apply(self):
        bundle = self.approved()
        self.docker.on_pull = lambda: (self.root / '.env').write_text('changed')
        self.blocked(bundle)

    def test_missing_image_and_remote_failure_block_apply(self):
        for selected in ['manifest', 'pull', 'image']:
            with self.subTest(selected=selected):
                self.setUp()
                bundle = self.approved()
                self.docker.fail = lambda args: (
                    (selected == 'manifest' and args[:2] == ('docker', 'buildx')) or
                    (selected == 'pull' and args[:2] == ('docker', 'pull')) or
                    (selected == 'image' and args[:2] == ('docker', 'image')))
                self.blocked(bundle)
                self.docker.fail = None

    def test_manifest_content_digest_mismatch_blocks_apply(self):
        bundle = self.approved()
        ref = next(ref for ref in self.docker.raw if 'backend' in ref)
        self.docker.raw[ref] += b'\n'
        self.blocked(bundle)

    def test_marker_schema_and_image_revision_and_platform_rejected(self):
        for options in [{'schema': None}, {'schema': '2'}, {'image_revision': 'b' * 40},
                        {'platform': {'os': 'linux', 'architecture': 'arm64'}}, {'duplicate': True}]:
            with self.subTest(options=options):
                self.docker.make_release(**options)
                with self.assertRaises(release.Rejected):
                    self.prepare()
                self.assertEqual(self.docker.mutations(), [])

    def test_local_config_id_mismatch_blocks_apply(self):
        bundle = self.approved()
        for image in self.docker.local.values():
            image['Id'] = release.digest(b'wrong image')
        self.blocked(bundle)

    def test_containerd_store_checks_index_digest_as_image_id(self):
        self.docker.use_containerd()
        bundle = self.approved()
        self.assertEqual(self.cd.apply(bundle, EXECUTION)['status'], 'success')
        saved = json.loads((self.cd.store / bundle / 'bundle.json').read_bytes())
        for image in saved['images'].values():
            self.assertEqual(self.docker.local[image['repository'] + '@' + image['index']]['Id'],
                             image['index'])

    def test_image_id_kind_must_match_docker_image_store(self):
        # 저장 방식과 다른 종류의 ID가 나오면 서비스 변경 전에 거절한다.
        for containerd, use_index in [(True, False), (False, True)]:
            with self.subTest(containerd=containerd):
                self.setUp()
                bundle = self.approved()
                saved = copy.deepcopy(self.docker.local)
                self.docker.containerd = containerd
                if use_index:
                    for ref, image in self.docker.local.items():
                        image['Id'] = ref.split('@', 1)[1]
                self.blocked(bundle)
                self.docker.local = saved
                self.docker.containerd = False

    def test_local_revision_or_platform_mismatch_blocks_apply(self):
        for key, value in [('Architecture', 'arm64'), ('Os', 'windows'),
                           ('Config', {'Labels': {release.REVISION: 'b' * 40}})]:
            with self.subTest(key=key):
                self.setUp()
                bundle = self.approved()
                saved = copy.deepcopy(self.docker.local)
                for image in self.docker.local.values():
                    image[key] = value
                self.blocked(bundle)
                self.docker.local = saved

    def test_legacy_deployment_record_mismatch_blocks_prepare(self):
        (self.root / '.env').write_text('IMAGE_TAG=sha-' + 'b' * 40)
        with self.assertRaisesRegex(release.Rejected, '기록 불일치'):
            self.prepare()
        self.assertEqual(self.docker.mutations(), [])

    def test_compose_up_failure_records_failure_without_success(self):
        bundle = self.approved()
        self.docker.fail = lambda args: args[:2] == ('docker', 'compose') and 'up' in args
        with self.assertRaises(release.Rejected):
            self.cd.apply(bundle, EXECUTION)
        self.assertEqual(json.loads((self.cd.store / bundle / 'result.json').read_bytes())['status'], 'failed')
        self.assertTrue((self.root / 'PAUSE_CD').exists())
        self.assertFalse((self.cd.store / 'current.json').exists())

    def test_app_warmup_is_retried_before_success(self):
        bundle = self.approved()
        self.docker.runtime_health_bad = True
        def warmup(seconds):
            self.sleeps.append(seconds)
            self.docker.runtime_health_bad = False
        self.cd.sleep = warmup
        result = self.cd.apply(bundle, EXECUTION)
        self.assertEqual(result['status'], 'success')
        self.assertEqual(self.sleeps, [4])

    def test_pause_or_activation_guard_blocks_apply(self):
        bundle = self.approved()
        (self.root / 'ENABLE_UPDATE_CD').unlink()
        self.blocked(bundle)
        (self.root / 'ENABLE_UPDATE_CD').touch()
        (self.root / 'PAUSE_CD').touch()
        self.blocked(bundle)

    def test_runtime_image_or_health_failure_records_failure_and_pauses(self):
        for health in [False, True]:
            with self.subTest(health=health):
                bundle = self.approved()
                self.docker.runtime_bad = not health
                self.docker.runtime_health_bad = health
                with self.assertRaises(release.Rejected):
                    self.cd.apply(bundle, EXECUTION)
                result = json.loads((self.cd.store / bundle / 'result.json').read_bytes())
                self.assertEqual(result['status'], 'failed')
                self.assertTrue((self.root / 'PAUSE_CD').exists())
                self.assertFalse((self.cd.store / 'current.json').exists())
                # 독립된 다음 실패 시나리오를 위해 운영자 조치를 대체한다.
                (self.root / 'PAUSE_CD').unlink()
                (self.cd.store / bundle / 'result.json').unlink()

    def test_interrupted_update_blocks_another_candidate(self):
        bundle = self.approved()
        release.write_new(self.cd.store / bundle / 'result.json', {'status': 'applying'})
        with self.assertRaisesRegex(release.Rejected, '미완료'):
            self.prepare()
        self.assertEqual(self.docker.mutations(), [])

    def test_successful_approval_is_single_use(self):
        bundle = self.approved()
        self.cd.apply(bundle, EXECUTION)
        self.docker.calls.clear()
        self.blocked(bundle)

    def test_pull_failure_is_durably_recorded_before_service_change(self):
        bundle = self.approved()
        self.docker.fail = lambda args: args[:2] == ('docker', 'pull')
        self.blocked(bundle)
        result = json.loads((self.cd.store / bundle / 'result.json').read_bytes())
        self.assertEqual(result['phase'], 'pulling')
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(result['execution'], EXECUTION)
        self.assertIn('boot_id', result)

    def test_apply_holds_common_lock_even_when_called_without_cli(self):
        bundle = self.approved()
        def contender():
            with self.assertRaisesRegex(release.Rejected, '잠금'):
                with self.cd.exclusive('recovery', wait=0):
                    self.fail('복구가 배포 잠금을 통과함')
        self.docker.on_pull = contender
        self.assertEqual(self.cd.apply(bundle, EXECUTION)['status'], 'success')

    def test_caught_interruption_blocks_fresh_release_instance(self):
        bundle = self.approved()
        def killed():
            result = json.loads((self.cd.store / bundle / 'result.json').read_bytes())
            self.assertEqual(result['phase'], 'pulling')
            raise SystemExit('강제 중단 대역')
        self.docker.on_pull = killed
        with self.assertRaises(SystemExit):
            self.cd.apply(bundle, EXECUTION)
        self.docker.on_pull = lambda: None
        with self.assertRaises(release.Rejected):
            release.Release(self.root, self.docker).prepare(SHA, 'after-reboot')
        self.assertEqual(self.docker.mutations(), [])

    def test_partial_runtime_cannot_be_reconciled(self):
        bundle = self.approved()
        self.docker.runtime_bad = True
        with self.assertRaises(release.Rejected):
            self.cd.apply(bundle, EXECUTION)
        with self.assertRaises(release.Rejected):
            self.cd.reconcile(bundle, EXECUTION, 'operator', 'target', 'INC-482')
        self.assertFalse((self.cd.store / bundle / 'resolution.json').exists())
        self.assertTrue((self.root / 'PAUSE_CD').exists())

    def test_reconciliation_checks_runtime_and_preserves_failure_history(self):
        bundle = self.approved()
        self.docker.runtime_bad = True
        with self.assertRaises(release.Rejected):
            self.cd.apply(bundle, EXECUTION)
        self.docker.runtime_bad = False
        result = self.cd.reconcile(bundle, EXECUTION, 'operator', 'target', 'INC-482')
        self.assertEqual(result['observed_bundle'], bundle)
        self.assertEqual(result['operator'], 'operator')
        self.assertEqual(json.loads((self.cd.store / bundle / 'result.json').read_bytes())['status'], 'failed')
        self.assertFalse((self.root / 'PAUSE_CD').exists())
        self.cd.preflight()
        self.docker.calls.clear()
        self.blocked(bundle)

    def test_old_waiter_rechecks_main_after_newer_deployment_releases_lock(self):
        bundle = self.approved()
        waiting = threading.Event()
        finished = threading.Event()
        errors = []
        actual_flock = release.fcntl.flock
        def observed_flock(fd, flags):
            try:
                return actual_flock(fd, flags)
            except BlockingIOError:
                waiting.set()
                raise
        def apply():
            try:
                self.cd.apply(bundle, EXECUTION)
            except BaseException as error:
                errors.append(error)
            finally:
                finished.set()
        with mock.patch.object(release.fcntl, 'flock', observed_flock):
            with release.Release(self.root, self.docker).exclusive('newer-deploy'):
                worker = threading.Thread(target=apply)
                worker.start()
                self.assertTrue(waiting.wait(3), '오래된 실행이 공통 잠금을 기다려야 함')
                self.assertFalse(finished.is_set())
                self.docker.main = 'b' * 40
                release.write_atomic(self.cd.store / 'current.json', {'bundle_id': 'newer-success'})
            worker.join(5)
        self.assertFalse(worker.is_alive())
        self.assertEqual(len(errors), 1)
        self.assertIsInstance(errors[0], release.Rejected)
        self.assertIn('main HEAD', str(errors[0]))
        self.assertEqual(self.docker.mutations(), [])
        self.assertEqual(json.loads((self.cd.store / 'current.json').read_bytes())['bundle_id'],
                         'newer-success')

    def test_sigkill_leaves_durable_phase_and_blocks_next_process(self):
        bundle = self.approved()
        script = '''import os, signal, sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
from release import Release
cd = Release(Path(sys.argv[2]))
with cd.exclusive('deploy'):
    folder = cd.store / sys.argv[3]
    with cd.operation(folder, sys.argv[3], 'crashed', {'approver': 'operator'}) as (_, stage):
        stage('applying')
        os.kill(os.getpid(), signal.SIGKILL)
'''
        process = subprocess.run([sys.executable, '-c', script, str(Path(__file__).parent),
                                  str(self.root), bundle], capture_output=True, timeout=5)
        self.assertEqual(process.returncode, -9)
        result = json.loads((self.cd.store / bundle / 'result.json').read_bytes())
        self.assertEqual((result['status'], result['phase']), ('running', 'applying'))
        self.assertFalse((self.root / 'PAUSE_CD').exists())
        with self.assertRaisesRegex(release.Rejected, '미완료'):
            release.Release(self.root, self.docker).prepare(SHA, 'next-process')

    def test_inspection_reports_partial_images_without_environment_values(self):
        bundle = self.approved()
        self.docker.runtime_bad = True
        with mock.patch.object(release, 'boot_id', return_value='boot-before'):
            with self.assertRaises(release.Rejected):
                self.cd.apply(bundle, EXECUTION)
        with mock.patch.object(release, 'boot_id', return_value='boot-after'):
            report = self.cd.inspect(bundle, EXECUTION)
        self.assertTrue(report['boot_changed'])
        self.assertEqual(report['observed_boot_id'], 'boot-after')
        self.assertEqual(report['services']['backend']['image_id'], 'wrong')
        self.assertIn(bundle, report['unresolved'])
        self.assertNotIn('SECRET', json.dumps(report))
        self.assertEqual(report['result']['phase'], 'checking')

    def test_reconciliation_is_repeatable_after_pause_cleanup_interruption(self):
        bundle = self.approved()
        self.docker.runtime_bad = True
        with self.assertRaises(release.Rejected):
            self.cd.apply(bundle, EXECUTION)
        self.docker.runtime_bad = False
        self.cd.reconcile(bundle, EXECUTION, 'operator', 'target', 'INC-482')
        (self.root / 'PAUSE_CD').touch()
        self.cd.reconcile(bundle, EXECUTION, 'operator', 'target', 'INC-482')
        self.assertFalse((self.root / 'PAUSE_CD').exists())

    def test_reconciliation_can_confirm_previous_success_without_redeploying(self):
        previous = self.approved()
        self.cd.apply(previous, EXECUTION)
        running = copy.deepcopy(self.docker.saved_compose)
        self.docker.make_release(salt='new-build')
        target = self.approved()
        self.docker.fail = lambda args: args[:2] == ('docker', 'pull')
        with self.assertRaises(release.Rejected):
            self.cd.apply(target, EXECUTION)
        self.docker.fail = None
        self.docker.saved_compose = running
        self.docker.calls.clear()
        record = self.cd.reconcile(target, EXECUTION, 'operator', 'current', 'INC-482')
        self.assertEqual(record['observed_bundle'], previous)
        self.assertEqual(self.docker.mutations(), [])
        self.assertEqual(json.loads((self.cd.store / 'current.json').read_bytes())['bundle_id'], previous)
        self.cd.preflight()

    def test_reconciliation_without_current_or_with_changed_config_stays_blocked(self):
        bundle = self.approved()
        self.docker.runtime_bad = True
        with self.assertRaises(release.Rejected):
            self.cd.apply(bundle, EXECUTION)
        self.docker.runtime_bad = False
        with self.assertRaises(FileNotFoundError):
            self.cd.reconcile(bundle, EXECUTION, 'operator', 'current', 'INC-482')
        (self.root / '.env').write_text('modified')
        with self.assertRaisesRegex(release.Rejected, '구성 변경'):
            self.cd.reconcile(bundle, EXECUTION, 'operator', 'target', 'INC-482')
        self.assertFalse((self.cd.store / bundle / 'resolution.json').exists())
        self.assertTrue((self.root / 'PAUSE_CD').exists())

    def test_recovery_operation_uses_same_durable_failure_record(self):
        bundle = self.approved()
        folder = self.cd.store / bundle
        with self.assertRaisesRegex(release.Rejected, '복구 대역 중단'):
            with self.cd.exclusive('recovery'):
                with self.cd.operation(folder, bundle, EXECUTION, {'approver': 'operator'},
                                       kind='recovery') as (_, stage):
                    stage('restoring')
                    with self.assertRaisesRegex(release.Rejected, '잠금'):
                        with release.Release(self.root).exclusive('manual-deploy', wait=0):
                            self.fail('복구와 수동 배포가 동시에 진입함')
                    raise release.Rejected('복구 대역 중단')
        result = json.loads((folder / 'result.json').read_bytes())
        self.assertEqual((result['operation'], result['phase'], result['status']),
                         ('recovery', 'restoring', 'failed'))

    def test_corrupt_record_blocks_even_without_pause_file(self):
        bundle = self.approved()
        (self.cd.store / bundle / 'result.json').write_text('{')
        with self.assertRaises(json.JSONDecodeError):
            self.prepare()
        self.assertEqual(self.docker.mutations(), [])

    def test_reconciliation_crash_between_resolution_and_current_keeps_pause(self):
        bundle = self.approved()
        self.docker.runtime_bad = True
        with self.assertRaises(release.Rejected):
            self.cd.apply(bundle, EXECUTION)
        self.docker.runtime_bad = False
        # SIGKILL 뒤 PAUSE_CD 없이 running 기록만 남은 조건도 동일하게 보호해야 한다.
        (self.root / 'PAUSE_CD').unlink()
        with mock.patch.object(release, 'write_atomic', side_effect=OSError('디스크 오류 대역')):
            with self.assertRaises(OSError):
                self.cd.reconcile(bundle, EXECUTION, 'operator', 'target', 'INC-482')
        self.assertTrue((self.cd.store / bundle / 'resolution.json').exists())
        self.assertTrue((self.root / 'PAUSE_CD').exists())
        with self.assertRaisesRegex(release.Rejected, 'PAUSE_CD'):
            self.cd.preflight()
        self.cd.reconcile(bundle, EXECUTION, 'operator', 'target', 'INC-482')
        self.cd.preflight()

    def test_health_retry_stops_at_overall_deadline(self):
        bundle_id = self.approved()
        self.cd.apply(bundle_id, EXECUTION)
        bundle = json.loads((self.cd.store / bundle_id / 'bundle.json').read_bytes())
        self.docker.runtime_health_bad = True
        with mock.patch.object(release.time, 'monotonic', side_effect=[0, 0, 300]):
            with self.assertRaises(release.Rejected):
                self.cd.runtime(bundle, self.cd.store / bundle_id)
        self.assertEqual(self.sleeps, [])


class ProcessBoundaryTests(unittest.TestCase):
    def test_child_retains_lock_after_parent_is_killed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            script = r'''import subprocess, sys, time
from pathlib import Path
sys.path.insert(0, sys.argv[1])
from release import Release
root = Path(sys.argv[2])
cd = Release(root)
with cd.exclusive('deploy'):
    cd.command(sys.executable, '-c',
        "import os,pathlib,sys,time; p=pathlib.Path(sys.argv[1]); "
        "p.with_name('ready').write_text(str(os.getpid())); p.with_name('child-ready').touch(); "
        "exec('while not p.exists():\\n time.sleep(0.05)')", str(root / 'stop'))
'''
            parent = subprocess.Popen([sys.executable, '-c', script, str(Path(__file__).parent), tmp])
            try:
                deadline = time.monotonic() + 5
                while not (root / 'child-ready').exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue((root / 'ready').exists())
                self.assertTrue((root / 'child-ready').exists(), '자식이 실제로 기동돼야 함')
                parent.kill()
                parent.wait(timeout=5)
                with self.assertRaisesRegex(release.Rejected, '잠금'):
                    with release.Release(root).exclusive('recovery', wait=0):
                        self.fail('실행 중인 자식의 잠금이 해제됨')
                (root / 'stop').touch()
                with release.Release(root).exclusive('recovery', wait=5):
                    pass
            finally:
                (root / 'stop').touch()
                if parent.poll() is None:
                    parent.kill()
                parent.wait(timeout=5)

    def test_command_timeouts_and_no_mutation_retry(self):
        self.assertEqual(release.command_timeout(('git', 'ls-remote')), 120)
        self.assertEqual(release.command_timeout(('docker', 'pull', 'image')), 600)
        self.assertEqual(release.command_timeout(('docker', 'compose', 'up')), 360)
        self.assertEqual(release.command_timeout(('docker', 'compose', 'exec')), 10)
        self.assertEqual(release.command_timeout(('docker', 'inspect')), 30)
        with mock.patch.object(release, 'command_timeout', return_value=0.05):
            with self.assertRaises(release.Rejected):
                release.run(sys.executable, '-c', 'import time; time.sleep(30)')


class ComposeRoundTripTests(unittest.TestCase):
    def test_rendered_config_preserves_dollar_values_and_bind_paths(self):
        if not shutil.which('docker'):
            self.skipTest('Docker CLI 없음: Compose 해석 실검증 생략')
        version = subprocess.run(['docker', 'compose', 'version'], capture_output=True)
        if version.returncode:
            self.skipTest('Docker Compose 없음: Compose 해석 실검증 생략')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / '.env').write_text("PASSWORD='test$SECRET${UNSET}'\n")
            source = root / 'compose.yaml'
            source.write_text('''services:
  backend:
    image: example.invalid/test:old
    environment:
      PASSWORD: ${PASSWORD}
    volumes:
      - ./realip.conf:/etc/realip.conf:ro
''')
            def compose(file, *args):
                return subprocess.check_output(['docker', 'compose', '--project-name', 'test',
                                                '--project-directory', tmp, '--env-file', str(root / '.env'),
                                                '-f', str(file), 'config', *args], stderr=subprocess.PIPE)
            config = json.loads(compose(source, '--format', 'json'))
            snapshot = root / 'snapshot.json'
            snapshot.write_bytes(release.encoded(config))
            self.assertEqual(compose(source, '--hash', 'backend'), compose(snapshot, '--hash', 'backend'))
            self.assertEqual(config, json.loads(compose(snapshot, '--format', 'json')))
            self.assertEqual(config['services']['backend']['volumes'][0]['source'], str(root / 'realip.conf'))


if __name__ == '__main__':
    unittest.main()
