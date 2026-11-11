package com.donworry.investigation.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.investigation.repository.InsiderCaseAlertRepository;
import com.donworry.investigation.service.InsiderCaseChanged;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 해제 검토 보고서 사실 읽기가 순간 실패한 뒤 재전달로 판이 만들어지는지 실제 event_publication으로 확인한다 (#702)
// 스케줄러가 저절로 돌지 않게 간격을 길게 두고 run()을 직접 부른다 (PaymentRefundCompletionMariaDbTests와 같은 방식)
@Tag("mariadb")
@SpringBootTest(properties = "donworry.insider-report.redelivery-delay=PT1H")
@Import(MariaDbTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InsiderReportRedeliveryMariaDbTests {

    @Autowired
    InsiderReportRedeliveryScheduler redelivery;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcTemplate jdbc;

    // 사실 읽기의 첫 조회 — 여기서 순간 DB 오류를 흉내 낸다
    @MockitoSpyBean
    InsiderCaseAlertRepository links;

    private Long insiderCaseId;

    @AfterEach
    void clean() {
        if (insiderCaseId == null) return;
        jdbc.update(
                "DELETE FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT,'$.insiderCaseId')=?",
                InsiderCaseChanged.class.getName(),
                insiderCaseId);
        jdbc.update("DELETE FROM ai_insider_reports WHERE insider_case_id=?", insiderCaseId);
        jdbc.update("DELETE FROM insider_case_alerts WHERE insider_case_id=?", insiderCaseId);
        jdbc.update("DELETE FROM insider_cases WHERE insider_case_id=?", insiderCaseId);
    }

    // 사실 읽기가 실패하면 판 없이 발행이 FAILED로 남고, 재전달 뒤에는 정상 판 하나만 생긴다 — 실패 판이 계기를 막지 않는다
    @Test
    void insiderReportRedelivery_afterTransientFailure_savesSingleSucceededVersion() {
        long alertId = openCase();
        doThrow(new QueryTimeoutException("test transient read failure"))
                .when(links)
                .findByInsiderCaseIdOrderByRaisedAtAscAlertIdAsc(insiderCaseId);

        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> events.publishEvent(
                        new InsiderCaseChanged(insiderCaseId, InsiderCaseChanged.Reason.OPENED, alertId)));

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(publication().get("STATUS")).isEqualTo("FAILED"));
        // 재시도 상한은 실패마다 시도 횟수가 쌓이는 것에 기대므로 실제 저장값을 확인한다
        assertThat(((Number) publication().get("COMPLETION_ATTEMPTS")).intValue())
                .isEqualTo(1);
        assertThat(reports()).isEmpty();
        reset(links);

        redelivery.run();

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertThat(publication().get("COMPLETION_DATE")).isNotNull());
        assertThat(reports()).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("SUCCEEDED");
            assertThat(((Number) row.get("report_version")).intValue()).isEqualTo(1);
            assertThat(row.get("trigger_type")).isEqualTo("CASE_OPENED");
        });
    }

    // 열린 내부자 사건과 계기 경보 하나 — 직원 · 경보 번호는 다른 테스트와 겹치지 않게 무작위
    private long openCase() {
        long staffId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        long alertId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        jdbc.update(
                "INSERT INTO insider_cases(staff_id,status,severity,opened_at,updated_at) VALUES (?,'OPEN','HIGH',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                staffId);
        insiderCaseId = jdbc.queryForObject(
                "SELECT insider_case_id FROM insider_cases WHERE staff_id=? AND status='OPEN'", Long.class, staffId);
        jdbc.update(
                "INSERT INTO insider_case_alerts(alert_id,insider_case_id,rule_code,severity,summary,raised_at,linked_at) VALUES (?,?,'INS_01','HIGH','redelivery test',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                alertId,
                insiderCaseId);
        return alertId;
    }

    private Map<String, Object> publication() {
        return jdbc.queryForMap(
                "SELECT STATUS, COMPLETION_ATTEMPTS, COMPLETION_DATE FROM EVENT_PUBLICATION WHERE EVENT_TYPE=? AND JSON_VALUE(SERIALIZED_EVENT,'$.insiderCaseId')=?",
                InsiderCaseChanged.class.getName(),
                insiderCaseId);
    }

    private List<Map<String, Object>> reports() {
        return jdbc.queryForList(
                "SELECT status, report_version, trigger_type FROM ai_insider_reports WHERE insider_case_id=?",
                insiderCaseId);
    }
}
