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
        @DefaultValue("3") int ins03CautionRawViews) {}
