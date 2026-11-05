import re
from typing import Any

from pydantic import BaseModel, ValidationError, field_validator

from app.agent.facts import Fact, topic_keys
from app.agent.prompts import EXAMPLE_SENTENCES

# AI가 쓰는 부분 — 조회한 사실을 주제별로 전달하는 문장 (#548)
# 판단(위험 · 정상 분류, 권고)은 쓰지 않는다. 분류는 기준표(코드), 최종 판정은 보안 직원
# 사실 문장은 근거 사실 번호를 함께 낸다
# 코드는 번호와 숫자만 맞춰 본다 (뜻을 단어로 추측하지 않는다)


def _no_blank(items: list[str]) -> list[str]:
    if any(not item.strip() for item in items):
        raise ValueError("빈 문장이 있다")
    return items


class FactSentence(BaseModel):
    text: str
    facts: list[str]

    @field_validator("text")
    @classmethod
    def _text(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("빈 문장이다")
        return value


# 결론 한 줄 · 권고 요약 · 결제별 이유는 AI가 쓰지 않는다 — 코드가 쓴다 (#532)
# topics: 주제 키(history · linked · signals · similar) → 그 주제의 사실 문장
class Narrative(BaseModel):
    summary: list[FactSentence]
    topics: dict[str, list[FactSentence]]
    additionalChecks: list[str]

    check_lists = field_validator("additionalChecks")(_no_blank)


_TEXT_LIST = {"type": "array", "items": {"type": "string"}}
# 요약 · 확인할 것은 2문장 이상 (#355)
MIN_SENTENCES = 2
_TEXT_LIST_2 = {**_TEXT_LIST, "minItems": MIN_SENTENCES}


def _sentence_list(ids: list[str], min_items: int) -> dict[str, Any]:
    sentence = {
        "type": "object",
        "properties": {
            "text": {"type": "string"},
            # 준 사실 번호만 고르게 한다 — 그래도 검사는 따로 한다
            "facts": {"type": "array", "items": {"type": "string", "enum": ids}, "minItems": 1},
        },
        "required": ["text", "facts"],
    }
    return {"type": "array", "items": sentence, "minItems": min_items}


# 주제 키 · 사실 번호를 고정한 JSON Schema — Ollama format · OpenAI 구조화 출력으로 넘긴다
def narrative_schema(facts: list[Fact]) -> dict[str, Any]:
    keys = topic_keys(facts)
    return {
        "type": "object",
        "properties": {
            "summary": _sentence_list([f.id for f in facts], MIN_SENTENCES),
            "topics": {
                "type": "object",
                "properties": {
                    key: _sentence_list([f.id for f in facts if f.topic == key], 1) for key in keys
                },
                "required": keys,
            },
            "additionalChecks": _TEXT_LIST_2,
        },
        "required": ["summary", "topics", "additionalChecks"],
    }


# 문장에 나오면 안 되는 코드값 — 밑줄이 든 영문 코드(REJECT_REVIEW · HIGH_AMOUNT 등)와 상태 코드
# \b는 한글도 글자로 봐서 "REJECT_REVIEW를"을 놓친다 — 영문 · 숫자 · 밑줄 기준으로만 경계를 본다
_CODE = re.compile(
    r"(?<![A-Za-z0-9_])(?:[A-Z]+(?:_[A-Z]+)+"
    r"|HELD|REVIEW|DENY|DENIED|ALLOW|CAPTURED|VOIDED|PENDING|DECLINED|EXPIRED)(?![A-Za-z0-9_])"
)

# 확률처럼 단정하는 표현 — FDS 점수는 확률이 아니고 판정은 직원이 한다 (fds 정본 기획 2.6)
_ASSERTIVE = re.compile(
    r"확률|%|가능성이\s*(?:매우\s*)?(?:높|크)|사기(?:로|임이|가)\s*(?:판단|확실|분명|확정|추정)"
)

# 숫자와 뒤 단위 — "65만 원"은 650000으로 읽는다
_NUMBER = re.compile(r"(\d[\d,]*)(\s*만)?\s*(건|회|번|개|명|분|시간|일|차)?")

# 한자 · 일본 문자 — Qwen이 "新規 카드"처럼 섞어 쓴다
_HANJA = re.compile(r"[぀-ヿ㐀-䶿一-鿿豈-﫿]+")

# 영어 단어 — 화면에서 쓰는 약어 · 시연 표기와, 인용한 사실에 있는 말(국가 코드 등)만 허용한다
_ENGLISH = re.compile(r"(?<![A-Za-z])[A-Za-z]{2,}(?![A-Za-z])")
ALLOWED_WORDS = frozenset({"FDS", "IP", "OTP", "VPN", "ID", "AI", "mock", "Qwen"})

# 판단 문구 — 판정은 코드가 쓰고 AI는 사실만 쓴다 (#532)
# "승인 취소된 결제"처럼 지난 결제의 상태는 사실이라 걸리지 않는다
_JUDGEMENT = re.compile(
    r"권고|권장|(?:승인\s*취소|매입\s*진행|매입|취소)(?:을|를)?\s*(?:먼저\s*)?"
    r"(?:검토|해야|하세요|하십시오|하는\s*것이|할\s*(?:것|만하|수\s*있))"
)

# 반복 비교용 — 띄어쓰기 · 문장부호를 빼고 같은 문장인지 본다
_SPACING = re.compile(r"[\s.,·!?~\"'()\[\]]")


def _key(text: str) -> str:
    return _SPACING.sub("", text)


# 같은 묶음 안에서 반복된 문장
def _repeats(texts: list[str]) -> list[str]:
    seen: set[str] = set()
    repeated: list[str] = []
    for text in texts:
        key = _key(text)
        if key and key in seen:
            repeated.append(text)
        seen.add(key)
    return repeated


# 문장 속 숫자 — "3만 원"은 30000, "13:02"는 13과 2로 읽는다
# 작은 수도 뺀다 — "결제 3건"이 사실의 "4건"과 다르면 틀린 숫자다 (#548)
def _numbers(text: str) -> set[int]:
    found: set[int] = set()
    for digits, man, _ in _NUMBER.findall(text):
        plain = digits.replace(",", "")
        if plain:
            found.add(int(plain) * (10000 if man else 1))
    return found


_EXAMPLES = {_key(text) for text in EXAMPLE_SENTENCES}


# 겉모양 검사 — 코드값 · 확률 단정 · 판단 문구 · 한자 · 영어 · 예시 베끼기
# 걸리면 문장을 지우지 않고 다시 쓰게 한다 (#548)
def _style_errors(text: str, allowed_words: set[str]) -> list[str]:
    errors: list[str] = []
    if code := _CODE.search(text):
        errors.append(f'코드값 "{code.group()}"을 한국어로 풀어 써라: "{text}"')
    elif words := [w for w in _ENGLISH.findall(text) if w not in allowed_words]:
        errors.append(f'영어 단어 {words}를 한국어로 바꿔 써라: "{text}"')
    if phrase := _ASSERTIVE.search(text):
        errors.append(f'확률 · 단정 표현 "{phrase.group()}"을 쓰지 마라: "{text}"')
    if phrase := _JUDGEMENT.search(text):
        errors.append(
            f'판단 문구 "{phrase.group()}"을 쓰지 마라. 판정은 보안 직원이 한다. '
            f'무슨 일이 있었는지만 써라: "{text}"'
        )
    if hanja := _HANJA.search(text):
        errors.append(f'한자 · 일본 문자 "{hanja.group()}"를 한국어로 바꿔 써라: "{text}"')
    if _key(text) in _EXAMPLES:
        errors.append(f'예시 문장을 그대로 쓰지 말고 이번 사실로 써라: "{text}"')
    return errors


# 사실 문장 검사 — 번호가 있고, 준 사실 번호이고, (주제 칸이면) 그 주제 번호이고,
# 문장 속 숫자가 인용한 사실 안에 있는지
def _sentence_errors(
    sentence: FactSentence,
    by_id: dict[str, Fact],
    base_numbers: set[int],
    topic: str | None,
) -> list[str]:
    text = sentence.text
    if not sentence.facts:
        return [f'근거 사실 번호를 facts에 하나 이상 붙여라: "{text}"']
    unknown = [fid for fid in sentence.facts if fid not in by_id]
    if unknown:
        return [f'준 적 없는 사실 번호 {unknown}: "{text}"']
    cited = [by_id[fid] for fid in sentence.facts]
    errors: list[str] = []
    if topic is not None:
        other = [f.id for f in cited if f.topic != topic]
        if other:
            errors.append(
                f'topics.{topic}에는 이 주제의 사실 번호만 붙여라 (다른 주제 {other}): "{text}"'
            )
    allowed = set(base_numbers)
    words = set(ALLOWED_WORDS)
    for fact in cited:
        allowed |= _numbers(fact.text)
        words |= set(_ENGLISH.findall(fact.text))
    wrong = sorted(_numbers(text) - allowed)
    if wrong:
        errors.append(f'인용한 사실 {sentence.facts}에 없는 숫자 {wrong}: "{text}"')
    return errors + _style_errors(text, words)


# 형식 · 내용 검사 — 통과하면 Narrative, 아니면 AI에게 돌려줄 오류 문장 목록 (다시 쓰기)
# payment_ids: 사건 결제 ID — 문장에서 결제를 가리킬 때 쓰는 숫자라 허용한다
def validate_narrative(
    raw: str, facts: list[Fact], payment_ids: list[str]
) -> tuple[Narrative | None, list[str]]:
    try:
        narrative = Narrative.model_validate_json(raw)
    except ValidationError as e:
        return None, [f"{'.'.join(map(str, err['loc']))}: {err['msg']}" for err in e.errors()]
    by_id = {fact.id: fact for fact in facts}
    base = {len(payment_ids)} | {n for pid in payment_ids for n in _numbers(pid)}
    errors: list[str] = []
    expected = topic_keys(facts)
    if missing := [key for key in expected if key not in narrative.topics]:
        errors.append(f"topics에 빠진 주제: {missing}")
    if extra := [key for key in narrative.topics if key not in expected]:
        errors.append(f"topics에 없는 주제: {extra}")
    errors += [e for s in narrative.summary for e in _sentence_errors(s, by_id, base, None)]
    for key, sentences in narrative.topics.items():
        if key in expected and not sentences:
            errors.append(f"topics.{key}에 사실 문장을 1개 이상 써라")
        errors += [e for s in sentences for e in _sentence_errors(s, by_id, base, key)]
    # 확인할 것은 사실 번호를 붙이지 않는다 — 숫자는 준 사실 전체 안에서만
    all_numbers = base | {n for fact in facts for n in _numbers(fact.text)}
    all_words = set(ALLOWED_WORDS) | {w for fact in facts for w in _ENGLISH.findall(fact.text)}
    for text in narrative.additionalChecks:
        if wrong := sorted(_numbers(text) - all_numbers):
            errors.append(f'사실에 없는 숫자 {wrong}: "{text}"')
        errors += _style_errors(text, all_words)
    groups = [
        [s.text for s in narrative.summary],
        narrative.additionalChecks,
        *[[s.text for s in sentences] for sentences in narrative.topics.values()],
    ]
    errors += [f'같은 문장을 반복하지 마라: "{t}"' for texts in groups for t in _repeats(texts)]
    for name, count in (
        ("summary", len(narrative.summary)),
        ("additionalChecks", len(narrative.additionalChecks)),
    ):
        if count < MIN_SENTENCES:
            errors.append(f"{name}는 {MIN_SENTENCES}개 이상 써라 (지금 {count}개)")
    return (narrative, []) if not errors else (None, errors)
