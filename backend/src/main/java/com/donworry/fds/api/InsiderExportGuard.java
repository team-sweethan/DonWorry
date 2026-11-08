package com.donworry.fds.api;

// 내보내기 문지기 (ADR-0082 INS-05, #651) — 보안 직원이 지금 거래 내역을 파일로 내보내도 되는지
// 높음 이상 내부자 경보가 열려 있으면 INS-05 위험 경보 · 계정 보안 정지(세션 만료)까지 하고 false. 둘 다 호출 쪽 롤백과 따로 확정된다
// 주의 경보만 있으면 true — 허용된 내보내기는 접근 기록으로 INS-08 · 09 집계에 들어가 쌓이면 높음이 된다
// 호출 쪽은 false면 데이터를 조회 · 반환하지 않는다. AI 보고서를 기다리지 않는다. ADMIN은 부르지 않는다
public interface InsiderExportGuard {
    boolean allowExport(Long staffId);
}
