package com.donworry.payment.service;

// 내보낼 행이 상한을 넘음 (#651) — 잘라서 주지 않는다. 조건을 좁혀 다시 받게 한다
public class ExportTooLargeException extends RuntimeException {
    private final int maxRows;

    public ExportTooLargeException(int maxRows) {
        super("export rows exceed " + maxRows);
        this.maxRows = maxRows;
    }

    public int maxRows() {
        return maxRows;
    }
}
