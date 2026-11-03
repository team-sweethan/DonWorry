import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("check-release-target.sh").resolve()
CURRENT = "a" * 40
NEWER = "b" * 40


class ReleaseTargetTests(unittest.TestCase):
    def run_check(self, response, git_status=0, expected=CURRENT):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            git = root / "git"
            git.write_text(
                '#!/bin/sh\n'
                '[ "$*" = "ls-remote --exit-code --refs origin refs/heads/main" ] || exit 99\n'
                'printf "%s" "$FAKE_REMOTE"\n'
                'exit "$FAKE_STATUS"\n'
            )
            git.chmod(0o755)
            output = root / "output"
            result = subprocess.run(
                ["bash", str(SCRIPT)],
                env={
                    **os.environ,
                    "PATH": str(root) + os.pathsep + os.environ["PATH"],
                    "GITHUB_OUTPUT": str(output),
                    "RELEASE_SHA": expected,
                    "FAKE_REMOTE": response,
                    "FAKE_STATUS": str(git_status),
                },
                capture_output=True,
                text=True,
            )
            return result, output.read_text() if output.exists() else ""

    def test_current_head_allows_publication(self):
        result, output = self.run_check(f"{CURRENT}\trefs/heads/main\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output, "current=true\n")

    def test_changed_head_skips_publication(self):
        result, output = self.run_check(f"{NEWER}\trefs/heads/main\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output, "current=false\n")

    def test_lookup_failure_fails_without_success_output(self):
        for status in (2, 128):
            with self.subTest(status=status):
                result, output = self.run_check("", git_status=status)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(output, "")

    def test_invalid_remote_response_fails(self):
        for response in (
            "",
            f"{CURRENT}\trefs/heads/dev",
            "invalid\trefs/heads/main",
            f"{CURRENT}\trefs/heads/main\n{NEWER}\trefs/heads/main",
        ):
            with self.subTest(response=response):
                result, output = self.run_check(response)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(output, "")

    def test_invalid_expected_sha_fails(self):
        result, output = self.run_check(f"{CURRENT}\trefs/heads/main", expected="invalid")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output, "")


if __name__ == "__main__":
    unittest.main()
