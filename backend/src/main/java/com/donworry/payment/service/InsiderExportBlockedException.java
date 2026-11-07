package com.donworry.payment.service;

// 열린 내부자 경보로 내보내기가 막힘 (#651) — 계정 정지는 문지기(fds InsiderExportGuard)가 이미 끝냈다
public class InsiderExportBlockedException extends RuntimeException {
    public InsiderExportBlockedException() {
        super("insider export blocked");
    }
}
