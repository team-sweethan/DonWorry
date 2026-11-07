import tempfile
import unittest
from pathlib import Path

import seed_demo


class SeedDemoSafetyTest(unittest.TestCase):
    # 운영 · 배포 서버 주소면 직원 · 결제를 만들기 전에 멈춘다
    def test_checkTarget_nonLocalHost_refuses(self):
        for url in ["https://donworry.example.com", "http://10.0.0.5:8081", "http://localhost.example.com"]:
            with self.subTest(url=url), self.assertRaises(seed_demo.SeedStop):
                seed_demo.check_target(url)
        for url in ["http://localhost:8081", "http://127.0.0.1:8081"]:
            with self.subTest(url=url):
                seed_demo.check_target(url)

    # ai-server 제공자가 openai면 허락 표시 없이는 멈춘다 — 환경변수가 .env보다 먼저다
    def test_checkAiProvider_openaiWithoutFlag_stops(self):
        with tempfile.TemporaryDirectory() as folder:
            env_file = Path(folder) / ".env"
            env_file.write_text(
                "# AI_SERVER_LLM_PROVIDER=mock\nAI_SERVER_LLM_PROVIDER=openai\n", encoding="utf-8"
            )
            with self.assertRaises(seed_demo.SeedStop):
                seed_demo.check_ai_provider(env_file, {}, allow_openai=False)
            with self.assertRaises(seed_demo.SeedStop):
                seed_demo.check_ai_provider(Path(folder) / "none", {"AI_SERVER_LLM_PROVIDER": "OpenAI"}, False)
            self.assertEqual(seed_demo.check_ai_provider(env_file, {}, allow_openai=True), "openai")

            env_file.write_text("AI_SERVER_LLM_PROVIDER=mock\n# AI_SERVER_LLM_PROVIDER=openai\n", encoding="utf-8")
            self.assertEqual(seed_demo.check_ai_provider(env_file, {}, allow_openai=False), "mock")


    # --reset이 지우는 표는 모두 donworry_seed 안이다 — 표 이름에 다른 DB를 섞어도 시드 DB 밖으로 나가지 않는다
    def test_resetSql_onlyTouchesSeedDb(self):
        sql = seed_demo.reset_sql(["payments", "security_cases", "donworry_dev`.`payments"])
        self.assertEqual(seed_demo.SEED_DB, "donworry_seed")
        drop = sql.split("DROP TABLE ", 1)[1].split(";", 1)[0]
        self.assertEqual(
            drop,
            "`donworry_seed`.`payments`, `donworry_seed`.`security_cases`, "
            "`donworry_seed`.`donworry_dev``.``payments`",
        )
        self.assertNotIn("DROP DATABASE", sql.upper())
        self.assertEqual(seed_demo.reset_sql([]), "")


if __name__ == "__main__":
    unittest.main()
