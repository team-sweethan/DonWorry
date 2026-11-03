import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

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
            return release.encoded({'OSType': 'linux', 'Architecture': 'x86_64'})
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

    def test_local_revision_or_platform_mismatch_blocks_apply(self):
        for key, value in [('Architecture', 'arm64'), ('Os', 'windows'),
                           ('Config', {'Labels': {release.REVISION: 'b' * 40}})]:
            with self.subTest(key=key):
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
