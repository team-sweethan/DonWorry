package com.donworry.fds.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.fds.entity.FdsBlocklistEntry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

// 차단 목록 검색: 식별값은 전체 일치, 사유는 일부 일치 (#629)
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class FdsBlocklistSearchMariaDbTests {

    private static final String EMAIL = "mask.target@example.test";
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 10);

    @Autowired
    private FdsBlocklistEntryRepository entries;

    @BeforeEach
    void setUp() {
        entries.deleteAll();
    }

    // 가린 값을 보고 검색어를 한 글자씩 늘려 원문을 맞히지 못하게 한다
    @Test
    void findActiveAtWithSearch_whenPartialIdentifier_returnsNothing() {
        entries.save(FdsBlocklistEntry.active("BUYER_EMAIL", EMAIL, "카드 도용 신고", Instant.now()));

        var result = entries.findActiveAtWithSearch(Instant.now(), "mas", FIRST_PAGE);

        assertThat(result.getContent()).isEmpty();
    }

    // 전체 값을 알면 대소문자가 달라도 찾는다
    @Test
    void findActiveAtWithSearch_whenFullIdentifierDifferentCase_returnsEntry() {
        var saved = entries.save(FdsBlocklistEntry.active("BUYER_EMAIL", EMAIL, "카드 도용 신고", Instant.now()));

        var result = entries.findActiveAtWithSearch(Instant.now(), "Mask.Target@Example.TEST", FIRST_PAGE);

        assertThat(result.getContent())
                .extracting(FdsBlocklistEntry::getBlocklistEntryId)
                .containsExactly(saved.getBlocklistEntryId());
    }

    // 사유는 지금처럼 일부만 입력해도 찾는다
    @Test
    void findActiveAtWithSearch_whenPartialReason_returnsEntry() {
        var saved = entries.save(FdsBlocklistEntry.active("BUYER_EMAIL", EMAIL, "카드 도용 신고", Instant.now()));

        var result = entries.findActiveAtWithSearch(Instant.now(), "도용", FIRST_PAGE);

        assertThat(result.getContent())
                .extracting(FdsBlocklistEntry::getBlocklistEntryId)
                .containsExactly(saved.getBlocklistEntryId());
    }

    // 해제된 목록 탭도 같은 규칙으로 막는다
    @Test
    void findInactiveAtWithSearch_whenPartialIdentifier_returnsNothing() {
        var entry = FdsBlocklistEntry.active("BUYER_EMAIL", EMAIL, "카드 도용 신고", Instant.now());
        entry.deactivate("오탐 확인", Instant.now());
        entries.save(entry);

        var partial = entries.findInactiveAtWithSearch(Instant.now(), "mas", FIRST_PAGE);
        var full = entries.findInactiveAtWithSearch(Instant.now(), EMAIL, FIRST_PAGE);

        assertThat(partial.getContent()).isEmpty();
        assertThat(full.getContent()).hasSize(1);
    }
}
