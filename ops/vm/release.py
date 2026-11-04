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
    if args[0] == 'python3' and Path(args[-1]).name == 'functional_check.py':
        return 60
    if args[:2] == ('docker', 'compose') and any('exec mariadb-dump ' in a for a in args):
        return 900
    if args[:2] == ('docker', 'compose') and 'db-state' in args:
        return 30
    if args[:2] == ('docker', 'pull'):
        return 600
    if args[0] == 'git' or args[:2] == ('docker', 'buildx'):
        return 120
    if args[:2] == ('docker', 'compose') and 'up' in args:
        return 360
    if args[0] == 'curl' or (args[:2] == ('docker', 'compose') and 'exec' in args):
        return 10
    return 30


def run(*args, pass_fds=(), output_path=None):
    # Compose 출력/오류에 환경값이 포함될 수 있어 명령 출력은 로그에 재전달하지 않는다.
    try:
        # 부모가 강제 종료돼도 실행 중인 자식이 공통 잠금을 유지한다. SSH 프로세스 그룹과도 분리한다.
        with subprocess.Popen(args, stdout=subprocess.PIPE if output_path is None else output_path,
                              stderr=subprocess.PIPE,
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


def revision_ok(labels, sha, verified=True):
    # #480 이전 이미지는 revision label이 없다. 운영자가 명시한 관측 등록에서만 label 부재를 허용하고,
    # 다른 SHA를 가리키는 label은 어떤 경우에도 거절한다.
    value = (labels or {}).get(REVISION)
    return value == sha or (not verified and value is None)


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
        self.command = (lambda *args, **kwargs: run(*args, pass_fds=(() if self.lock_fd is None else
                                                         (self.lock_fd,)), **kwargs)) if command is run else command
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
        for name in ['functional-check.json', 'functional_check.py']:
            path = self.root / name
            if path.exists():
                require(path.is_file() and not path.is_symlink(), '기능 검사 파일 형식 오류')
                inputs[name] = digest(path.read_bytes())
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

    def image(self, service, index_digest, sha, platform, verified=True):
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
        require(revision_ok(config['config'].get('Labels'), sha, verified), '이미지 revision 불일치')
        return {'repository': repo, 'index': index_digest, 'manifest': child_digest,
                'config': valid_digest(child['config']['digest'])}

    def verify(self, bundle):
        if bundle.get('origin') == 'observed':
            sha = bundle['sha']
            indexes = {s: image['index'] for s, image in bundle['images'].items()}
        else:
            sha, indexes = self.marker(bundle['marker'])
            require(sha == bundle['sha'], '승인 SHA와 marker revision 불일치')
        require(self.platform() == bundle['platform'], '승인 후 VM 플랫폼 변경')
        verified = bundle.get('revision_verified', True)
        for service, image in bundle['images'].items():
            require(image == self.image(service, indexes[service], sha, bundle['platform'], verified),
                    '승인 이미지 묶음 불일치')

    def database_state(self):
        # 출력은 스키마와 Flyway 메타데이터뿐이다. 업무 행/접속 비밀값은 반환하지 않는다.
        cid = self.compose('ps', '-q', 'db').decode().strip()
        require(bool(cid), '기존 DB 없음')
        require(self.json('docker', 'inspect', cid)[0]['State'].get('Health', {}).get('Status') == 'healthy',
                'DB 비정상: 운영자 판단 필요')
        sql = """
SELECT JSON_ARRAY('flyway', installed_rank, version, type, script, checksum, success)
FROM flyway_schema_history ORDER BY installed_rank;
SELECT JSON_ARRAY('column', TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE,
 IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLLATION_NAME, GENERATION_EXPRESSION)
FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
ORDER BY TABLE_NAME, ORDINAL_POSITION;
SELECT JSON_ARRAY('index', TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
 SUB_PART, INDEX_TYPE) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;
SELECT JSON_ARRAY('constraint', TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME,
 REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME, ORDINAL_POSITION)
FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION;
SELECT JSON_ARRAY('table', TABLE_NAME, ENGINE, ROW_FORMAT, TABLE_COLLATION, CREATE_OPTIONS)
FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME;
SELECT JSON_ARRAY('trigger', TRIGGER_NAME, EVENT_MANIPULATION, EVENT_OBJECT_TABLE,
 ACTION_TIMING, ACTION_STATEMENT, ACTION_ORDER, SQL_MODE, DEFINER,
 CHARACTER_SET_CLIENT, COLLATION_CONNECTION, DATABASE_COLLATION) FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY TRIGGER_NAME;
SELECT JSON_ARRAY('partition', TABLE_NAME, PARTITION_NAME, SUBPARTITION_NAME,
 PARTITION_METHOD, SUBPARTITION_METHOD, PARTITION_EXPRESSION, SUBPARTITION_EXPRESSION,
 PARTITION_DESCRIPTION) FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA=DATABASE()
ORDER BY TABLE_NAME, PARTITION_ORDINAL_POSITION, SUBPARTITION_ORDINAL_POSITION;
SELECT JSON_ARRAY('reference', TABLE_NAME, CONSTRAINT_NAME, UPDATE_RULE, DELETE_RULE)
FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
ORDER BY TABLE_NAME, CONSTRAINT_NAME;
SELECT JSON_ARRAY('server', VERSION(), DATABASE(), @@lower_case_table_names);
SELECT JSON_ARRAY('check', TABLE_NAME, CONSTRAINT_NAME, CHECK_CLAUSE)
FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
ORDER BY TABLE_NAME, CONSTRAINT_NAME;
SELECT JSON_ARRAY('unsupported',
 (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
  AND (TABLE_TYPE <> 'BASE TABLE' OR ENGINE <> 'InnoDB')),
 (SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE()),
 (SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA=DATABASE()));
"""
        raw = self.compose('exec', '-T', 'db', 'sh', '-c',
                           'export MYSQL_PWD="$DB_MIGRATE_PASSWORD"; '
                           'exec mariadb -udw_migrate --batch --raw --skip-column-names '
                           '--database="$MARIADB_DATABASE" -e "$1"', 'db-state', sql)
        rows = [json.loads(line) for line in raw.decode().splitlines()]
        history = [row for row in rows if row[0] == 'flyway']
        require(history and all(row[-1] == 1 for row in history),
                'Flyway 이력 없음/실패: 운영자 판단 필요')
        require([r for r in rows if r[0] == 'unsupported'] == [['unsupported', 0, 0, 0]],
                '비 InnoDB/뷰/루틴/이벤트는 백업 정책 검토 필요')
        require(any(row[0] == 'column' for row in rows), 'DB 스키마 조회 불완전')
        return {'fingerprint': digest(encoded(rows)), 'flyway': history, 'checked_at': timestamp()}

    def confirmed_current(self):
        require((self.store / 'current.json').is_file(), '등록된 현재 묶음 없음: register-current 필요')
        current = json.loads((self.store / 'current.json').read_bytes())['bundle_id']
        saved = json.loads((self.store / current / 'bundle.json').read_bytes())
        folder, bundle = self.load_snapshot(current, saved['execution'])
        result = json.loads((folder / 'result.json').read_bytes())
        require(result['status'] == 'success', '복구 후보는 성공 기록 필요')
        configurations = [bundle['configuration'], result.get('published_configuration')]
        require(self.configuration()[1] in configurations, '기존 성공 구성과 현재 구성 불일치')
        self.local_images(bundle, pull=False)
        self.observed_runtime(bundle, folder)
        return current, folder, bundle

    @serialized('register-current')
    def register_current(self, sha, execution, operator, evidence, without_revision=False):
        require(re.fullmatch(r'[0-9a-f]{40}', sha), '현재 SHA 형식 오류')
        token(execution)
        token(operator)
        token(evidence)
        self.preflight()
        require(image_tag((self.root / '.env').read_bytes())[0] == 'sha-' + sha, '현재 SHA 기록 불일치')
        config, version = self.configuration()
        if (self.store / 'current.json').exists():
            current = json.loads((self.store / 'current.json').read_bytes())['bundle_id']
            saved = json.loads((self.store / current / 'bundle.json').read_bytes())
            previous_folder, previous = self.load_snapshot(current, saved['execution'])
            previous_result = json.loads((previous_folder / 'result.json').read_bytes())
            resolved = previous_folder / 'resolution.json'
            confirmation = json.loads(resolved.read_bytes()) if resolved.exists() else {}
            require(previous_result['status'] == 'success' or
                    (confirmation.get('observed_bundle') == current and
                     confirmation.get('result_digest') == digest((previous_folder / 'result.json').read_bytes())),
                    '기존 성공/상태 확인 기록 필요')
            require(previous_result['status'] != 'success' or
                    version not in [previous['configuration'], previous_result.get('published_configuration')],
                    '동일한 현재 구성은 이미 등록됨')
        platform = self.platform()
        images = {}
        for service, repo in REPOS.items():
            cid = self.compose('ps', '-q', service).decode().strip()
            require(bool(cid), '기존 서비스 없음')
            container = self.json('docker', 'inspect', cid)[0]
            local = self.json('docker', 'image', 'inspect', container['Image'])[0]
            refs = [ref for ref in local.get('RepoDigests', []) if ref.startswith(repo + '@')]
            require(len(refs) == 1, '기존 이미지 registry digest 없음/중복')
            images[service] = self.image(service, valid_digest(refs[0].split('@')[1]), sha, platform,
                                         verified=not without_revision)
        bundle = {'schema': 1, 'origin': 'observed', 'sha': sha, 'platform': platform,
                  'images': images, 'configuration': version, 'execution': execution,
                  'created_at': timestamp()}
        if without_revision:
            # SHA는 운영자 근거(.env·배포 기록)로만 확인했다. digest·이미지 ID·플랫폼은 그대로 대조했다.
            bundle['revision_verified'] = False
        resolved = copy.deepcopy(config)
        for service, image in images.items():
            resolved['services'][service].update(image=image['repository'] + '@' + image['index'],
                                                 platform=platform_name(platform), pull_policy='never')
        bundle['compose_digest'] = digest(encoded(resolved))
        # write_new는 encoded와 같은 직렬화를 사용한다.
        bundle_id = digest(encoded(bundle)).split(':')[1]
        folder = self.store / bundle_id
        folder.mkdir(parents=True, mode=0o700)
        sync_directory(self.root)
        write_new(folder / 'compose.json', resolved)
        # 저장 직렬화 계약을 ID에 묶는다.
        require(digest((folder / 'compose.json').read_bytes()) == bundle['compose_digest'],
                'Compose 직렬화 불일치')
        write_new(folder / 'bundle.json', bundle)
        sync_directory(self.store)
        with self.operation(folder, bundle_id, execution,
                            {'approver': operator, 'evidence': evidence}, kind='registration') as (result, stage):
            self.local_images(bundle, pull=False)
            self.observed_runtime(bundle, folder)
            result['database'] = self.database_state()
            result['scope'] = 'observed-images-and-health; restore-not-verified'
            stage('completed')
        write_atomic(self.store / 'current.json', {'bundle_id': bundle_id, 'sha': sha})
        return {'bundle_id': bundle_id, 'execution': execution, 'status': 'registered',
                'restore_verified': False, 'revision_verified': not without_revision}

    def observed_runtime(self, bundle, folder):
        # 최초 등록 전에는 실행 ref가 SHA 태그일 수 있다. 실행 이미지 ID는 반드시 맞아야 한다.
        ids = self.image_ids(bundle)
        for service in REPOS:
            cid = self.compose('ps', '-q', service, file=folder / 'compose.json').decode().strip()
            require(bool(cid), '실행 컨테이너 없음')
            value = self.json('docker', 'inspect', cid)[0]
            require(value['Image'] == ids[service] and value['State']['Running'], '실행 이미지 불일치')
        self.health(folder)

    def backup_database(self, folder, result, stage):
        current, _, _ = self.confirmed_current()
        before = self.database_state()
        record = {'status': 'running', 'previous_bundle': current, 'database': before,
                  'started_at': timestamp(), 'restore_verified': False}
        result['backup'] = record
        stage('backing_up')
        destination = folder / 'database.sql'
        # SQL은 메모리/로그로 반환하지 않고 root 전용 파일에 직접 쓴다.
        with destination.open('xb') as output:
            os.fchmod(output.fileno(), 0o600)
            args = ('docker', 'compose', '--project-name', 'donworry', '--project-directory',
                    str(self.root), '--env-file', str(self.root / '.env'), '-f',
                    str(self.root / 'compose.yaml'), 'exec', '-T', 'db', 'sh', '-c',
                    'export MYSQL_PWD="$DB_MIGRATE_PASSWORD"; exec mariadb-dump -udw_migrate '
                    '--single-transaction --quick --triggers --hex-blob "$MARIADB_DATABASE"')
            self.command(*args, output_path=output)
            output.flush()
            os.fsync(output.fileno())
        require(destination.stat().st_size > 0, '빈 백업: 배포 중단')
        require(self.database_state()['fingerprint'] == before['fingerprint'],
                '백업 중 DB 스키마/Flyway 변경: 배포 중단')
        hasher = hashlib.sha256()
        with destination.open('rb') as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                hasher.update(block)
        sync_directory(folder)
        record.update(status='success', file='database.sql', bytes=destination.stat().st_size,
                      digest='sha256:' + hasher.hexdigest(), finished_at=timestamp())
        stage('backed_up')

    def functional_policy(self):
        # 검사 내용·데이터는 운영자가 승인한다. 저장소는 임의 결제 시나리오를 만들지 않는다.
        require((self.root / 'functional-check.json').is_file(), '승인된 기능 검사 정책 없음')
        policy = json.loads((self.root / 'functional-check.json').read_bytes())
        require(policy['environment'] == 'approved-test', '승인된 테스트 환경에서만 기능 검사 허용')
        token(policy['evidence'])
        token(policy['test_data'])
        require((self.root / 'functional_check.py').is_file(), '승인된 기능 검사 구현 없음')
        return policy

    def deploy_prerequisites(self):
        # 실행 기록(operation)을 만들기 전에 확인한다. 서비스를 바꾸지 않은 사전 조건 누락이
        # failed·PAUSE_CD로 남으면 reconcile/register-current로도 풀 수 없다.
        self.functional_policy()
        self.confirmed_current()

    def verify_backup(self, result):
        require(result['backup']['status'] == 'success' and
                self.database_state()['fingerprint'] == result['backup']['database']['fingerprint'],
                '백업 후 DB 변경: 새 배포 금지')

    def functional_check(self, bundle, result, stage):
        policy = self.functional_policy()
        require(self.configuration()[1] == bundle['configuration'], '기능 검사 승인 구성 변경')
        stage('functional_check')
        self.command('python3', str(self.root / 'functional_check.py'))
        result['functional_check'] = {'status': 'success', 'evidence': policy['evidence'],
                                      'test_data': policy['test_data'], 'checked_at': timestamp()}

    @serialized('prepare')
    def prepare(self, sha, execution):
        require(re.fullmatch(r'[0-9a-f]{40}', sha), 'TARGET_SHA 형식 오류')
        token(execution)
        self.preflight()
        self.deploy_prerequisites()
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
                    revision_ok(local['Config'].get('Labels'), bundle['sha'],
                                bundle.get('revision_verified', True)),
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
            if result.get('backup', {}).get('status') == 'running':
                result['backup'].update(status='failed', finished_at=timestamp())
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
        self.deploy_prerequisites()
        with self.operation(folder, bundle_id, execution, approval) as (result, stage):
            before = image_tag((self.root / '.env').read_bytes())[0]
            result['deployment_records'] = {
                'before': before, 'intended': 'sha-' + bundle['sha'], 'after': before,
                'files': {'.env': 'unchanged', 'backups/deployed-image-tag': 'unchanged'}}
            stage('validating')
            self.functional_policy()
            self.verify(bundle)
            stage('pulling')
            self.local_images(bundle, pull=True)
            stage('final_validation')
            self.backup_database(folder, result, stage)
            stage('final_validation')
            self.load(bundle_id, execution)
            self.preflight(exclude=bundle_id)
            self.verify(bundle)
            self.local_images(bundle, pull=False)
            self.compose('config', '--quiet', file=folder / 'compose.json')
            self.verify_backup(result)
            self.main(bundle['sha'])
            stage('applying')
            # DB는 기존 healthy 인스턴스를 그대로 사용한다. 자동 재시도하지 않는다.
            self.compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                         '--wait', '--wait-timeout', '240', 'backend', 'ai', 'nginx',
                         file=folder / 'compose.json')
            stage('checking')
            self.runtime(bundle, folder)
            self.functional_check(bundle, result, stage)
            self.publish_deployment(bundle, result, stage)
            stage('publishing')
            write_atomic(self.store / 'current.json', {'bundle_id': bundle_id, 'sha': bundle['sha']})
            stage('completed')
        return result

    @serialized('approve-recovery')
    def approve_recovery(self, bundle_id, execution, operator, evidence):
        folder, bundle = self.load_snapshot(bundle_id, execution)
        failed = json.loads((folder / 'result.json').read_bytes())
        require(failed['status'] != 'success', '성공 배포는 복구 대상이 아님')
        require(self.unresolved() == [bundle_id], '다른 미완료 실행 확인 필요')
        backup = failed.get('backup')
        require(backup is not None and backup.get('status') == 'success',
                '성공한 배포 전 백업 없음: 백업 전 실패는 서비스 변경 없음, inspect/reconcile로 확인')
        previous = backup['previous_bundle']
        require(previous != bundle_id, '복구 대상 오류')
        old = json.loads((self.store / previous / 'bundle.json').read_bytes())
        old_folder, old = self.load_snapshot(previous, old['execution'])
        require(json.loads((old_folder / 'result.json').read_bytes())['status'] == 'success',
                '이전 성공 기록 없음')
        require(self.database_state()['fingerprint'] == backup['database']['fingerprint'],
                'DB 변경/호환성 불명: 이미지 복구 금지, 운영자 판단 필요')
        record = {'failed_bundle': bundle_id, 'execution': execution, 'previous_bundle': previous,
                  'result_digest': digest((folder / 'result.json').read_bytes()),
                  'operator': token(operator), 'evidence': token(evidence),
                  'approved_at': timestamp(), 'scope': 'images-only; no-database-restore',
                  'previous_revision_verified': old.get('revision_verified', True)}
        write_new(folder / 'recovery-approval.json', record)
        return record

    @serialized('recover')
    def recover(self, bundle_id, execution):
        require((self.root / 'ENABLE_UPDATE_CD').is_file(), '실제 CD 활성화 전')
        folder, failed_bundle = self.load_snapshot(bundle_id, execution)
        failed = json.loads((folder / 'result.json').read_bytes())
        approval = json.loads((folder / 'recovery-approval.json').read_bytes())
        require(approval['failed_bundle'] == bundle_id and approval['execution'] == execution and
                approval['result_digest'] == digest((folder / 'result.json').read_bytes()),
                '복구 승인과 중단 기록 불일치')
        require(self.unresolved() in ([bundle_id], []), '다른 미완료 실행 확인 필요')
        previous = approval['previous_bundle']
        require(previous == failed['backup']['previous_bundle'], '승인 복구 대상 불일치')
        old = json.loads((self.store / previous / 'bundle.json').read_bytes())
        old_folder, old = self.load_snapshot(previous, old['execution'])
        require(json.loads((old_folder / 'result.json').read_bytes())['status'] == 'success',
                '이전 성공 기록 없음')
        attempt = folder / 'recovery-attempt.json'
        current = json.loads((self.store / 'current.json').read_bytes())['bundle_id']
        recovery_attempt = json.loads(attempt.read_bytes())['bundle_id'] if attempt.exists() else None
        require(current in {previous, bundle_id, recovery_attempt}, '현재 배포 변경: 오래된 복구 실행 거절')
        if attempt.exists():
            recovery_id = recovery_attempt
            recovered = json.loads((self.store / recovery_id / 'bundle.json').read_bytes())
            destination, recovered = self.load_snapshot(recovery_id, recovered['execution'])
            require(recovered['recovery_approval'] == digest(encoded(approval)), '다른 복구 승인')
            self.finish_recovery(folder, approval, recovered, recovery_id, destination, failed)
            return {'bundle_id': recovery_id, 'execution': recovered['execution'], 'status': 'success',
                    'database_restored': False}
        configuration = self.configuration()[1]
        require({k: v for k, v in configuration['inputs'].items() if k != '.env'} ==
                {k: v for k, v in old['configuration']['inputs'].items() if k != '.env'},
                '기존 Compose/bind/명령 변경: 복구 금지')
        env = (self.root / '.env').read_bytes()
        _, (start, end) = image_tag(env)
        intended_env = env[:start] + ('sha-' + old['sha']).encode() + env[end:]
        expected = self.planned_configuration(intended_env, old)
        old_result = json.loads((old_folder / 'result.json').read_bytes())
        require(expected in [old['configuration'], old_result.get('published_configuration')],
                '기존 환경/설정과 불일치: 복구 금지')
        recovery = {**old, 'configuration': configuration,
                    'execution': 'recovery-' + digest(encoded(approval)).split(':')[1][:32],
                    'created_at': timestamp(),
                    'recovery_of': bundle_id, 'recovery_approval': digest(encoded(approval))}
        recovery_id = digest(encoded(recovery)).split(':')[1]
        # 승인 1회당 복구도 1회만 시도한다. 새 시각으로 다른 실행을 만들 수 없다.
        write_new(folder / 'recovery-attempt.json', {'bundle_id': recovery_id})
        destination = self.store / recovery_id
        destination.mkdir(mode=0o700)
        write_new(destination / 'bundle.json', recovery)
        write_new(destination / 'compose.json', json.loads((old_folder / 'compose.json').read_bytes()))
        write_new(destination / 'approval.json', approval)
        (self.root / 'PAUSE_CD').touch(mode=0o600, exist_ok=True)
        sync_directory(self.root)
        with self.operation(destination, recovery_id, recovery['execution'], approval,
                            kind='recovery') as (result, stage):
            self.verify(old)
            stage('pulling')
            self.local_images(old, pull=True)
            require(self.database_state()['fingerprint'] == failed['backup']['database']['fingerprint'],
                    'DB 변경/호환성 불명: 복구 중단')
            require(self.configuration()[1] == configuration, '복구 승인 후 구성 변경')
            self.local_images(old, pull=False)
            self.compose('config', '--quiet', file=destination / 'compose.json')
            stage('applying')
            self.compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                         '--wait', '--wait-timeout', '240', 'backend', 'ai', 'nginx',
                         file=destination / 'compose.json')
            stage('checking')
            self.runtime(recovery, destination)
            self.functional_check(recovery, result, stage)
            require(self.database_state()['fingerprint'] == failed['backup']['database']['fingerprint'],
                    '복구 기동 후 DB 변경: 운영자 판단 필요')
            self.publish_deployment(recovery, result, stage)
            stage('completed')
        # 원래 실패 결과를 보존한다. 모든 게시가 끝나기 전에는 PAUSE_CD를 지우지 않는다.
        self.finish_recovery(folder, approval, recovery, recovery_id, destination, failed)
        return {'bundle_id': recovery_id, 'execution': recovery['execution'], 'status': 'success',
                'database_restored': False}

    def finish_recovery(self, folder, approval, recovery, recovery_id, destination, failed):
        result = json.loads((destination / 'result.json').read_bytes())
        require(result['status'] == 'success', '복구 미완료/실패: 운영자 판단 필요, 재적용 금지')
        require(self.unresolved() in ([approval['failed_bundle']], []), '다른 미완료 실행 확인 필요')
        require(self.configuration()[1] == result['published_configuration'], '복구 후 구성 변경')
        require(self.database_state()['fingerprint'] == failed['backup']['database']['fingerprint'],
                '복구 후 DB 변경: 운영자 판단 필요')
        self.runtime(recovery, destination)
        self.local_sha_tags(recovery)
        require(image_tag((self.root / '.env').read_bytes())[0] == 'sha-' + recovery['sha'] and
                (self.root / 'backups/deployed-image-tag').read_text().strip() == 'sha-' + recovery['sha'],
                '복구 후 배포 기록 불일치')
        resolution = {'result_digest': approval['result_digest'],
                  'observed_bundle': recovery_id, 'operator': approval['operator'],
                  'evidence': approval['evidence'], 'checked_at': timestamp(), 'kind': 'recovery'}
        if (folder / 'resolution.json').exists():
            existing = json.loads((folder / 'resolution.json').read_bytes())
            require(all(existing[k] == v for k, v in resolution.items() if k != 'checked_at'),
                    '기존 복구 해제 기록 불일치')
        else:
            write_new(folder / 'resolution.json', resolution)
        write_atomic(self.store / 'current.json', {'bundle_id': recovery_id, 'sha': recovery['sha']})
        (self.root / 'PAUSE_CD').unlink(missing_ok=True)
        sync_directory(self.root)

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
        if bundle.get('origin') == 'observed':
            self.observed_runtime(bundle, folder_expected)
        else:
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
    parser.add_argument('action', choices=['prepare', 'approve', 'apply', 'inspect', 'reconcile',
                                         'register-current', 'approve-recovery', 'recover'])
    parser.add_argument('target', help='prepare/register-current: SHA, 그 외: bundle ID')
    parser.add_argument('execution', help='Jenkins 또는 수동 실행 식별자')
    parser.add_argument('approver', nargs='?')
    parser.add_argument('--against', choices=['current', 'target'])
    parser.add_argument('--without-revision', action='store_true',
                        help='register-current 전용: #480 이전(revision label 없는) 이미지 등록')
    parser.add_argument('--evidence', help='작업 종료·DB/Flyway·업무 영향 확인 근거 식별자 (비밀값 금지)')
    args = parser.parse_args()
    require(not args.without_revision or args.action == 'register-current',
            '--without-revision은 register-current 전용')
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
    elif args.action == 'register-current':
        require(args.approver is not None and args.evidence is not None, '운영자/근거 누락')
        result = release.register_current(args.target, args.execution, args.approver, args.evidence,
                                          without_revision=args.without_revision)
    elif args.action == 'approve-recovery':
        require(args.approver is not None and args.evidence is not None, '운영자/근거 누락')
        result = release.approve_recovery(args.target, args.execution, args.approver, args.evidence)
    elif args.action == 'recover':
        result = release.recover(args.target, args.execution)
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
