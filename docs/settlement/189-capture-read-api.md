# #189 P6 매입·정산 상태 공개 조회

같은 Spring 앱의 P5는 `settlement.api.CaptureReader`만 주입하여 사용한다.
ADR-0012의 `payment → settlement` 방향을 유지하며 HTTP·DB 스키마 변경은 없다.

```java
import com.donworry.settlement.api.CaptureReader;

// 생성자 주입받은 reader로 조회한다.
var snapshot = reader.findByPaymentId(paymentId);
```

반환형은 `Optional<CaptureSnapshot>`이다.

| 필드 | 의미 |
| --- | --- |
| paymentId | 조회한 P5 결제 ID |
| captureId | P6 매입 ID |
| captureStatus | 저장된 PROCESSING / COMPLETED / FAILED |
| captureAmount | 원 단위 매입 금액 |
| capturedAt | 실제 저장된 완료 시각. 완료 전 NULL, DB 마이크로초 정밀도 |
| settlementExists | 매입 ID를 참조하는 정산 기록 존재 여부 |
| settlementStatus | 저장된 SCHEDULED / COMPLETED / CANCELED. 정산 없음이면 NULL |

- 매입 없음: `Optional.empty()`. 요청 미전달·처리 중 롤백·최종 실패를 단정하지 않는다.
- 매입은 있고 정산 없음: 빈 결과가 아니라 `settlementExists=false`, 상태 NULL로 반환한다.
- null·0 이하 paymentId: `IllegalArgumentException`.
- DB 실패: Spring 데이터 접근 예외 등을 그대로 전달한다. 빈 결과나 FAILED로 바꾸지 않는다.
- Entity·내부 Enum·Repository는 공개하지 않는다. 상태 문자열은 위 값으로 제공한다.
- `SCHEDULED`는 정산 예정금 저장 상태이며 지급·입금 완료가 아니다.

## 트랜잭션과 한계

새 읽기 전용 트랜잭션(REQUIRES_NEW)에서 단일 LEFT JOIN 조회를 실행한다.
호출자의 트랜잭션은 일시 중단되며 그 안의 미커밋 변경은 조회에 반영하지 않는다.
읽기 전용은 쓰기 방지 권한 장치가 아니므로 구현 자체도 조회 전용 Repository만 사용한다.
매입 실행·상태 변경·이벤트 발행·재전달은 하지 않는다.

결과는 이번 조회에서 관측한 P6 사실이며 이후 변경될 수 있다.
P5/P3 상태와 원자적인 스냅샷이나 취소·정산 경합 제어를 보장하지 않는다.
현재 정상 매입 흐름은 중간 PROCESSING을 선행 커밋하지 않으며 DB 오류가 자동으로 FAILED 행을 남기지도 않는다.
운영 재전달은 #191, 호출 측 구현은 #188 범위다.

## 검증

`CaptureReaderTests`: 입력 오류·매입 없음·상태/시각 매핑·정산 없음·DB 오류 전파.
`CaptureReaderMariaDbTests`: 실제 LEFT JOIN·마이크로초 시각·완료 전 상태·없음·호출자 미커밋 변경 분리·이벤트 및 업무 데이터 부작용 확인.
MariaDB 검사는 Docker로 격리 DB를 사용하며 개인 donworry DB를 사용하지 않는다.
