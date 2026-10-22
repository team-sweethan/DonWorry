package com.donworry.investigation.entity;

// 직원 최종 판정 (fds 정본 03 §3.3 ⑤)
public enum Decision {
    ACCEPT,
    REJECT;

    // ACCEPT는 P6 매입, REJECT는 P5 승인 취소로 인계한다 (fds 정본 03 §3.9 ⑦)
    public ActionType actionType() {
        return switch (this) {
            case ACCEPT -> ActionType.CAPTURE;
            case REJECT -> ActionType.VOID;
        };
    }
}
