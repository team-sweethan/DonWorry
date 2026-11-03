#!/usr/bin/env python3
"""VM1 release candidate / approval / update. Requires Python 3.10+, Docker Buildx."""

import argparse
import copy
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path('/srv/donworry')
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


def platform_name(platform):
    return platform['os'] + '/' + platform['architecture']


def run(*args):
    # Compose 출력/오류에 환경값이 포함될 수 있어 명령 출력은 로그에 재전달하지 않는다.
    try:
        return subprocess.run(args, check=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=600).stdout
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as exc:
        raise Rejected(f'외부 명령 실패: {args[0]} (상세 출력은 비밀값 보호를 위해 생략)') from exc


def write_new(path, value):
    with path.open('xb') as stream:
        stream.write(encoded(value))
        stream.flush()
        os.fsync(stream.fileno())


class Release:
    def __init__(self, root=ROOT, command=run, sleep=time.sleep):
        self.root = root
        self.command = command
        self.sleep = sleep
        self.store = root / 'releases'

    def json(self, *args):
        return json.loads(self.command(*args))

    def compose(self, *args, file=None):
        return self.command('docker', 'compose', '--project-name', 'donworry',
                            '--project-directory', str(self.root), '--env-file',
                            str(self.root / '.env'), '-f', str(file or self.root / 'compose.yaml'),
                            *args)

    def main(self, sha):
        actual = self.command('git', 'ls-remote', '--exit-code', '--refs', GIT,
                              'refs/heads/main').decode().rstrip('\n')
        require(actual == f'{sha}\trefs/heads/main', '승인 SHA와 현재 main HEAD 불일치')

    def preflight(self):
        require(not (self.root / 'PAUSE_CD').exists() and
                not (self.root / 'PAUSE_CD').is_symlink(), 'PAUSE_CD: 수동 확인 필요')
        for path in self.store.glob('*/result.json'):
            require(json.loads(path.read_bytes()).get('status') == 'success',
                    '실패/미완료 업데이트 기록: 수동 확인 필요')
        require((self.root / 'backups/deployed-image-tag').is_file(),
                '기존 성공 배포 기록 없음: 최초 배포는 이 명령의 대상이 아님')
        previous = (self.root / 'backups/deployed-image-tag').read_text().strip()
        require(re.fullmatch(r'sha-[0-9a-f]{40}', previous), '기존 성공 배포 기록 오류')
        tags = re.findall(r'^IMAGE_TAG=(.*)$', (self.root / '.env').read_text(), re.MULTILINE)
        require(tags == [previous], '기존 IMAGE_TAG와 최초 성공 배포 기록 불일치')
        cid = self.compose('ps', '-q', 'db').decode().strip()
        require(bool(cid), '기존 DB 없음')
        db = self.json('docker', 'inspect', cid)[0]
        require(db['State'].get('Health', {}).get('Status') == 'healthy', '기존 DB 비정상')

    def platform(self):
        info = self.json('docker', 'info', '--format', '{{json .}}')
        arch = {'x86_64': 'amd64', 'aarch64': 'arm64'}.get(info['Architecture'], info['Architecture'])
        require(info['OSType'] == 'linux' and arch in {'amd64', 'arm64'}, '미지원 VM 플랫폼')
        return {'os': 'linux', 'architecture': arch}

    def configuration(self):
        config = json.loads(self.compose('config', '--format', 'json'))
        require(set(config['services']) == {'db', *REPOS}, '예상하지 않은 Compose 서비스')
        inputs = {name: digest((self.root / name).read_bytes()) for name in ['.env', 'compose.yaml']}
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
        return {'bundle_id': final_id, **bundle}

    def load(self, bundle_id, execution):
        require(re.fullmatch(r'[0-9a-f]{64}', bundle_id), '묶음 ID 형식 오류')
        folder = self.store / bundle_id
        bundle = json.loads((folder / 'bundle.json').read_bytes())
        require(digest(encoded(bundle)) == 'sha256:' + bundle_id, '승인 묶음 변조')
        require(bundle['execution'] == token(execution), 'Jenkins 실행 ID 불일치')
        require(digest((folder / 'compose.json').read_bytes()) == bundle['compose_digest'],
                '저장된 Compose 변경: 새 승인 필요')
        require(self.configuration()[1] == bundle['configuration'], '구성 변경: 새 준비·승인 필요')
        return folder, bundle

    def approve(self, bundle_id, execution, approver):
        folder, bundle = self.load(bundle_id, execution)
        require(not (folder / 'result.json').exists(), '이미 적용을 시도한 승인')
        write_new(folder / 'approval.json', {'bundle_id': bundle_id, 'execution': execution,
                                           'approver': token(approver), 'approved_at': timestamp()})
        return {'bundle_id': bundle_id, 'approved': True}

    def local_images(self, bundle, pull):
        for image in bundle['images'].values():
            ref = f"{image['repository']}@{image['index']}"
            if pull:
                self.command('docker', 'pull', '--platform', platform_name(bundle['platform']), ref)
            local = self.json('docker', 'image', 'inspect', ref)[0]
            require(local['Id'] == image['config'] and
                    local['Os'] == bundle['platform']['os'] and
                    local['Architecture'] == bundle['platform']['architecture'] and
                    local['Config'].get('Labels', {}).get(REVISION) == bundle['sha'],
                    '로컬 이미지 digest/platform/revision 불일치')

    def apply(self, bundle_id, execution):
        require((self.root / 'ENABLE_UPDATE_CD').is_file(), '실제 CD 활성화 전: 운영 검증·승인 필요')
        folder, bundle = self.load(bundle_id, execution)
        approval = json.loads((folder / 'approval.json').read_bytes())
        require(approval['bundle_id'] == bundle_id and approval['execution'] == execution,
                '승인 기록 불일치')
        require(not (folder / 'result.json').exists(), '이미 적용을 시도한 승인')
        self.preflight()
        self.verify(bundle)
        self.local_images(bundle, pull=True)
        # 다운로드 등 준비 작업 이후 구성/이미지/기존 서비스 상태를 다시 확인한다.
        self.load(bundle_id, execution)
        self.preflight()
        self.local_images(bundle, pull=False)
        self.compose('config', '--quiet', file=folder / 'compose.json')
        self.main(bundle['sha'])
        result = {'bundle_id': bundle_id, 'execution': execution, 'approval': approval,
                  'started_at': timestamp(), 'status': 'applying'}
        write_new(folder / 'result.json', result)
        try:
            # DB는 기존 healthy 인스턴스를 그대로 사용한다.
            self.compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                         '--wait', '--wait-timeout', '240', 'backend', 'ai', 'nginx',
                         file=folder / 'compose.json')
            self.runtime(bundle, folder)
            result['status'] = 'success'
            current = self.root / 'releases/current.json'
            temp = current.with_suffix('.tmp')
            temp.write_bytes(encoded({'bundle_id': bundle_id, 'sha': bundle['sha']}))
            temp.replace(current)
        except BaseException:
            result['status'] = 'failed'
            (self.root / 'PAUSE_CD').touch(mode=0o600, exist_ok=True)
            raise
        finally:
            result['finished_at'] = timestamp()
            (folder / 'result.json').write_bytes(encoded(result))
        return result

    def runtime(self, bundle, folder):
        for service, image in bundle['images'].items():
            cid = self.compose('ps', '-q', service, file=folder / 'compose.json').decode().strip()
            require(bool(cid), '실행 컨테이너 없음')
            container = self.json('docker', 'inspect', cid)[0]
            require(container['Image'] == image['config'] and container['State']['Running'] and
                    container['Config']['Image'] == f"{image['repository']}@{image['index']}",
                    '실행 컨테이너 이미지 불일치')
        # 현재 앱 이미지에 Docker healthcheck가 없어 Compose의 running만으로 준비를 판단할 수 없다.
        for attempt in range(60):
            try:
                self.health(folder)
                return
            except Rejected:
                if attempt == 59:
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
    parser.add_argument('action', choices=['prepare', 'approve', 'apply'])
    parser.add_argument('target', help='prepare: TARGET_SHA, 그 외: bundle ID')
    parser.add_argument('execution', help='Jenkins 실행 식별자')
    parser.add_argument('approver', nargs='?')
    args = parser.parse_args()
    require(os.geteuid() == 0, 'root 전용 명령')
    os.umask(0o077)
    with open('/run/lock/donworry-deploy.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        release = Release()
        if args.action == 'prepare':
            result = release.prepare(args.target, args.execution)
        elif args.action == 'approve':
            require(args.approver is not None, '승인자 누락')
            result = release.approve(args.target, args.execution, args.approver)
        else:
            result = release.apply(args.target, args.execution)
        print(encoded(result).decode())


if __name__ == '__main__':
    try:
        cli()
    except (Rejected, OSError, ValueError, KeyError, TypeError, IndexError) as error:
        # JSON 파싱 오류 등에도 입력 원문/비밀값을 출력하지 않는다.
        print(str(error) if isinstance(error, Rejected) else 'ERROR: 입력/상태 검사 실패', file=sys.stderr)
        sys.exit(1)
