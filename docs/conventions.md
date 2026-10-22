# 코드 컨벤션

포매터·자동 검사가 정하지 못하는 **팀 선택**만 적는다. 사람·AI 공통으로 따른다.

| 표시 | 뜻 |
| --- | --- |
| `합의 전` | 초안. 팀 리뷰에서 고치거나 뺀다 |
| `합의` | 팀이 확정. 이 규칙을 따른다 |
| `보류` | 아직 정할 수 없음. 정해질 때까지 각자 판단하지 말고 담당에게 묻는다 |

여기에 적지 않는 것:

| 대상 | 어디서 정하나 |
| --- | --- |
| 들여쓰기 · 줄바꿈 · import 순서 | 포매터가 자동으로 맞춘다 — 커밋 전 `./gradlew spotlessApply` ([ADR-0013](decisions/0013-code-formatter.md)) |
| 모듈 경계 · 패키지 구조 · 계층 | [Modulith 03](modulith/03-module-structure.md#2-모듈-안-패키지-규칙), AGENTS.md 4절 (테스트가 검사) |
| 이벤트 · 모듈 간 호출 | [Modulith 04](modulith/04-dependency-and-events.md) |
| 테이블 · 마이그레이션 | [Flyway 05](flyway/05-guide.md) |
| ID · 금액 · 시각 타입 | 미정 — [P5 결제 문서 C1](payment/README.md#구현-전에-필요한-최소-계약) |

## 1. Lombok `합의 전`

- Entity: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
- Entity에 `@Setter` · `@Data` 쓰지 않는다. 상태 변경은 이름 있는 메서드로만 한다
- 의존성 주입(필요한 객체를 생성자로 받기)은 `@RequiredArgsConstructor` + `private final` 필드

```java
// 상태 변경은 이름 있는 메서드로만 — 어디서 바뀌는지 검색되게
public void hold() {
    this.status = PaymentStatus.HELD;
}
```

이유: setter가 열려 있으면 어느 코드가 상태를 바꿨는지 추적할 수 없다. 결제 · 판정 상태는 바뀐 경로가 분명해야 한다.

## 2. DTO `합의 전`

- 요청 · 응답 DTO는 `record`로 만든다
- 화면 · 외부 HTTP용(`dto/`) 이름은 `XxxRequest` · `XxxResponse`
- Entity → DTO 변환은 DTO의 정적 메서드 `XxxResponse.from(entity)`
- 다른 모듈에 공개하는 `api/` record는 [Modulith 03](modulith/03-module-structure.md#2-모듈-안-패키지-규칙)을 따른다

```java
public record ReportResponse(Long id, String status) {
    // Entity를 밖으로 내보내지 않고 필요한 값만 옮긴다
    public static ReportResponse from(AiReport report) {
        return new ReportResponse(report.getId(), report.getStatus().name());
    }
}
```

## 3. 에러 응답 `합의 전`

1차 CakeShop의 `ErrorCode` · `BusinessException` 방식을 그대로 쓰고, 응답만 HTML 에러 페이지 대신 **ProblemDetail**(RFC 9457, 에러 응답 표준 JSON 형식, Spring 기본 제공) JSON으로 내보낸다.

팀원이 할 일:

1. 모듈별 `XxxErrorCode` enum에 한 줄 추가 (코드 · 메시지 · HTTP 상태)
2. Service에서 `throw new BusinessException(XxxErrorCode.YYY)`
3. 테스트에서 상태 코드와 `code`를 확인

```java
// 모듈별 에러 코드 — 코드는 <모듈>_<3자리>
REPORT_NOT_FOUND("REPORT_001", "보고서가 없습니다.", 404),

// Service
throw new BusinessException(ReportErrorCode.REPORT_NOT_FOUND);
```

응답 (공통 처리기가 변환):

```json
{ "status": 404, "title": "Not Found", "detail": "보고서가 없습니다.", "code": "REPORT_001" }
```

하지 않는 것:

- Controller에서 `try-catch`로 잡아 에러 JSON을 직접 만들기
- 실패를 `200`으로 응답하기
- `@Valid` 검증 오류를 각자 처리하기 (공통 처리기가 `400`으로 응답)

고려한 대안:

| 대안 | 차이 |
| --- | --- |
| `ApiResponse<T>`로 성공 · 실패 모두 감싸기 (`{success, data, error}`) | 성공 응답마다 감싸야 하고, HTTP 상태와 `success`가 같은 뜻을 두 번 담는다. 1차 CakeShop도 쓰지 않았다 (성공은 `ResponseEntity<DTO>` 그대로) |
| 1차 CakeShop JSON API 방식 그대로 | Controller마다 실패 응답이 문자열 · 빈 본문 · 전용 DTO로 달랐다. React가 모든 화면을 JSON으로 받으므로 통일이 필요하다 |

### 공통 클래스 위치 `보류`

`ErrorCode` · `BusinessException` · 공통 처리기(`@RestControllerAdvice`)는 어느 업무 모듈에도 속하지 않는다. 모든 모듈이 쓰는 공유 패키지(예: `common`)와 그 담당을 정한 뒤 만든다. 그 전까지 각자 공통 클래스를 만들지 않는다.

## 4. 트랜잭션 `합의 전`

- `@Transactional`은 Service에만 붙인다. Controller · Repository에는 붙이지 않는다
- 조회만 하는 메서드는 `@Transactional(readOnly = true)`

이유: 이벤트는 보낸 쪽 트랜잭션이 커밋된 뒤에 처리된다 ([Modulith 04](modulith/04-dependency-and-events.md)). 트랜잭션 경계가 Service에 모여 있어야 언제 커밋되는지 보인다.

## 5. 이름 `합의 전`

| 대상 | 규칙 | 예 |
| --- | --- | --- |
| URL | `/api/<자원 복수형>`, 여러 단어는 kebab-case(소문자-하이픈) | `/api/payments/{id}/cancel`, `/api/security-cases` |
| 클래스 | 계층을 접미사로 | `ReportController` · `ReportService` · `AiReportRepository` |
| boolean | `is` · `has` · `can`으로 시작 | `isExpired()`, `hasReport` |
| 상수 · enum 값 | 대문자 + 밑줄 | `PAYMENT_EXPIRED`, `HELD` |
| JSON 필드 | camelCase (Java 필드 이름 그대로) | `paymentId` |

## 6. 테스트 이름 `합의 전`

- 한글 메서드 이름 `상황_결과` 형식. `@DisplayName`은 쓰지 않는다
- 지금 `PaymentRulesTests`가 쓰는 방식이다

```java
@Test
void ALLOW_승인_성공은_최종_매입_성공이_아니며_재승인하지_않는다() { ... }
```

## 7. 로그 `합의 전`

- 로거는 `@Slf4j`
- 카드번호 · 개인정보 · 토큰 · 비밀번호는 로그에 남기지 않는다. 필요하면 ID만 남긴다
- 문자열 더하기 대신 자리표시자 `{}`를 쓴다

```java
log.info("결제 보류 paymentId={}", payment.getId());   // 카드번호 대신 ID
```

## 8. 주석 `합의 전`

- `//` 한두 줄. 여러 줄 `/* */` 주석은 쓰지 않는다
- "무엇을 하는지"보다 "왜 이렇게 했는지"를 적는다. 코드만 봐도 알 수 있는 내용은 적지 않는다
