package com.donworry.fds.service;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 내부자 탐지 기준값 (#516) — 정상 업무를 재기 전 임시값. 바꿀 때는 application.yml donworry.insider.*
@Builder
@ConfigurationProperties(prefix = "donworry.insider")
public record InsiderDetectionProperties(
        // INS-01 주의: 이 시간 안에 서로 다른 사건 담당 밖 거부 N건
        @DefaultValue("10m") Duration ins01Window,
        @DefaultValue("3") int ins01CautionCases,
        // INS-03 주의: 이 시간 안에 주문자 전화 원문 열람 N건
        @DefaultValue("1h") Duration ins03Window,
        @DefaultValue("3") int ins03CautionRawViews,
        // INS-07 높음: 처음 보는 브라우저로 로그인한 뒤 이 시간 안 원문 열람 · 비교할 지난 성공 로그인 기간 (#643)
        @DefaultValue("30m") Duration ins07AfterLogin,
        @DefaultValue("30d") Duration ins07Lookback,
        // INS-08 주의: 이 시간 안 거래 목록 · 내보내기 행 합계가 N 이상이고 담당 사건과 이어진 행 비율이 이 값 미만
        // 높음(#651): 같은 조건에서 행 합계가 이 값 이상 — 높음부터 내보내기가 막힌다(주의에 머물며 계속 빼가지 못하게)
        @DefaultValue("1h") Duration ins08Window,
        @DefaultValue("1000") int ins08CautionRows,
        @DefaultValue("3000") int ins08HighRows,
        @DefaultValue("0.3") double ins08MaxLinkedRatio,
        // INS-09 주의: 이 시간 안 담당 사건에 없는 서로 다른 고객 번호 검색(목록 · 내보내기) N명. 높음(#651): 이 값 이상
        @DefaultValue("1h") Duration ins09Window,
        @DefaultValue("3") int ins09CautionCustomers,
        @DefaultValue("5") int ins09HighCustomers,
        // INS-08 · 09의 "담당 사건": 열린 사건 + 이 기간 안에 닫힌 사건 (닫은 뒤 차지백 · 후속 문의)
        @DefaultValue("30d") Duration scopeClosedLookback) {}
