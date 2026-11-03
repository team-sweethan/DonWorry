# 저장소 안 Markdown의 상대 링크가 실제 파일·폴더를 가리키는지 검사 (외부 URL은 보지 않음)
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SKIP = {"node_modules", ".venv", "build", "dist", ".git", ".gradle"}
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")

broken = []
for md in ROOT.rglob("*.md"):
    if SKIP & set(md.relative_to(ROOT).parts):
        continue
    text = re.sub(r"```.*?```", "", md.read_text(encoding="utf-8"), flags=re.S)  # 코드 블록 제외
    for target in LINK.findall(text):
        if re.match(r"^(https?:|mailto:|#)", target):
            continue
        path = target.split("#")[0]
        if not (md.parent / path).exists():
            broken.append(f"{md.relative_to(ROOT)} → {target}")

if broken:
    print("깨진 링크:")
    print("\n".join(f"  {b}" for b in broken))
    sys.exit(1)
print("Markdown 상대 링크 이상 없음")
