# 보안 홈 샘플 미리보기

`frontend`에서 `npm run preview:security-home`을 실행하고
<http://localhost:5174/security-home-preview.html>을 연다.
이미 5174 서버가 실행 중이면 기존 서버에서 같은 주소를 열면 된다.

- 샘플 사건 12건과 빈 상태를 전환하고, 등장 효과를 다시 보거나 새 사건 강조를 확인할 수 있다.
- 실제 홈과 같은 `SecurityHomeDashboard`를 사용한다. 인증을 우회하지 않으며 실제 API를 호출하거나 DB에 기록하지 않는다.
- 샘플의 사건 열기·현황 이동은 안내만 표시한다. 실제 화면은 `/security/home`에서 기존 인증을 거쳐 확인한다.
- HTML 진입점은 Vite 개발 서버 전용이며 기본 프로덕션 빌드에 포함하지 않는다.
- 개발 서버 전용 의존성 캐시를 사용해 테스트 실행과의 캐시 충돌을 피한다.
- 실제 브라우저의 모션·반응형·키보드·reduced-motion 확인은 자동 검사와 별도로 수행한다.
