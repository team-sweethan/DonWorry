# fds-ml/features.py 복사본 (ruff 표기 정리만 적용) — 학습·운영 Feature의 단일 기준
# 계산 규칙을 바꾸면 fds-ml 원본·모델·tests/fixtures/ml_golden.json을 함께 갱신한다
from __future__ import annotations

import csv
import math
from collections import defaultdict, deque
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta, timezone
from itertools import groupby
from pathlib import Path

# 거래 시각은 UTC로 비교하고, 시간대 Feature만 KST로 변환한다.
KST = timezone(timedelta(hours=9))

WINDOW_5M = timedelta(minutes=5)
WINDOW_30M = timedelta(minutes=30)
WINDOW_24H = timedelta(hours=24)


FEATURE_COLUMNS = (
    "amount",
    "merchantId",
    "hourOfDay",
    "customerPastTxCount",
    "customerAvgAmount",
    "amountRatio",
    "txCount5m",
    "txCount30m",
    "txCount24h",
    "minutesSinceLastTx",
    "deviceSeenBefore",
    "cardSeenBefore",
    "cardUsedByOtherCustomer",
)

CATEGORICAL_FEATURES = ("merchantId",)


@dataclass(frozen=True)
class Transaction:
    """Feature 계산에 필요한 거래 정보."""

    original_index: int
    timestamp: datetime
    customer_ref: str
    merchant_id: str
    amount: float
    device_id: str
    card_token: str


@dataclass
class CustomerHistory:
    """특정 고객에 대해 현재 거래 이전까지 축적된 이력."""

    tx_count: int = 0
    total_amount: float = 0.0
    last_time: datetime | None = None

    device_ids: set[str] = field(default_factory=set)
    card_tokens: set[str] = field(default_factory=set)

    times_5m: deque[datetime] = field(default_factory=deque)
    times_30m: deque[datetime] = field(default_factory=deque)
    times_24h: deque[datetime] = field(default_factory=deque)


def _required_text(row: Mapping[str, object], key: str) -> str:
    """필수 컬럼의 문자열을 읽는다."""

    value = row[key]

    if value is None or not str(value).strip():
        raise ValueError(f"{key} 값이 비어 있습니다.")

    return str(value).strip()


def _optional_text(row: Mapping[str, object], key: str) -> str:
    """카드 또는 기기 정보가 없으면 빈 문자열을 반환한다."""

    value = row.get(key)
    return "" if value is None else str(value).strip()


def _parse_timestamp(value: object) -> datetime:
    """ISO 8601 시각을 UTC datetime으로 변환한다."""

    if isinstance(value, datetime):
        timestamp = value
    else:
        text = str(value).strip()

        if text.endswith("Z"):
            text = text[:-1] + "+00:00"

        timestamp = datetime.fromisoformat(text)

    if timestamp.tzinfo is None or timestamp.utcoffset() is None:
        raise ValueError("paymentRequestedAt에 시간대가 필요합니다.")

    return timestamp.astimezone(UTC)


def _parse_transaction(
    row: Mapping[str, object],
    index: int,
) -> Transaction:
    """CSV 행을 Feature 계산용 거래 객체로 변환한다."""

    try:
        timestamp = _parse_timestamp(row["paymentRequestedAt"])
        customer_ref = _required_text(row, "customerRef")
        merchant_id = _required_text(row, "merchantId")
        amount = float(row["amount"])

        if not math.isfinite(amount) or amount < 0:
            raise ValueError("amount는 0 이상의 유한한 숫자여야 합니다.")

        return Transaction(
            original_index=index,
            timestamp=timestamp,
            customer_ref=customer_ref,
            merchant_id=merchant_id,
            amount=amount,
            device_id=_optional_text(row, "deviceId"),
            card_token=_optional_text(row, "cardToken"),
        )

    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError(f"거래 행 {index + 1} 처리 실패: {exc}") from exc


def _expire_old_times(
    timestamps: deque[datetime],
    cutoff: datetime,
) -> None:
    """지정한 기간보다 오래된 거래 시각을 제거한다."""

    while timestamps and timestamps[0] < cutoff:
        timestamps.popleft()


def build_features(
    transactions: Iterable[Mapping[str, object]],
) -> list[dict[str, int | float | str]]:
    """
    거래별 Feature를 생성한다.

    원칙:
    1. 거래를 UTC 시각순으로 처리한다.
    2. 현재 거래 이전의 이력만 사용한다.
    3. 동일 시각 거래는 모두 계산한 뒤 이력을 갱신한다.
    4. fraudLabel, scenarioType 등 정답 정보는 읽지 않는다.
    5. 반환 순서는 입력 거래 순서와 같다.
    """

    records = [_parse_transaction(row, index) for index, row in enumerate(transactions)]

    # 원본 CSV가 정렬되어 있지 않아도 안전하게 처리한다.
    records.sort(key=lambda tx: tx.timestamp)

    histories: dict[str, CustomerHistory] = defaultdict(CustomerHistory)

    # 카드별로 과거에 사용한 고객을 기록한다.
    card_owners: dict[str, set[str]] = defaultdict(set)

    results: list[dict[str, int | float | str] | None] = [None] * len(records)

    # 같은 타임스탬프는 하나의 그룹으로 처리한다.
    for timestamp, group in groupby(
        records,
        key=lambda tx: tx.timestamp,
    ):
        same_time_transactions = list(group)

        # 1단계: 현재 거래를 반영하지 않고 Feature 계산
        for tx in same_time_transactions:
            history = histories[tx.customer_ref]

            _expire_old_times(
                history.times_5m,
                timestamp - WINDOW_5M,
            )
            _expire_old_times(
                history.times_30m,
                timestamp - WINDOW_30M,
            )
            _expire_old_times(
                history.times_24h,
                timestamp - WINDOW_24H,
            )

            if history.tx_count > 0:
                average_amount = history.total_amount / history.tx_count
            else:
                average_amount = 0.0

            if average_amount > 0:
                amount_ratio = tx.amount / average_amount
            else:
                amount_ratio = 0.0

            if history.last_time is not None:
                minutes_since_last_tx = (timestamp - history.last_time).total_seconds() / 60.0
            else:
                minutes_since_last_tx = -1.0

            device_seen_before = int(bool(tx.device_id) and tx.device_id in history.device_ids)

            card_seen_before = int(bool(tx.card_token) and tx.card_token in history.card_tokens)

            other_customer_used_card = 0

            if tx.card_token:
                previous_owners = card_owners.get(tx.card_token, set())

                other_customer_used_card = int(
                    any(owner != tx.customer_ref for owner in previous_owners)
                )

            results[tx.original_index] = {
                "amount": tx.amount,
                "merchantId": tx.merchant_id,
                "hourOfDay": timestamp.astimezone(KST).hour,
                "customerPastTxCount": history.tx_count,
                "customerAvgAmount": average_amount,
                "amountRatio": amount_ratio,
                "txCount5m": len(history.times_5m),
                "txCount30m": len(history.times_30m),
                "txCount24h": len(history.times_24h),
                "minutesSinceLastTx": minutes_since_last_tx,
                "deviceSeenBefore": device_seen_before,
                "cardSeenBefore": card_seen_before,
                "cardUsedByOtherCustomer": (other_customer_used_card),
            }

        # 2단계: 동일 시각 거래의 Feature 계산이 끝난 뒤
        # 모든 거래를 과거 이력에 추가한다.
        for tx in same_time_transactions:
            history = histories[tx.customer_ref]

            history.tx_count += 1
            history.total_amount += tx.amount
            history.last_time = timestamp

            history.times_5m.append(timestamp)
            history.times_30m.append(timestamp)
            history.times_24h.append(timestamp)

            if tx.device_id:
                history.device_ids.add(tx.device_id)

            if tx.card_token:
                history.card_tokens.add(tx.card_token)
                card_owners[tx.card_token].add(tx.customer_ref)

    # 모든 입력 행에는 반드시 결과가 하나씩 있어야 한다.
    if any(row is None for row in results):
        raise RuntimeError("일부 거래의 Feature가 생성되지 않았습니다.")

    return [row for row in results if row is not None]


def load_feature_rows(
    csv_path: str | Path,
) -> list[dict[str, int | float | str]]:
    """transactions.csv에서 ML Feature를 생성한다."""

    with open(
        csv_path,
        encoding="utf-8-sig",
        newline="",
    ) as file:
        transactions = list(csv.DictReader(file))

    return build_features(transactions)
