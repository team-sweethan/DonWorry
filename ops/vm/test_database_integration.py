"""옵트인: 새 로컬 MariaDB 컨테이너만 사용한다. 운영 DB에 연결하지 않는다."""

import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest import mock
import uuid

import test_release as fixtures

release = fixtures.release


@unittest.skipUnless(os.environ.get('DONWORRY_DB_TEST') == '1', '로컬 Docker DB 검사 명시 필요')
class DatabaseIntegrationTests(unittest.TestCase):
    def docker(self, *args, **kwargs):
        return subprocess.run(['docker', *args], check=True, capture_output=True,
                              timeout=60, **kwargs).stdout

    def test_private_dump_restores_fixture_and_schema_change_is_detected(self):
        name = 'donworry-483-test-' + uuid.uuid4().hex
        self.docker('run', '-d', '--name', name, '--network', 'none',
                    '--health-cmd', 'healthcheck.sh --connect --innodb_initialized', '--health-interval', '1s',
                    '-e', 'MARIADB_ROOT_PASSWORD=local-fixture-only',
                    '-e', 'MARIADB_DATABASE=donworry',
                    '-e', 'DB_MIGRATE_PASSWORD=local-fixture-only', 'mariadb:11.8')
        self.addCleanup(lambda: self.docker('rm', '-fv', name))
        deadline = time.monotonic() + 60
        while True:
            try:
                self.docker('exec', name, 'healthcheck.sh', '--connect', '--innodb_initialized')
                if self.docker('inspect', '--format', '{{.State.Health.Status}}', name).strip() == b'healthy':
                    break
            except subprocess.CalledProcessError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(1)
            time.sleep(0.1)
        def sql(statement, database='donworry', data=None):
            return self.docker('exec', '-i', '-e', 'MYSQL_PWD=local-fixture-only', name,
                               'mariadb', '-uroot', '--batch', '--skip-column-names', database,
                               *(['-e', statement] if statement else []), input=data)
        sql("""CREATE USER 'dw_migrate'@'localhost' IDENTIFIED BY 'local-fixture-only';
GRANT ALL ON donworry.* TO 'dw_migrate'@'localhost';
CREATE TABLE flyway_schema_history (installed_rank INT, version VARCHAR(50), type VARCHAR(20),
 script VARCHAR(200), checksum INT, success BOOL) ENGINE=InnoDB;
INSERT INTO flyway_schema_history VALUES (1,'1','SQL','V1__fixture.sql',123,1);
CREATE TABLE fixture (id BIGINT PRIMARY KEY, amount BIGINT CHECK (amount >= 0)) ENGINE=InnoDB;
INSERT INTO fixture VALUES (1,42);
CREATE DATABASE donworry_restored;
""")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / 'releases' / ('a' * 64)
            folder.mkdir(parents=True)
            def command(*args, **kwargs):
                if args[:2] == ('docker', 'inspect'):
                    return release.run('docker', 'inspect', name)
                if 'ps' in args:
                    return name.encode()
                index = args.index('exec')
                return release.run('docker', 'exec', '-i', name, *args[index + 3:], **kwargs)
            cd = release.Release(root, command)
            with cd.exclusive('integration'):
                before = cd.database_state()
                with mock.patch.object(cd, 'confirmed_current', return_value=('previous-fixture', None, None)):
                    with cd.operation(folder, 'a' * 64, 'fixture', {}) as (result, stage):
                        cd.backup_database(folder, result, stage)
                self.assertEqual(result['backup']['status'], 'success')
                self.assertFalse(result['backup']['restore_verified'])
                dump = folder / 'database.sql'
                self.assertEqual(dump.stat().st_mode & 0o777, 0o600)
                sql(None, 'donworry_restored', dump.read_bytes())
                self.assertEqual(sql('SELECT amount FROM fixture', 'donworry_restored').strip(), b'42')
                self.assertEqual(sql('SELECT success FROM flyway_schema_history', 'donworry_restored').strip(), b'1')
                sql('ALTER TABLE fixture ADD COLUMN note VARCHAR(20)')
                self.assertNotEqual(before['fingerprint'], cd.database_state()['fingerprint'])


if __name__ == '__main__':
    unittest.main()
