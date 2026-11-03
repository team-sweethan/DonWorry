package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.investigation.report.service.RuleCriterionFormatter.Criterion;
import org.junit.jupiter.api.Test;

class RuleCriterionFormatterTests {

    // 차단 목록 규칙은 카드 토큰 · IP · 이메일이 든 실제값을 문장으로도 내보내지 않는다
    @Test
    void format_blocklistRule_returnsNoIdentifiers() {
        String observed = "{\"buyerRef\":\"buyer-1\",\"deviceId\":\"dev-9\",\"cardToken\":\"tok_abc\","
                + "\"cardDisplayLabel\":\"신한 1234\",\"ipAddress\":\"10.1.2.3\",\"buyerEmail\":\"a@b.com\","
                + "\"matchedIdentifierTypes\":[\"CARD\"]}";

        assertThat(RuleCriterionFormatter.format("R07", "{\"active\":true}", observed))
                .isEmpty();
    }

    // 고액 규칙은 기준과 이번 결제 금액을 만 원 단위로 보여 준다
    @Test
    void format_highAmountRule_returnsCriterionAndObservedInManWon() {
        assertThat(RuleCriterionFormatter.format("R01", "{\"amountGte\":500000,\"score\":30}", "{\"amount\":600000}"))
                .contains(new Criterion("50만 원 이상", "60만 원"));
    }

    // 깨진 JSON이나 빠진 숫자는 문장을 만들지 않는다 — 보고서 생성은 그대로 이어진다
    @Test
    void format_brokenJson_returnsNull() {
        assertThat(RuleCriterionFormatter.format("R01", "{\"amountGte\":", "{\"amount\":600000}"))
                .isEmpty();
        assertThat(RuleCriterionFormatter.format("R04", "{\"windowMinutes\":10}", "{\"attemptCount\":3}"))
                .isEmpty();
    }
}
