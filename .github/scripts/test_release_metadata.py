import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("prepare-release-metadata.sh").resolve()
CURRENT = "a" * 40
DIGESTS = {
    "BACKEND_DIGEST": "sha256:" + "b" * 64,
    "FRONTEND_DIGEST": "sha256:" + "c" * 64,
    "AI_DIGEST": "sha256:" + "d" * 64,
}


class ReleaseMetadataTests(unittest.TestCase):
    def run_metadata(self, changes=None):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            env = {
                **os.environ,
                "GITHUB_OUTPUT": str(output),
                "RELEASE_SHA": CURRENT,
                **DIGESTS,
            }
            for key, value in (changes or {}).items():
                if value is None:
                    env.pop(key, None)
                else:
                    env[key] = value
            result = subprocess.run(
                ["bash", str(SCRIPT)], env=env, capture_output=True, text=True,
            )
            return result, output.read_text() if output.exists() else ""

    # 서로 다른 서비스 digest가 바뀌거나 누락되지 않고 같은 묶음으로 전달된다.
    def test_metadata_whenValid_recordsCompleteBundle(self):
        result, output = self.run_metadata()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output.splitlines(), [
            "labels<<DONWORRY_RELEASE_LABELS",
            f"org.opencontainers.image.revision={CURRENT}",
            "io.donworry.release.schema-version=1",
            f"io.donworry.release.backend.digest={DIGESTS['BACKEND_DIGEST']}",
            f"io.donworry.release.frontend.digest={DIGESTS['FRONTEND_DIGEST']}",
            f"io.donworry.release.ai.digest={DIGESTS['AI_DIGEST']}",
            "DONWORRY_RELEASE_LABELS",
        ])

    # 마지막 값이 빠져도 앞서 검증한 서비스의 부분 출력이 남지 않는다.
    def test_metadata_whenDigestMissing_failsWithoutOutput(self):
        for variable in DIGESTS:
            for value in (None, ""):
                with self.subTest(variable=variable, value=value):
                    result, output = self.run_metadata({variable: value})
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(output, "")

    # 공백·개행과 output 삽입 시도도 marker 발행 전에 차단한다.
    def test_metadata_whenDigestMalformed_failsWithoutOutput(self):
        for variable, valid in DIGESTS.items():
            for value in (
                "sha256:abc", "b" * 64, "sha512:" + "b" * 64,
                "sha256:" + "B" * 64, valid + "0", valid + "\n",
                " " + valid, valid + "\nDONWORRY_RELEASE_LABELS\ninjected=true",
            ):
                with self.subTest(variable=variable, value=value):
                    result, output = self.run_metadata({variable: value})
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(output, "")

    # SHA 태그 문자열이나 축약 SHA는 revision으로 받아들이지 않는다.
    def test_metadata_whenShaInvalid_failsWithoutOutput(self):
        for value in (None, "", "a" * 7, "A" * 40, "sha-" + CURRENT,
                      CURRENT + "\n", CURRENT + "0"):
            with self.subTest(value=value):
                result, output = self.run_metadata({"RELEASE_SHA": value})
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(output, "")

    # 동일 커밋 재빌드에서도 변경된 digest를 사용하며 과거 값을 재사용하지 않는다.
    def test_metadata_whenSameShaRebuilt_recordsNewDigest(self):
        rebuilt = "sha256:" + "e" * 64
        result, output = self.run_metadata({"AI_DIGEST": rebuilt})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"org.opencontainers.image.revision={CURRENT}\n", output)
        self.assertIn(f"io.donworry.release.ai.digest={rebuilt}\n", output)
        self.assertNotIn(DIGESTS["AI_DIGEST"], output)


if __name__ == "__main__":
    unittest.main()
