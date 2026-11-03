import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("publish-release-marker.sh").resolve()
CHECK = SCRIPT.with_name("check-release-target.sh")
CURRENT = "a" * 40
NEWER = "b" * 40
DIGEST = "sha256:" + "c" * 64
REPOSITORY = "ghcr.io/team-sweethan/donworry-release"


class ReleasePublicationTests(unittest.TestCase):
    def run_publication(self, head=CURRENT, git_status=0, docker_status=0,
                        digest=DIGEST, earlier_check=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            calls = root / "calls"
            output = root / "output"
            git = root / "git"
            git.write_text(
                '#!/bin/sh\n'
                'echo git >> "$CALLS"\n'
                '[ "$*" = "ls-remote --exit-code --refs origin refs/heads/main" ] || exit 99\n'
                'printf "%s" "$FAKE_REMOTE"\n'
                'exit "$FAKE_GIT_STATUS"\n'
            )
            docker = root / "docker"
            docker.write_text(
                '#!/bin/sh\n'
                'printf "docker" >> "$CALLS"\n'
                'printf " <%s>" "$@" >> "$CALLS"\n'
                'printf "\\n" >> "$CALLS"\n'
                'exit "$FAKE_DOCKER_STATUS"\n'
            )
            git.chmod(0o755)
            docker.chmod(0o755)
            env = {
                **os.environ,
                "PATH": str(root) + os.pathsep + os.environ["PATH"],
                "GITHUB_OUTPUT": str(output),
                "RELEASE_SHA": CURRENT,
                "MARKER_DIGEST": digest,
                "CALLS": str(calls),
                "FAKE_REMOTE": f"{head}\trefs/heads/main\n",
                "FAKE_GIT_STATUS": str(git_status),
                "FAKE_DOCKER_STATUS": str(docker_status),
            }
            if earlier_check:
                previous = subprocess.run(
                    ["bash", str(CHECK)],
                    env={**env, "FAKE_REMOTE": f"{CURRENT}\trefs/heads/main\n"},
                    capture_output=True, text=True,
                )
                self.assertEqual(previous.returncode, 0, previous.stderr)
                self.assertEqual(output.read_text(), "current=true\n")
                output.write_text("")
                calls.write_text("")
            result = subprocess.run(
                ["bash", str(SCRIPT)], env=env, capture_output=True, text=True,
            )
            return (
                result,
                calls.read_text() if calls.exists() else "",
                output.read_text() if output.exists() else "",
            )

    def test_current_head_publishes_prepared_digest_after_lookup(self):
        result, calls, output = self.run_publication()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            calls,
            "git\ndocker <buildx> <imagetools> <create> <--prefer-index=false> "
            f"<--tag> <{REPOSITORY}:prod> <{REPOSITORY}@{DIGEST}>\n",
        )
        self.assertIn("published=true\n", output)

    def test_main_changed_after_earlier_check_skips_publication(self):
        result, calls, output = self.run_publication(head=NEWER, earlier_check=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, "git\n")
        self.assertIn("current=false\n", output)
        self.assertNotIn("published=true", output)

    def test_lookup_failure_never_publishes(self):
        result, calls, output = self.run_publication(git_status=128)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, "git\n")
        self.assertNotIn("published=true", output)

    def test_publish_failure_fails_without_success_output(self):
        result, calls, output = self.run_publication(docker_status=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("docker", calls)
        self.assertNotIn("published=true", output)

    def test_invalid_digest_never_publishes(self):
        for digest in ("", "sha256:abc", "c" * 64, DIGEST + "\n", "--help"):
            with self.subTest(digest=digest):
                result, calls, output = self.run_publication(digest=digest)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("docker", calls)
                self.assertNotIn("published=true", output)


if __name__ == "__main__":
    unittest.main()
