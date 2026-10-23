# #118 모의 승인 계약

2026-10-23 · 기준 코드 `4a41a3f` · 2번 단계 계약과 4번 단계 구현. 승인기·입출력 검증과 승인 처리 서비스를 추가했다. 5번 단계에서 기존 생성·평가 흐름에 별도 createAndAuthorize 진입점을 연결했다. 6번 단계 MariaDB 검증 범위와 결과는 [검증 기록](verification.md)을 따른다.

## 범위와 책임

P5 내부 Java 모의 승인기를 호출해 성공 또는 업무 거절을 얻고, 호출자가 기존 PaymentRules로 후속 결제 상태를 판단한다. 실제 카드사·HTTP·별도 원장·파일·메모리 승인 장부를 변경하지 않는다. 승인 참조 생성 자체는 금융 부작용이 아니다.
이 계약은 `payment.service` 내부에 두고 다른 모듈·HTTP에 공개하지 않는다. FDS 평가·결제 조회·상태 저장·이벤트 발행은 승인기의 책임이 아니다.

기본 모의 구현은 **모든 유효한 요청에 성공을 반환**한다. 거절·기술 실패는 동일 인터페이스의 테스트용 대역으로 재현한다. 금액·주문번호의 특정 값이나 난수로 결과를 바꾸지 않는다. 따라서 기본 실행으로 거절을 시연하는 기능은 이번 계약에 없으며 필요하면 별도 내부 시연 진입점을 설계한다.

## 입력·출력

아래는 내부 계약의 요약이다. 실제 결과 구분 enum은 `AuthorizationResult.Type`이며 모든 승인기 계약 타입은 service 패키지 내부로 제한한다.

```java
interface PaymentAuthorizer {
    AuthorizationResult authorize(AuthorizationCommand command);
}

record AuthorizationCommand(Long paymentId, Long amount) {}

enum AuthorizationResultType { SUCCEEDED, DECLINED }

record AuthorizationResult(
        AuthorizationResultType type,
        String authorizationRef,
        String declineCode) {}
```

| 값 | 조건·의미 |
| --- | --- |
| command | 필수. null이면 IllegalArgumentException |
| paymentId | 필수·양수. 기존 저장 결제의 ID |
| amount | 필수·양수·원 단위. 같은 저장 결제에서 읽은 금액 |
| type | 필수. 성공 또는 업무 거절만 정상 반환 |
| authorizationRef | 성공 시 필수·공백 불가·100자 이하, 거절 시 null |
| declineCode | 거절 시 필수·공백 불가, 성공 시 null. 내부 오류 코드이며 카드정보를 담지 않음 |

잘못된 입력은 승인 결과로 바꾸지 않고 IllegalArgumentException으로 거절한다. 결과 객체도 생성 시 위 조합을 검증한다. null 결과·부적합 결과는 연동 오류이며 성공이나 업무 거절로 간주하지 않는다.
가맹점·주문·FDS 판정은 모의 승인 판단에 필요하지 않아 입력에서 제외한다. 결제 존재·금액 일치·승인 가능 상태·ALLOW/REVIEW 여부는 호출자가 검증한다. 외부 입력만으로 승인 여부를 선택하게 하지 않는다.

기본 성공 참조는 `MOCK-AUTH-<paymentId>`를 사용한다. 같은 결제에는 같은 참조가 생성되고, 다른 결제에는 다른 값이 생긴다. 실제 카드사 승인번호나 인증 수단으로 사용하지 않는다. 같은 참조 생성은 중복 호출 차단을 대신하지 않는다.
테스트용 업무 거절 코드는 예를 들어 `MOCK_DECLINED`를 쓴다. 거절 사유의 별도 DB 보존은 현재 payments 컬럼에 없으며, 구현 단계에서 저장 완료로 주장하지 않는다.

## 결과별 호출자 처리

| 조건 | 승인기 호출 | 후속 처리 계약 |
| --- | --- | --- |
| FDS DENY | 없음 | DENIED 저장 대상 |
| FDS ERROR·평가 예외·잘못된 평가 응답 | 없음 | 최초 PENDING 보존, 자동 승인 금지 |
| ALLOW + SUCCEEDED | 1회 | afterAuthorization의 SUCCEEDED 판단 사용. 참조 저장·PENDING 유지 |
| REVIEW + SUCCEEDED | 1회 | 같은 규칙으로 참조 저장·HELD 반영 |
| ALLOW/REVIEW + DECLINED | 1회 | DECLINED 판단 사용. 승인 참조 없음 |
| 모의 호출 예외·null/부적합 결과 | 자동 반복 없음 | 상태 반영 실패로 전달. DECLINED나 승인 성공으로 변환하지 않음 |

이 표의 상태 변경은 PaymentAuthorizationService에 구현했다. 6번 단계에서 실제 DB 커밋·롤백을 검증했다. 모의 성공 반환만으로 DB 저장 성공이 되는 것은 아니다. 이번 #118에서는 REQUEST_CAPTURE·NOTIFY_HELD를 실제 이벤트로 발행하지 않는다.

## 기술 실패와 UNKNOWN

모의 구현 및 실패 대역은 모두 외부 부작용이 없는 계약을 지킨다. 기술 실패는 테스트 대역이 예외를 던져 재현하며, 정상 결과 enum에 TECHNICAL_FAILURE를 추가하지 않는다. 호출자는 예외를 숨기거나 같은 호출 안에서 자동 재시도하지 않는다.

기존 `PaymentRules.afterAuthorization(..., TECHNICAL_FAILURE)`는 외부 실행 여부가 불명확한 경우 UNKNOWN·RECONCILE_AUTHORIZATION을 반환한다. 이 규칙과 기존 테스트는 유지한다. **#118의 부작용 없는 모의 예외는 이 분기로 전달하지 않는다.** 현재 SQL에 UNKNOWN 보존 필드가 없는데 저장된 것처럼 취급하지 않기 위한 경계다.
이 선택은 실제 외부 승인에도 재시도가 안전하다는 의미가 아니다. 외부 실행 또는 독립 승인 장부를 도입하려면 영속 멱등 키·결과 확인·UNKNOWN 복구를 먼저 설계하고 저장·기술 선택 변경에 대한 ADR을 작성해야 한다.

## 재호출 계약과 다음 단계

- 성공·거절 상태가 커밋된 결제는 승인기를 다시 호출하지 않는다. [3번 단계 설계](authorization-flow.md)에서는 원 응답 복원에 필요한 저장 데이터가 없으므로 동일 결과 재생 대신 충돌 예외로 거절한다.
- 부작용 없는 모의 호출/저장 실패로 상태 반영 트랜잭션이 확실히 롤백됐다면, 같은 결제를 명시적으로 다시 처리할 수 있는 구조를 검토한다. 자동 재시도는 없다. 같은 저장 금액·채택 평가를 확인하는 책임은 호출자에게 있다.
- 커밋 성공 여부를 알 수 없거나 응답이 유실되면 DB를 다시 확인하기 전 승인기를 호출하지 않는다. PENDING이라도 authorization_ref가 있으면 이미 승인된 결제다.
- 최초 생성 API `createAndEvaluate()`를 다시 호출하면 새 결제가 생기므로 같은 결제의 재시도 API로 사용하지 않는다. 주문 중복 접수와 재시작 후 전체 복구는 이번 범위 밖이다.
- 상태 검증·잠금·트랜잭션, 평가와 결제의 상관관계, 확실한 롤백/응답 유실의 구분은 [3번 단계 설계](authorization-flow.md)를 따른다. 이 문서만으로 동시성·멱등성이 구현됐다고 보지 않는다.

## 검토한 대안과 검증 계획

- 난수 결과: 테스트·시연을 재현하기 어려워 제외한다.
- 주문번호·금액에 숨은 성공/거절 규칙: FDS 판단과 승인 판단을 혼동시키고 업무 입력에 시연용 규칙이 섞여 제외한다.
- 호출자가 성공/거절을 지정하는 외부 필드: 승인 판정을 외부가 선택하게 되므로 제외한다.
- 별도 메모리 승인 장부: DB 롤백과 불일치하고 재시작 시 사라지므로 이번 모의에는 사용하지 않는다.

후속 테스트는 입력 검증, 결과 조합 검증, 기본 성공·참조 재현성, 거절/기술 실패 대역을 통한 호출자 분기, 성공·거절 후 중복 호출 금지, 확실한 롤백과 응답 유실의 재처리 차이를 확인한다. 4번 단계에서 단위 테스트를 추가했다. 6번 단계에서 DB 잠금·커밋·롤백과 생성·평가·승인 연결을 MariaDB로 검증했다. 실제 장애·응답 유실은 미검증이다.
