# P6 주문·픽업 수신 기반

관련 이슈: #226, #227, #333. P1 HMAC 기반은 #219·#241, 의존 허용은 ADR-0038을 따른다.

이 변경은 CakeShop 서버가 보내는 픽업 예정·완료 정보와 주문 상품 목록을 받아 매입·정산 일정에 연결한다.
실제 CakeShop 전송부·배포 검증은 별도이며, 정산 완료 배치·취소·현금흐름·사장님 조회·프론트는 포함하지 않는다.

- [API·인증·재전달 계약](pickup-api.md)
- [테이블·시간·동시성 설계](pickup-schema.md)
- [ADR-0043: 저장·연결 방식과 대안](../decisions/0043-p6-pickup-receiving.md)
- [정기 지출 관리 API·ERD (4단계)](recurring-expenses-api.md)

## 처리 흐름

1. P1 공통 HMAC 검증이 요청의 가맹점을 확인한다. P6는 `CurrentMerchant.findCurrent()`만 사용한다.
2. 같은 `(merchant_id, order_id)` 행을 원자적으로 확보한 후 DB 쓰기 잠금을 잡는다.
3. 예정 요청은 예정 시각과 상품 전체를 한 트랜잭션으로 저장한다. 완료 요청은 실제 시각만 저장한다.
4. 매입이 있으면 같은 가맹점·주문의 정산에 정확히 72시간 뒤의 시각을 반영한다.
5. 매입이 아직 없으면 픽업을 보관한다. 이후 매입 트랜잭션도 같은 주문 잠금을 잡아 연결한다.

매입 완료와 정산 예정금 생성은 기존 단일 트랜잭션을 유지한다. P5 이벤트 레코드는 변경하지 않는다.
픽업 예정·완료·상품 저장은 별도 외부 금융 승인을 실행하지 않으며 이 기능에서 지급·정산 완료로 바꾸지 않는다.

## 기본 비활성

`settlement.pickup-http.enabled=true`일 때만 컨트롤러를 등록한다.
예: `./gradlew bootRun --args="--settlement.pickup-http.enabled=true"`.
연결 DB에 새 migration이 적용되므로 서버를 켜기 전에 대상 환경을 확인한다.
인증 필터는 P1 코드 그대로이며 P6 플래그가 꺼져도 인증 우회 경로를 만들지 않는다.

## 검증

분리한 브랜치에서 `spotlessApply`, `check`, `mariaDbTest`를 실행하고 PR에 커밋별 결과를 기록한다.
MariaDB 테스트는 Docker 격리 DB에 Flyway 전체를 적용하고 JPA 매핑·잠금·재전달·롤백·인증을 확인한다.
운영 키 배포·외부 도메인·프록시·실제 CakeShop 발신·최신 Notion 동기화는 검사 결과와 구분한다.
