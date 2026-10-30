# 0049. 신청 검토용 쇼핑몰 주소 수집

- **상태:** 제안 — #354 PR A 사용자 설계·구현 승인, PR 리뷰 대상
- **날짜:** 2026-10-30
- **관련:** [Issue #354](https://github.com/team-sweethan/DonWorry/issues/354)
- **영향 받는 담당:** P1 identity·frontend

## 배경

관리자가 신청자의 실제 운영 쇼핑몰을 승인 전에 확인할 자료가 없다. 쇼핑몰 종류는 자체 구축·플랫폼·입점형 등 분류가 모호하고 운영 확인 목적에 필수적이지 않다. 기존 신청은 주소를 수집하지 않았으므로 임의 값을 채울 수 없다.

## 결정

신규 신청의 `shopUrl`을 필수로 받고 신청 테이블에만 저장한다. HTTP(S) 절대 URI·호스트·user-info 없음·trim 후 2048자 이하를 검사하며 자동으로 접속하지 않는다. 국제화 호스트는 ICU4J 79.1의 UTS #46 비전환 처리로 검증할 때만 Punycode로 변환하고 주소 원문을 보존한다. 프론트도 호스트 구간 문법을 추가 검사하여 밑줄·빈 구간·앞뒤 하이픈이 있는 주소를 제출 전에 거절한다. 관리자 상세에서 확인용 링크로 제공한다. 기존 행은 nullable로 보존하고 주소가 없다는 이유만으로 기존 승인을 막지 않는다. 동의 문구·버전과 화면·테스트를 같은 PR에서 갱신한다.

### 국제화 도메인 처리

서버의 Unicode 호스트 검증에 `com.ibm.icu:icu4j:79.1`을 사용한다. Java 기본 IDN의 IDNA2003 문자 처리는 브라우저가 허용하는 `ẞ`·이모지와 불일치한다. [ICU IDNA 공식 API](https://unicode-org.github.io/icu-docs/apidoc/dev/icu4j/com/ibm/icu/text/IDNA.html)의 UTS #46 인스턴스에 NONTRANSITIONAL_TO_ASCII·CHECK_BIDI·CHECK_CONTEXTJ·USE_STD3_RULES를 적용한다. 인스턴스는 공유하고 요청별 Info·출력 버퍼를 분리한다. URI가 이미 해석하는 ASCII·IP 호스트는 기존 처리를 유지한다.

[WHATWG 도메인 처리 규칙](https://url.spec.whatwg.org/#concept-domain-to-ascii)은 비전환 처리와 BiDi·Joiner 검사를 사용한다. 브라우저 비엄격 처리에서 검사하지 않는 HYPHEN_3_4·LABEL_TOO_LONG·DOMAIN_NAME_TOO_LONG 오류는 실패로 취급하지 않는다. 앱 자체는 앞뒤 하이픈·밑줄·빈 구간을 금지하고 주소 전체 2048자 제한을 유지한다. 원문 저장·조회·DB에는 추가 정규화를 하지 않는다.

프론트와 서버는 같은 [호스트 사례 파일](../../backend/src/test/resources/identity/shop-url-cases.json)을 읽어 허용·거절 결과를 검사한다. 이 사례 대조는 모든 브라우저·Unicode 버전의 완전한 동등성 증명이 아니며 런타임·ICU 갱신 때 같은 사례를 재실행한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 쇼핑몰 종류와 주소를 함께 수집 | 분류가 애매하고 운영 여부 확인에 주소로 충분하다 |
| merchants에 저장하거나 webhook 주소로 사용 | 신청 당시 검토 자료와 연동 설정의 목적·생명주기가 다르다 |
| NOT NULL과 기본 주소로 기존 행 채우기 | 수집하지 않은 사실을 조작하고 기존 기록을 훼손한다 |
| 주소 없는 기존 신청 전부 반려 | 이미 접수된 신청을 추가 수집 항목 때문에 중단시킨다 |
| Java 기본 IDN 유지·문자별 예외 추가 | 브라우저 UTS #46과 문자 집합·매핑·Joiner 규칙이 달라 불일치가 반복된다 |
| 국제화 도메인을 프론트에서 모두 거절 | 한글을 포함한 정상 쇼핑몰 주소를 제한한다 |
| 서버가 URL에 접속해 운영 여부 자동 판정 | 이번 범위는 관리자 수동 검토이며 별도 외부 접속이 필요 없다 |

## 영향

- API: 기존 공개 신청 POST `shopUrl` 필수, 관리자 상세 GET nullable `shopUrl` 추가. 구 양식은 새 동의 안내 확인 후 다시 제출한다.
- DB: P1 merchant_applications에 shop_url VARCHAR(2048) NULL을 새 Flyway migration으로 추가. 기존 migration 보존.
- 화면: 신청·입력 확인·개인정보 안내·관리자 상세 변경.
- 빌드: ICU4J 79.1 런타임 의존성 추가. Gradle이 Maven Central에서 받으며 설정·서비스·DB 추가는 없다.
- 테스트: 입력 검증·저장·상세·기존 승인·신청 보존 정책·프론트 계약 검사.
- 문서: [PR A 설계](../identity/354-pr1-application-design.md), [ERD](../erd/README.md), 기존 신청 설계. 모듈 공개 Java API·다른 담당 스키마 변경 없음.

## 검증

검증 명령과 결과는 [PR A 설계](../identity/354-pr1-application-design.md)의 실행 기록과 PR에 남긴다. Notion 대조·동기화, 운영 배포는 미수행이다.
