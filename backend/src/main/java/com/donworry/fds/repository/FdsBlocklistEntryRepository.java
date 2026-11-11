package com.donworry.fds.repository;

import com.donworry.fds.entity.FdsBlocklistEntry;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FdsBlocklistEntryRepository extends JpaRepository<FdsBlocklistEntry, Long> {
    List<FdsBlocklistEntry> findByIdentifierTypeAndIdentifierValue(String identifierType, String identifierValue);

    Page<FdsBlocklistEntry> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // 식별값은 전체가 같을 때만 찾는다. 일부 검색을 열어 두면 가린 원문을 한 글자씩 맞힐 수 있다 (#629, ISMS-P 2.6.3)
    // 카드 표시 이름(이미 가린 값)과 사유는 일부만 맞아도 찾는다. 빈 검색어는 사유 조건으로 전체가 나온다
    @Query("""
            select entry from FdsBlocklistEntry entry
            where entry.active = true
              and (entry.expiresAt is null or entry.expiresAt > ?1)
              and (lower(entry.identifierValue) = lower(?2)
                   or lower(coalesce(entry.displayLabel, '')) like lower(concat('%', ?2, '%'))
                   or lower(entry.reason) like lower(concat('%', ?2, '%')))
            order by entry.createdAt desc
            """)
    Page<FdsBlocklistEntry> findActiveAtWithSearch(Instant at, String search, Pageable pageable);

    @Query("""
            select entry from FdsBlocklistEntry entry
            where (entry.active = false or (entry.expiresAt is not null and entry.expiresAt <= ?1))
              and (lower(entry.identifierValue) = lower(?2)
                   or lower(coalesce(entry.displayLabel, '')) like lower(concat('%', ?2, '%'))
                   or lower(entry.reason) like lower(concat('%', ?2, '%')))
            order by entry.createdAt desc
            """)
    Page<FdsBlocklistEntry> findInactiveAtWithSearch(Instant at, String search, Pageable pageable);
}
