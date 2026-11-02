import argparse
import csv
import importlib.util
import json
import random
import sys
from collections import defaultdict
from pathlib import Path

import joblib

# fds-ml 원본 features.py와 원본 모델로 ML golden(정답 기준) 데이터를 만든다
# 예) uv run python -m scripts.make_ml_golden --fds-ml ../fds-ml
#     → tests/fixtures/ml_golden.json (features.py · 모델을 바꾸면 다시 만든다)

ROW_FIELDS = (
    "paymentId",
    "merchantId",
    "customerRef",
    "amount",
    "paymentRequestedAt",
    "deviceId",
    "cardToken",
)
CASE_COUNT = 30
SEED = 42


def load_original_features(fds_ml: Path):
    spec = importlib.util.spec_from_file_location("fds_ml_features", fds_ml / "features.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module  # dataclass가 모듈을 찾을 수 있게 등록
    spec.loader.exec_module(module)
    return module


def api_row(row: dict[str, str]) -> dict[str, object]:
    # Spring이 보낼 모양 — merchantId · paymentId · amount는 숫자, 기기 · 카드는 없으면 null
    return {
        "paymentId": int(row["paymentId"]),
        "merchantId": int(row["merchantId"]),
        "customerRef": row["customerRef"],
        "amount": int(row["amount"]),
        "paymentRequestedAt": row["paymentRequestedAt"],
        "deviceId": row["deviceId"] or None,
        "cardToken": row["cardToken"] or None,
    }


def pick_cases(rows, features) -> list[tuple[str, int]]:
    rng = random.Random(SEED)
    picked: dict[int, str] = {}

    def take(name: str, candidates: list[int], count: int) -> None:
        pool = [i for i in candidates if i not in picked]
        for i in rng.sample(pool, min(count, len(pool))):
            picked[i] = name

    first_tx = [i for i, f in enumerate(features) if f["customerPastTxCount"] == 0]
    take("new_customer", first_tx, 4)
    by_scenario = defaultdict(list)
    for i, row in enumerate(rows):
        if row["fraudLabel"] == "1":
            by_scenario[row["scenarioType"]].append(i)
    for scenario in sorted(by_scenario):
        take(f"fraud_{scenario}", by_scenario[scenario], 2)
    take("shared_card", [i for i, f in enumerate(features) if f["cardUsedByOtherCustomer"]], 3)
    take("burst_5m", [i for i, f in enumerate(features) if f["txCount5m"] >= 2], 2)
    take("normal", [i for i, row in enumerate(rows) if row["fraudLabel"] == "0"], CASE_COUNT)
    return [(name, i) for i, name in list(picked.items())[:CASE_COUNT]]


def main() -> int:
    parser = argparse.ArgumentParser(description="ML golden 데이터 생성")
    parser.add_argument("--fds-ml", type=Path, required=True, help="fds-ml 폴더 경로")
    parser.add_argument(
        "--out", type=Path, default=Path(__file__).parents[1] / "tests/fixtures/ml_golden.json"
    )
    args = parser.parse_args()

    original = load_original_features(args.fds_ml)
    with open(args.fds_ml / "data/transactions.csv", encoding="utf-8-sig", newline="") as file:
        rows = list(csv.DictReader(file))
    features = original.build_features(rows)

    bundle = joblib.load(args.fds_ml / "models/random_forest_baseline.joblib")
    pipeline = bundle["pipeline"]
    pipeline[-1].set_params(n_jobs=1)
    columns = bundle["feature_columns"]

    times = [original._parse_timestamp(row["paymentRequestedAt"]) for row in rows]
    cases = []
    for name, i in pick_cases(rows, features):
        target, at = rows[i], times[i]
        # 대상보다 엄격히 이전 거래만 — 같은 시각 거래는 이력이 아니다 (features.py 규칙)
        history = [r for r, t in zip(rows, times, strict=True) if t < at]
        mine = [r for r in history if r["customerRef"] == target["customerRef"]]
        shared = [
            r
            for r in history
            if target["cardToken"]
            and r["cardToken"] == target["cardToken"]
            and r["customerRef"] != target["customerRef"]
        ]
        expected = {c: features[i][c] for c in columns}
        cases.append(
            {
                "name": name,
                "target": api_row(target),
                "customerHistory": [api_row(r) for r in mine],
                "sharedCardHistory": [api_row(r) for r in shared],
                "expectedFeatures": expected,
                "expectedProbability": float(pipeline.predict_proba([expected])[0][1]),
            }
        )

    golden = {
        "source": "fds-ml/features.py + models/random_forest_baseline.joblib (n_jobs=1)",
        "threshold": bundle["threshold"],
        "featureColumns": list(columns),
        "cases": cases,
    }
    args.out.write_text(json.dumps(golden, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(cases)}건 → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
