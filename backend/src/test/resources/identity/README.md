# 쇼핑몰 주소 공통 검증 사례

`shop-url-cases.json`은 Java `MerchantApplicationRequestsTests`와 frontend `shopUrl.test.ts`가 함께 읽는다. 기대값은 서비스 실행 결과에서 생성하지 않는다.

## URI 구성요소 문자 범위

[Java 21 URI 문법](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/URI.html)과 [OpenJDK 21 URI의 path·URIC 정의](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/net/URI.java)를 기준으로 다음을 대조한다.

- 경로: 영숫자·unreserved·escaped·`:@&=+$,;/`를 허용한다. 원시 대괄호는 금지하고 `%5B`·`%5D`는 허용한다.
- 쿼리·fragment: 위 범위에 `?[]`를 허용한다. fragment 안의 추가 `#`는 금지한다.
- 출력 가능한 ASCII U+0020~U+007E의 95개 문자를 경로·쿼리·fragment에 각각 삽입한 285개 사례를 포함한다. `?`·`#`가 구성요소 경계로 쓰이는 경우도 구분한다. 불완전 percent escape는 허용하지 않는다.
- 나머지 82개 사례는 국제화 호스트·IP·포트·IPv6·명시적인 대괄호 경로와 인코딩 경계를 검증한다. 총 367개다.

이 사례 집합은 모든 브라우저·Java·Unicode 버전의 완전한 동등성을 증명하지 않는다. 구체적인 불일치가 확인되면 규칙과 기대값의 근거를 먼저 확인하고 같은 파일에 회귀 사례를 추가한다. 서비스 구현의 결과에 맞춰 기대값을 바꾸지 않는다.
