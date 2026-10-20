# 0001. 모듈 간 의존 방향

- **상태:** 제안 — 팀 합의 필요
- **날짜:** 2026-10-20
- **관련:** Notion `2 API 명세서`, `3 ERD`
- **영향 받는 담당:** 전원 (특히 P2 정후 · P5 시은 · P6 민정 · P3 수민)

## 배경

팀 API 명세 · ERD를 그대로 구현하면 모듈끼리 서로 호출하는 곳이 네 군데 생긴다.
Spring Modulith는 모듈 단위로 의존을 세므로 서로 다른 기능이어도 순환으로 판정하고 `ModularityTests`(`verify()`)가 실패한다.

| # | 정방향 | 역방향 |
| --- | --- | --- |
| ① | payment → fds: 위험 평가 호출 | fds → payment: 경보 화면의 결제 상태 조회, 규칙용 결제 이력 조회 |
| ② | payment → settlement: 취소 가능 여부 조회 · 취소 반영 | settlement → payment: 매입 대상 조회 · 결제 행 잠금 |
| ③ | fds → investigation: 경보 전달 | investigation → fds: 근거 조회 |
| ④ | investigation → P4: 보고서 요청 | P4 → investigation: 완료 통보 · 결정 확인 |

업무 코드가 0줄인 지금 방향을 정하면 고칠 코드가 없다.

## 결정

**4층 구조. 직접 호출(공개 `api/` 인터페이스)은 아래층으로만, 아래층이 위층에 알릴 일은 이벤트로 보낸다.**

```
investigation (P3·P4)   settlement (P6)      ← 위층: 아무도 부르지 않음 (공개 api 없음)
            \               /
             payment (P5)
              /       \
        fds (P2)       \
              \         \
              identity (P1)                  ← 맨 아래: 모두가 의존
```

| 모듈 | 의존(직접 호출) | 공개 `api/` | 발행 이벤트 | 수신 이벤트 |
| --- | --- | --- | --- | --- |
| identity | 없음 | `TenantReader`, `CurrentUser` | — | — |
| fds | identity | `FdsEvaluator`, `AlertReader`, `BlockCommand` | `AlertRaised` | — |
| payment | fds, identity | `PaymentReader`, `ReviewExecutor`, `SettlementGate` | `PaymentHoldExpired`, `PaymentCanceled` | — |
| investigation | payment, fds, identity (+ ai-server HTTP) | 없음 | — | `AlertRaised`, `PaymentHoldExpired` |
| settlement | payment, identity | 없음 | — | `PaymentCanceled` |

이벤트 타입은 **보내는 쪽 `api/`**에 둔다. 받는 쪽이 그 타입을 import하므로 의존 방향은 여전히 아래로 향한다.

순환 해소:

- ① fds는 payment를 조회하지 않는다. payment가 평가 요청에 재료(`cardFingerprint`, `deviceId`, `customerRef`, `amount`, 같은 카드 과거 승인 여부)를 담아 넘긴다. "10분 내 3회"는 fds가 자기 평가 기록으로 센다. 경보 목록 · 상세 화면 API는 investigation으로 옮긴다
- ② settlement가 픽업 정보를 받으면 환불 마감을 `SettlementGate.registerRefundDeadline()`으로 payment에 넣는다. payment는 취소 가능 여부를 혼자 판단하고 `PaymentCanceled`를 발행한다
- ③ 경보 전달은 `AlertRaised` 이벤트
- ④ P4 코드는 `investigation/report` 하위 패키지 (같은 모듈)

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 순환을 허용하고 `verify()`를 끈다 | 모듈 경계 검사 자체를 버리게 된다. 6명이 동시에 개발하면 얽힘이 빠르게 늘어난다 |
| 공통 모듈(`common`)에 공유 타입을 모은다 | 6번째 모듈이 생기고, 잡동사니 서랍이 되면 다시 순환이 생긴다 |
| 모든 모듈 간 통신을 이벤트로 | 결제 중 FDS 평가처럼 결과를 바로 받아야 하는 호출이 비동기가 되어 흐름이 복잡해진다 |
| P4를 별도 모듈로 | investigation과 서로 호출해 순환 ④가 생긴다 |

## 영향

- API: 경보 목록 · 상세(P2-API-03 · 04)의 제공 모듈이 fds → investigation으로 바뀜. 위험 평가 요청에 판단 재료 칸 추가
- DB: payment에 환불 마감 칸 추가 필요 (settlement가 등록)
- 화면: 없음 (URL은 그대로 둘 수 있음)
- 테스트: 합의 후 각 모듈 `package-info.java`에 `allowedDependencies`를 넣어 `verify()`로 강제
- 문서: Notion API 명세 · ERD의 해당 행 갱신

## 합의가 필요한 것

1. 신규 카드 판단 재료를 payment가 계산해 넘긴다 — 정후 · 시은
2. 경보 목록 · 상세 API를 investigation으로 옮긴다 — 정후 · 수민
3. 환불 마감을 settlement가 payment에 등록한다 — 시은 · 민정

## 검증

미검증 — 업무 코드가 없다. 합의 후 `allowedDependencies`를 넣고, 방향을 어기는 임시 클래스로 `./gradlew check`가 실패하는지 확인한다.
