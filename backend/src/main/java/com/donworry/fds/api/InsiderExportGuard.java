package com.donworry.fds.api;

// 내보내기 문지기 (ADR-0082 INS-05, #651) — 보안 직원이 지금 거래 내역을 파일로 내보내도 되는지
// 열린 내부자 경보가 있으면 INS-05 위험 경보 · 계정 보안 정지(세션 만료)까지 하고 false. 둘 다 호출 쪽 롤백과 따로 확정된다
// 호출 쪽은 false면 데이터를 조회 · 반환하지 않는다. AI 보고서를 기다리지 않는다. ADMIN은 부르지 않는다
public interface InsiderExportGuard {
    boolean allowExport(Long staffId);
}
