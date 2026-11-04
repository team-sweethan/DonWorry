#!/usr/bin/env python3
"""VM1 release candidate / approval / update. Requires Python 3.10+, Docker Buildx."""

import argparse
import copy
from contextlib import contextmanager
import datetime
import fcntl
from functools import wraps
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time

ROOT = Path('/srv/donworry')
LOCK = Path('/run/lock/donworry-deploy.lock')
LOCK_WAIT = 300
MARKER = 'ghcr.io/team-sweethan/donworry-release'
GIT = 'https://github.com/team-sweethan/DonWorry.git'
REPOS = {s: f'ghcr.io/team-sweethan/donworry-{n}' for s, n in
         [('backend', 'backend'), ('nginx', 'frontend'), ('ai', 'ai')]}
REVISION = 'org.opencontainers.image.revision'
PREFIX = 'io.donworry.release.'
MANIFESTS = {'application/vnd.oci.image.manifest.v1+json',
             'application/vnd.docker.distribution.manifest.v2+json'}
INDEXES = {'application/vnd.oci.image.index.v1+json',
           'application/vnd.docker.distribution.manifest.list.v2+json'}


class Rejected(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Rejected(message)


def digest(data):
    return 'sha256:' + hashlib.sha256(data).hexdigest()


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def valid_digest(value):
    require(isinstance(value, str) and re.fullmatch(r'sha256:[0-9a-f]{64}', value),
            'digest 형식 오류')
    return value


def token(value):
    require(re.fullmatch(r'[A-Za-z0-9_.@-]{1,120}', value), '식별자 형식 오류')
    return value


def timestamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def boot_id():
    path = Path('/proc/sys/kernel/random/boot_id')
    return path.read_text().strip() if path.exists() else 'unavailable'


def platform_name(platform):
    return platform['os'] + '/' + platform['architecture']


def command_timeout(args):
    if args[:2] == ('docker', 'pull'):
        return 600
    if args[0] == 'git' or args[:2] == ('docker', 'buildx'):
        return 120
    if args[:2] == ('docker', 'compose') and 'up' in args:
        return 360
    if args[0] == 'curl' or (args[:2] == ('docker', 'compose') and 'exec' in args):
        return 10
    return 30


def run(*args, pass_fds=()):
    # Compose 출력/오류에 환경값이 포함될 수 있어 명령 출력은 로그에 재전달하지 않는다.
    try:
        # 부모가 강제 종료돼도 실행 중인 자식이 공통 잠금을 유지한다. SSH 프로세스 그룹과도 분리한다.
        with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              start_new_session=True, pass_fds=pass_fds) as process:
            try:
                output, _ = process.communicate(timeout=command_timeout(args))
            except BaseException:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.communicate()
                raise
            require(process.returncode == 0, f'외부 명령 실패: {args[0]} (출력 생략)')
            return output
    except (subprocess.TimeoutExpired, OSError) as exc:
        raise Rejected(f'외부 명령 실패: {args[0]} (상세 출력은 비밀값 보호를 위해 생략)') from exc


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def write_new(path, value):
    with path.open('xb') as stream:
        stream.write(encoded(value))
        stream.flush()
        os.fsync(stream.fileno())
    sync_directory(path.parent)


def write_atomic(path, value):
    # fsync 후 같은 파일시스템에서 교체해 전원 중단 시에도 이전/새 JSON 중 하나를 읽는다.
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.state-', delete=False) as stream:
        temporary = Path(stream.name)
        try:
            stream.write(encoded(value))
            stream.flush()
            os.fsync(stream.fileno())
            temporary.replace(path)
            sync_directory(path.parent)
        finally:
            temporary.unlink(missing_ok=True)


def write_deployment_file(path, data, on_replace=None):
    # 배포 호환 파일은 root:root 0600. 환경값은 파싱/재출력하지 않는다.
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.deploy-', delete=False) as stream:
        temporary = Path(stream.name)
        try:
            os.fchown(stream.fileno(), 0, 0)
            os.fchmod(stream.fileno(), 0o600)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
            temporary.replace(path)
            if on_replace is not None:
                on_replace()
            sync_directory(path.parent)
        finally:
            temporary.unlink(missing_ok=True)


def image_tag(data):
    require(len(re.findall(rb'^IMAGE_TAG=', data, re.MULTILINE)) == 1,
            'IMAGE_TAG 설정이 정확히 하나여야 함')
    matches = list(re.finditer(rb'^IMAGE_TAG=([^\r\n]*)(?=\r?$)', data, re.MULTILINE))
    require(len(matches) == 1, 'IMAGE_TAG 설정이 정확히 하나여야 함')
    tag = matches[0].group(1)
    require(re.fullmatch(rb'sha-[0-9a-f]{40}', tag), 'IMAGE_TAG 형식 오류')
    return tag.decode('ascii'), matches[0].span(1)


def serialized(action):
    def decorate(method):
        @wraps(method)
        def wrapped(self, *args, **kwargs):
            with self.exclusive(action):
                return method(self, *args, **kwargs)
        return wrapped
    return decorate


class Release:
    def __init__(self, root=ROOT, command=run, sleep=time.sleep):
        self.root = root
        self.lock_fd = None
        self.lock_path = LOCK if root == ROOT else root / 'deploy.lock'
        self.command = (lambda *args: run(*args, pass_fds=(() if self.lock_fd is None else
                                                         (self.lock_fd,)))) if command is run else command
        self.sleep = sleep
        self.store = root / 'releases'

    @contextmanager
    def exclusive(self, action, wait=LOCK_WAIT):
        # Jenkins 외 수동 실행과 #483 복구도 이 경계를 사용한다. 잠금 파일은 삭제하지 않는다.
        with self.lock_path.open('a') as lock:
            deadline = time.monotonic() + wait
            while True:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    require(time.monotonic() < deadline, f'공통 잠금 대기 초과: {action}')
                    time.sleep(0.1)
            previous = self.lock_fd
            self.lock_fd = lock.fileno()
            try:
                yield
            finally:
                # LOCK_UN을 호출하면 상속받은 자식의 잠금까지 풀리므로 fd만 닫는다.
                self.lock_fd = previous

    def unresolved(self, exclude=None):
        pending = []
        for path in self.store.glob('*/result.json'):
            result = json.loads(path.read_bytes())
            if path.parent.name == exclude or result.get('status') == 'success':
                continue
            resolution = path.with_name('resolution.json')
            if resolution.exists():
                record = json.loads(resolution.read_bytes())
                require(record['result_digest'] == digest(path.read_bytes()), '상태 확인 기록 불일치')
                continue
            pending.append(path.parent.name)
        return pending

    def json(self, *args):
        return json.loads(self.command(*args))

    def compose(self, *args, file=None, env_file=None):
        return self.command('docker', 'compose', '--project-name', 'donworry',
                            '--project-directory', str(self.root), '--env-file',
                            str(env_file or self.root / '.env'), '-f', str(file or self.root / 'compose.yaml'),
                            *args)

    def main(self, sha):
        actual = self.command('git', 'ls-remote', '--exit-code', '--refs', GIT,
                              'refs/heads/main').decode().rstrip('\n')
        require(actual == f'{sha}\trefs/heads/main', '승인 SHA와 현재 main HEAD 불일치')

    def preflight(self, exclude=None):
        require(not (self.root / 'PAUSE_CD').exists() and
                not (self.root / 'PAUSE_CD').is_symlink(), 'PAUSE_CD: 수동 확인 필요')
        require(not self.unresolved(exclude), '실패/미완료 업데이트 기록: inspect/reconcile 필요')
        require((self.root / 'backups/deployed-image-tag').is_file(),
                '기존 성공 배포 기록 없음: 최초 배포는 이 명령의 대상이 아님')
        previous = (self.root / 'backups/deployed-image-tag').read_text().strip()
        require(re.fullmatch(r'sha-[0-9a-f]{40}', previous), '기존 성공 배포 기록 오류')
        tag, _ = image_tag((self.root / '.env').read_bytes())
        require(tag == previous, '기존 IMAGE_TAG와 성공 배포 기록 불일치')
        cid = self.compose('ps', '-q', 'db').decode().strip()
        require(bool(cid), '기존 DB 없음')
        db = self.json('docker', 'inspect', cid)[0]
        require(db['State'].get('Health', {}).get('Status') == 'healthy', '기존 DB 비정상')

    def platform(self):
        info = self.json('docker', 'info', '--format', '{{json .}}')
        arch = {'x86_64': 'amd64', 'aarch64': 'arm64'}.get(info['Architecture'], info['Architecture'])
        require(info['OSType'] == 'linux' and arch in {'amd64', 'arm64'}, '미지원 VM 플랫폼')
        return {'os': 'linux', 'architecture': arch}

    def configuration(self, env_file=None):
        config = json.loads(self.compose('config', '--format', 'json', env_file=env_file))
        require(set(config['services']) == {'db', *REPOS}, '예상하지 않은 Compose 서비스')
        inputs = {'.env': digest((env_file or self.root / '.env').read_bytes()),
                  'compose.yaml': digest((self.root / 'compose.yaml').read_bytes())}
        inputs['release.py'] = digest(Path(__file__).read_bytes())
        # 읽기 전용 bind 파일도 승인 구성에 포함한다. DB named volume 내용은 읽지 않는다.
        for service in config['services'].values():
            require('build' not in service and 'env_file' not in service, '미해석 Compose 설정')
            for volume in service.get('volumes', []):
                if volume['type'] != 'bind':
                    continue
                require(volume.get('read_only') is True, '쓰기 가능한 bind는 별도 검토 필요')
                source = Path(volume['source'])
                require(source.exists() and not source.is_symlink(), 'bind 경로 없음/심볼릭 링크')
                paths = [source, *sorted(source.rglob('*'))] if source.is_dir() else [source]
                for path in paths:
                    require(not path.is_symlink(), 'bind 내부 심볼릭 링크 거절')
                    inputs[str(path)] = digest(path.read_bytes()) if path.is_file() else 'directory'
        require(not config.get('secrets') and not config.get('configs'),
                '외부 secrets/configs는 별도 구성 추적 필요')
        return config, {'inputs': inputs, 'rendered': digest(encoded(config))}

    def manifest(self, ref, expected, types):
        raw = self.command('docker', 'buildx', 'imagetools', 'inspect', '--raw', ref)
        require(digest(raw) == expected, '원격 manifest digest 불일치')
        value = json.loads(raw)
        require(value.get('schemaVersion') == 2 and value.get('mediaType') in types,
                '지원하지 않는 manifest 형식')
        return value

    def config(self, ref):
        return self.json('docker', 'buildx', 'imagetools', 'inspect',
                         '--format', '{{json .Image}}', ref)

    def marker(self, marker_digest):
        ref = f'{MARKER}@{valid_digest(marker_digest)}'
        self.manifest(ref, marker_digest, MANIFESTS)
        labels = self.config(ref)['config']['Labels']
        require(labels.get(PREFIX + 'schema-version') == '1', 'marker 스키마 누락/미지원')
        sha = labels.get(REVISION)
        require(isinstance(sha, str) and re.fullmatch(r'[0-9a-f]{40}', sha), 'marker SHA 오류')
        images = {s: valid_digest(labels.get(PREFIX + n + '.digest'))
                  for s, n in [('backend', 'backend'), ('nginx', 'frontend'), ('ai', 'ai')]}
        return sha, images

    def image(self, service, index_digest, sha, platform):
        repo = REPOS[service]
        index = self.manifest(f'{repo}@{index_digest}', index_digest, INDEXES)
        matches = [m for m in index['manifests']
                   if m.get('platform', {}).get('os') == platform['os'] and
                   m.get('platform', {}).get('architecture') == platform['architecture'] and
                   m.get('platform', {}).get('variant', '') in ('', 'v8') and
                   m.get('annotations', {}).get('vnd.docker.reference.type') != 'attestation-manifest']
        require(len(matches) == 1, '실행 플랫폼 manifest 없음/중복')
        child_digest = valid_digest(matches[0]['digest'])
        child_ref = f'{repo}@{child_digest}'
        child = self.manifest(child_ref, child_digest, MANIFESTS)
        config = self.config(child_ref)
        require(config.get('os') == platform['os'] and
                config.get('architecture') == platform['architecture'], '이미지 플랫폼 불일치')
        require(config['config'].get('Labels', {}).get(REVISION) == sha, '이미지 revision 불일치')
        return {'repository': repo, 'index': index_digest, 'manifest': child_digest,
                'config': valid_digest(child['config']['digest'])}

    def verify(self, bundle):
        sha, indexes = self.marker(bundle['marker'])
        require(sha == bundle['sha'], '승인 SHA와 marker revision 불일치')
        require(self.platform() == bundle['platform'], '승인 후 VM 플랫폼 변경')
        for service, image in bundle['images'].items():
            require(image == self.image(service, indexes[service], sha, bundle['platform']),
                    '승인 이미지 묶음 불일치')

    @serialized('prepare')
    def prepare(self, sha, execution):
        require(re.fullmatch(r'[0-9a-f]{40}', sha), 'TARGET_SHA 형식 오류')
        token(execution)
        self.preflight()
        self.main(sha)
        descriptor = self.json('docker', 'buildx', 'imagetools', 'inspect', '--format',
                               '{{json .Manifest}}', MARKER + ':prod')
        marker = valid_digest(descriptor['digest'])
        revision, indexes = self.marker(marker)
        require(revision == sha, 'stale marker: TARGET_SHA와 revision 불일치')
        platform = self.platform()
        images = {s: self.image(s, d, sha, platform) for s, d in indexes.items()}
        config, version = self.configuration()
        bundle = {'schema': 1, 'sha': sha, 'marker': marker, 'platform': platform,
                  'images': images, 'configuration': version, 'execution': execution,
                  'created_at': timestamp()}
        bundle_id = digest(encoded(bundle)).split(':')[1]
        folder = self.store / bundle_id
        folder.mkdir(parents=True, mode=0o700)
        sync_directory(self.root)
        sync_directory(self.store)
        resolved = copy.deepcopy(config)
        for s, image in images.items():
            resolved['services'][s]['image'] = f"{image['repository']}@{image['index']}"
            resolved['services'][s]['platform'] = platform_name(platform)
            resolved['services'][s]['pull_policy'] = 'never'
        # Compose config 출력은 $를 이미 이스케이프한다. 다시 변환하면 환경값이 바뀐다.
        write_new(folder / 'compose.json', resolved)
        bundle['compose_digest'] = digest((folder / 'compose.json').read_bytes())
        # 최종 저장 내용 전체를 ID에 묶는다.
        final_id = digest(encoded(bundle)).split(':')[1]
        write_new(folder / 'bundle.json', bundle)
        folder.rename(self.store / final_id)
        sync_directory(self.store)
        return {'bundle_id': final_id, **bundle}

    def load(self, bundle_id, execution):
        folder, bundle = self.load_snapshot(bundle_id, execution)
        require(self.configuration()[1] == bundle['configuration'], '구성 변경: 새 준비·승인 필요')
        return folder, bundle

    @serialized('approve')
    def approve(self, bundle_id, execution, approver):
        folder, bundle = self.load(bundle_id, execution)
        require(not (folder / 'result.json').exists(), '이미 적용을 시도한 승인')
        write_new(folder / 'approval.json', {'bundle_id': bundle_id, 'execution': execution,
                                           'approver': token(approver), 'approved_at': timestamp()})
        return {'bundle_id': bundle_id, 'approved': True}

    def image_ids(self, bundle):
        # 기존 저장소는 이미지 ID가 config digest지만, containerd 이미지 저장소는
        # pull한 index digest를 이미지 ID로 쓴다. 저장 방식은 실행할 때마다 확인한다.
        info = self.json('docker', 'info', '--format', '{{json .}}')
        containerd = any(isinstance(item, list) and len(item) == 2 and item[0] == 'driver-type' and
                         str(item[1]).startswith('io.containerd.snapshotter')
                         for item in info.get('DriverStatus') or [])
        return {s: image['index'] if containerd else image['config']
                for s, image in bundle['images'].items()}

    def local_images(self, bundle, pull):
        ids = self.image_ids(bundle)
        for service, image in bundle['images'].items():
            ref = f"{image['repository']}@{image['index']}"
            if pull:
                self.command('docker', 'pull', '--platform', platform_name(bundle['platform']), ref)
            local = self.json('docker', 'image', 'inspect', ref)[0]
            require(local['Id'] == ids[service] and
                    local['Os'] == bundle['platform']['os'] and
                    local['Architecture'] == bundle['platform']['architecture'] and
                    local['Config'].get('Labels', {}).get(REVISION) == bundle['sha'],
                    '로컬 이미지 digest/platform/revision 불일치')

    def local_sha_tags(self, bundle, assign=False):
        ids = self.image_ids(bundle)
        for service, image in bundle['images'].items():
            target = image['repository'] + ':sha-' + bundle['sha']
            if assign:
                self.command('docker', 'image', 'tag', image['repository'] + '@' + image['index'], target)
            require(self.json('docker', 'image', 'inspect', target)[0]['Id'] == ids[service],
                    '로컬 SHA 태그 이미지 불일치')

    def planned_configuration(self, env_data, bundle):
        # 실제 파일 교체 전에 변경 후 해시를 계산한다. Compose가 읽을 때만 비밀 임시 파일을 둔다.
        with tempfile.NamedTemporaryFile(dir=self.root, prefix='.planned-env-') as stream:
            stream.write(env_data)
            stream.flush()
            configuration = self.configuration(env_file=Path(stream.name))[1]
        require({k: v for k, v in configuration['inputs'].items() if k != '.env'} ==
                {k: v for k, v in bundle['configuration']['inputs'].items() if k != '.env'},
                '구성 변경: 기록 갱신 계획 중단')
        return configuration

    def publish_deployment(self, bundle, result, stage):
        require(self.configuration()[1] == bundle['configuration'], '구성 변경: 기록 갱신 중단')
        env = self.root / '.env'
        deployed = self.root / 'backups/deployed-image-tag'
        original = env.read_bytes()
        before, (start, end) = image_tag(original)
        desired = 'sha-' + bundle['sha']
        updates = {'.env': (env, original, original[:start] + desired.encode() + original[end:]),
                   'backups/deployed-image-tag': (deployed, deployed.read_bytes(), (desired + '\n').encode())}
        record = {'before': before, 'intended': desired, 'after': before,
                  'files': {name: 'pending' for name in updates}}
        result['deployment_records'] = record
        result['planned_configuration'] = self.planned_configuration(updates['.env'][2], bundle)
        # 두 파일 중 하나라도 바뀌기 전에 계획을 디스크에 남긴다.
        stage('publishing_records')
        try:
            # SHA 태그를 원격에서 다시 고르지 않는다. 승인 digest로 이미 받은 이미지에 붙인다.
            self.local_sha_tags(bundle, assign=True)
            for name, (path, _, data) in updates.items():
                record['files'][name] = 'writing'
                stage('publishing_records')
                def replaced():
                    record['files'][name] = 'updated'
                write_deployment_file(path, data, on_replace=replaced)
                record['files'][name] = 'updated'
                stage('publishing_records')
            # IMAGE_TAG 변경은 다음 승인의 구성에 포함한다. 이전 snapshot은 고치지 않는다.
            published = self.configuration()[1]
            require(published == result['planned_configuration'],
                    '기록 갱신 후 구성 불일치')
            result['published_configuration'] = published
        finally:
            # rename 후 fsync 실패도 실제 교체 여부를 구분한다. 비밀 환경값은 기록하지 않는다.
            for name, (path, old, new) in updates.items():
                try:
                    actual = path.read_bytes()
                    if old == new == actual:
                        # 같은 SHA면 내용만으로 rename 여부를 알 수 없다.
                        state = record['files'][name]
                        record['files'][name] = ('updated' if state == 'updated' else
                                                 'unchanged' if state == 'pending' else 'unknown')
                    else:
                        record['files'][name] = ('updated' if actual == new else
                                                 'unchanged' if actual == old else 'unknown')
                except OSError:
                    record['files'][name] = 'unknown'
            try:
                record['after'] = image_tag(env.read_bytes())[0]
            except (OSError, Rejected):
                record['after'] = None

    @contextmanager
    def operation(self, folder, bundle_id, execution, approval, kind='deploy'):
        # 복구 구현도 exclusive + operation을 함께 사용해야 한다 (#483).
        require(self.lock_fd is not None, '변경 작업은 공통 잠금 필요')
        result = {'bundle_id': bundle_id, 'execution': execution, 'approval': approval,
                  'operation': kind, 'pid': os.getpid(),
                  'boot_id': boot_id(),
                  'started_at': timestamp(), 'status': 'running', 'phase': 'validating',
                  'history': []}
        write_new(folder / 'result.json', result)
        def stage(phase):
            result['phase'] = phase
            result['updated_at'] = timestamp()
            result['history'].append({'phase': phase, 'at': result['updated_at']})
            write_atomic(folder / 'result.json', result)
        try:
            stage('validating')
            yield result, stage
            result['status'] = 'success'
        except BaseException:
            result['status'] = 'failed'
            (self.root / 'PAUSE_CD').touch(mode=0o600, exist_ok=True)
            sync_directory(self.root)
            raise
        finally:
            result['finished_at'] = timestamp()
            write_atomic(folder / 'result.json', result)

    @serialized('apply')
    def apply(self, bundle_id, execution):
        require((self.root / 'ENABLE_UPDATE_CD').is_file(), '실제 CD 활성화 전: 운영 검증·승인 필요')
        folder, bundle = self.load(bundle_id, execution)
        approval = json.loads((folder / 'approval.json').read_bytes())
        require(approval['bundle_id'] == bundle_id and approval['execution'] == execution,
                '승인 기록 불일치')
        require(not (folder / 'result.json').exists(), '이미 적용을 시도한 승인')
        # 잠금 대기 후 검사한다. 중단 기록이 있으면 다운로드도 새로 시작하지 않는다.
        self.preflight()
        with self.operation(folder, bundle_id, execution, approval) as (result, stage):
            before = image_tag((self.root / '.env').read_bytes())[0]
            result['deployment_records'] = {
                'before': before, 'intended': 'sha-' + bundle['sha'], 'after': before,
                'files': {'.env': 'unchanged', 'backups/deployed-image-tag': 'unchanged'}}
            stage('validating')
            self.verify(bundle)
            stage('pulling')
            self.local_images(bundle, pull=True)
            stage('final_validation')
            self.load(bundle_id, execution)
            self.preflight(exclude=bundle_id)
            self.verify(bundle)
            self.local_images(bundle, pull=False)
            self.compose('config', '--quiet', file=folder / 'compose.json')
            self.main(bundle['sha'])
            stage('applying')
            # DB는 기존 healthy 인스턴스를 그대로 사용한다. 자동 재시도하지 않는다.
            self.compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                         '--wait', '--wait-timeout', '240', 'backend', 'ai', 'nginx',
                         file=folder / 'compose.json')
            stage('checking')
            self.runtime(bundle, folder)
            self.publish_deployment(bundle, result, stage)
            stage('publishing')
            write_atomic(self.store / 'current.json', {'bundle_id': bundle_id, 'sha': bundle['sha']})
            stage('completed')
        return result

    @serialized('inspect')
    def inspect(self, bundle_id, execution):
        folder, bundle = self.load_snapshot(bundle_id, execution)
        result = json.loads((folder / 'result.json').read_bytes())
        observed_boot = boot_id()
        previous_boot = result.get('boot_id', 'unavailable')
        # State 전체나 Config.Env를 출력하지 않는다. 컨테이너별 혼합 상태를 보여준다.
        services = {}
        for service in ['db', *REPOS]:
            cid = self.compose('ps', '-a', '-q', service, file=folder / 'compose.json').decode().strip()
            if not cid:
                services[service] = {'present': False}
                continue
            container = self.json('docker', 'inspect', cid)[0]
            services[service] = {'present': True, 'image_id': container.get('Image'),
                                 'image_ref': container.get('Config', {}).get('Image'),
                                 'running': container['State'].get('Running', False),
                                 'health': container['State'].get('Health', {}).get('Status')}
        return {'bundle_id': bundle_id, 'execution': execution, 'observed_at': timestamp(),
                'observed_boot_id': observed_boot,
                'boot_changed': (previous_boot != observed_boot if 'unavailable' not in
                                 {previous_boot, observed_boot} else None),
                'result': result,
                'unresolved': self.unresolved(), 'services': services}

    def load_snapshot(self, bundle_id, execution):
        require(re.fullmatch(r'[0-9a-f]{64}', bundle_id), '묶음 ID 형식 오류')
        folder = self.store / bundle_id
        bundle = json.loads((folder / 'bundle.json').read_bytes())
        require(digest(encoded(bundle)) == 'sha256:' + bundle_id, '승인 묶음 변조')
        require(bundle['execution'] == token(execution), '실행 ID 불일치')
        require(digest((folder / 'compose.json').read_bytes()) == bundle['compose_digest'],
                '저장된 Compose 변경')
        return folder, bundle

    @serialized('reconcile')
    def reconcile(self, bundle_id, execution, operator, against, evidence):
        folder, bundle = self.load_snapshot(bundle_id, execution)
        token(operator)
        token(evidence)
        result = json.loads((folder / 'result.json').read_bytes())
        require(result['status'] != 'success', '성공 실행은 중단 확인 대상이 아님')
        require(self.unresolved() in ([bundle_id], []), '다른 미완료 실행부터 확인 필요')
        require(against in {'current', 'target'}, '확인 대상은 current/target만 허용')
        # SIGKILL 기록에는 PAUSE_CD가 없을 수 있다. 해제 기록/포인터 저장 사이 중단도 차단한다.
        (self.root / 'PAUSE_CD').touch(mode=0o600, exist_ok=True)
        sync_directory(self.root)
        expected = bundle_id
        if against == 'current':
            expected = json.loads((self.store / 'current.json').read_bytes())['bundle_id']
            require(re.fullmatch(r'[0-9a-f]{64}', expected), '현재 묶음 ID 형식 오류')
            require(expected != bundle_id, '미완료 대상은 target으로 명시해 확인 필요')
            old = json.loads((self.store / expected / 'bundle.json').read_bytes())
            folder_expected, bundle = self.load_snapshot(expected, old['execution'])
            previous_result = folder_expected / 'result.json'
            previous_resolution = folder_expected / 'resolution.json'
            confirmed = json.loads(previous_result.read_bytes())['status'] == 'success'
            if not confirmed and previous_resolution.exists():
                resolved = json.loads(previous_resolution.read_bytes())
                confirmed = (resolved['observed_bundle'] == expected and
                             resolved['result_digest'] == digest(previous_result.read_bytes()))
            require(confirmed, '이전 성공/확인 기록 없음: #483 운영자 복구 필요')
        else:
            folder_expected = folder
        # 파일/환경 변경과 DB 비정상은 중단 상태 해제로 숨기지 않는다.
        expected_result = json.loads((folder_expected / 'result.json').read_bytes())
        expected_configurations = [bundle['configuration']]
        for key in ['planned_configuration', 'published_configuration']:
            if key in expected_result:
                expected_configurations.append(expected_result[key])
        require(self.configuration()[1] in expected_configurations, '구성 변경: 운영자 확인 필요')
        # 부분 기록 갱신은 자동 복구하지 않는다. 두 기록과 확인한 이미지가 맞아야 해제한다.
        tag, _ = image_tag((self.root / '.env').read_bytes())
        require(tag == 'sha-' + bundle['sha'] and
                (self.root / 'backups/deployed-image-tag').read_text().strip() == tag,
                '배포 기록 불일치: #483 운영자 복구 필요')
        cid = self.compose('ps', '-q', 'db', file=folder_expected / 'compose.json').decode().strip()
        require(bool(cid), '기존 DB 없음')
        require(self.json('docker', 'inspect', cid)[0]['State'].get('Health', {}).get('Status') == 'healthy',
                '기존 DB 비정상')
        self.local_images(bundle, pull=False)
        self.runtime(bundle, folder_expected)
        # 같은 SHA 재빌드도 digest는 다르다. 태그를 고치지 않고 불일치 시 차단을 유지한다.
        self.local_sha_tags(bundle)
        record = {'bundle_id': bundle_id, 'execution': execution, 'operator': operator,
                  'evidence': evidence,
                  'against': against, 'observed_bundle': expected, 'checked_at': timestamp(),
                  'result_digest': digest((folder / 'result.json').read_bytes()),
                  'scope': 'runtime-images-and-health-only; DB/business correctness requires #483'}
        # history는 보존하고 별도 해제 증거를 남긴다. 대상 승인 재사용은 여전히 금지한다.
        resolution = folder / 'resolution.json'
        if resolution.exists():
            previous = json.loads(resolution.read_bytes())
            require(all(previous[key] == value for key, value in record.items() if key != 'checked_at'),
                    '기존 상태 확인과 불일치')
            record = previous
        else:
            write_new(resolution, record)
        write_atomic(self.store / 'current.json', {'bundle_id': expected, 'sha': bundle['sha']})
        (self.root / 'PAUSE_CD').unlink(missing_ok=True)
        sync_directory(self.root)
        return record

    def runtime(self, bundle, folder):
        ids = self.image_ids(bundle)
        for service, image in bundle['images'].items():
            cid = self.compose('ps', '-q', service, file=folder / 'compose.json').decode().strip()
            require(bool(cid), '실행 컨테이너 없음')
            container = self.json('docker', 'inspect', cid)[0]
            require(container['Image'] == ids[service] and container['State']['Running'] and
                    container['Config']['Image'] == f"{image['repository']}@{image['index']}",
                    '실행 컨테이너 이미지 불일치')
        # 현재 앱 이미지에 Docker healthcheck가 없어 Compose의 running만으로 준비를 판단할 수 없다.
        deadline = time.monotonic() + 300
        for attempt in range(60):
            require(time.monotonic() < deadline, '기동 검사 전체 제한 시간 초과')
            try:
                self.health(folder)
                return
            except Rejected:
                if attempt == 59 or time.monotonic() >= deadline:
                    raise
                self.sleep(4)

    def health(self, folder):
        self.command('curl', '-fsS', '--max-time', '5', 'http://127.0.0.1/healthz')
        reply = self.compose('exec', '-T', 'nginx', 'sh', '-c',
                             'wget -S -O /dev/null -T 5 -t 1 http://backend:8081/api/auth/me 2>&1 || true',
                             file=folder / 'compose.json').decode()
        require(re.search(r'HTTP/[0-9.]+\s+(200|401|403)(\s|$)', reply), 'Spring Boot 응답 오류')
        self.compose('exec', '-T', 'ai', 'python', '-c',
                     "from urllib.request import urlopen; assert urlopen('http://127.0.0.1:8000/health', timeout=5).status == 200",
                     file=folder / 'compose.json')


def cli():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'approve', 'apply', 'inspect', 'reconcile'])
    parser.add_argument('target', help='prepare: TARGET_SHA, 그 외: bundle ID')
    parser.add_argument('execution', help='Jenkins 또는 수동 실행 식별자')
    parser.add_argument('approver', nargs='?')
    parser.add_argument('--against', choices=['current', 'target'])
    parser.add_argument('--evidence', help='작업 종료·DB/Flyway·업무 영향 확인 근거 식별자 (비밀값 금지)')
    args = parser.parse_args()
    require(os.geteuid() == 0, 'root 전용 명령')
    os.umask(0o077)
    # SSH 단절은 취소 요청으로 취급하지 않는다. 결과는 VM 기록으로 확인한다.
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    def interrupted(signum, frame):
        raise Rejected('실행 중단 신호: VM 상태 확인 필요')
    signal.signal(signal.SIGTERM, interrupted)
    release = Release()
    if args.action == 'prepare':
        result = release.prepare(args.target, args.execution)
    elif args.action == 'approve':
        require(args.approver is not None, '승인자 누락')
        result = release.approve(args.target, args.execution, args.approver)
    elif args.action == 'apply':
        result = release.apply(args.target, args.execution)
    elif args.action == 'inspect':
        result = release.inspect(args.target, args.execution)
    else:
        require(args.approver is not None and args.against is not None and args.evidence is not None,
                '운영자/확인 대상/수동 점검 근거 누락')
        result = release.reconcile(args.target, args.execution, args.approver, args.against, args.evidence)
    print(encoded(result).decode())


if __name__ == '__main__':
    try:
        cli()
    except (Rejected, OSError, ValueError, KeyError, TypeError, IndexError) as error:
        # JSON 파싱 오류 등에도 입력 원문/비밀값을 출력하지 않는다.
        print(str(error) if isinstance(error, Rejected) else 'ERROR: 입력/상태 검사 실패', file=sys.stderr)
        sys.exit(1)
