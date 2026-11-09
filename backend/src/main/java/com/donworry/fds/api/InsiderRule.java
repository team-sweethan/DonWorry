package com.donworry.fds.api;

// 내부자 탐지 규칙 (#516, ADR-0082) — INS-07 · 08 · 09는 6b(#643), INS-05는 7b(#651)
// code = 화면 · 응답 · 경보 문장에 보이는 표시 코드(SEC-INS-0x, #664). 저장은 enum 이름(INS_0x) 그대로
public enum InsiderRule {
    INS_01("SEC-INS-01", "담당 밖 접근 반복"),
    INS_03("SEC-INS-03", "전화번호 열람 집중"),
    // 접근 기록이 아니라 내보내기 문지기(InsiderExportGuard)가 연다. 높음 이상 경보가 열려 있을 때만 (주의만이면 허용)
    INS_05("SEC-INS-05", "높음 경보 중 내보내기 시도"),
    INS_06("SEC-INS-06", "경보 겹침"),
    INS_07("SEC-INS-07", "처음 보는 브라우저 로그인 직후 전화번호 열람"),
    // 거래 목록 조회와 허용된 내보내기를 함께 센다 (#651)
    INS_08("SEC-INS-08", "업무 대비 거래 목록 · 내보내기 대량 조회"),
    INS_09("SEC-INS-09", "담당 무관 고객 반복 검색");

    private final String code;
    private final String label;

    InsiderRule(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
