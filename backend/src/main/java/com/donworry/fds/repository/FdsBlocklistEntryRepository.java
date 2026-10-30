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

    @Query("""
            select entry from FdsBlocklistEntry entry
            where entry.active = true
              and (entry.expiresAt is null or entry.expiresAt > ?1)
              and (lower(entry.identifierValue) like lower(concat('%', ?2, '%'))
                   or lower(coalesce(entry.displayLabel, '')) like lower(concat('%', ?2, '%'))
                   or lower(entry.reason) like lower(concat('%', ?2, '%')))
            order by entry.createdAt desc
            """)
    Page<FdsBlocklistEntry> findActiveAtWithSearch(Instant at, String search, Pageable pageable);

    @Query("""
            select entry from FdsBlocklistEntry entry
            where (entry.active = false or (entry.expiresAt is not null and entry.expiresAt <= ?1))
              and (lower(entry.identifierValue) like lower(concat('%', ?2, '%'))
                   or lower(coalesce(entry.displayLabel, '')) like lower(concat('%', ?2, '%'))
                   or lower(entry.reason) like lower(concat('%', ?2, '%')))
            order by entry.createdAt desc
            """)
    Page<FdsBlocklistEntry> findInactiveAtWithSearch(Instant at, String search, Pageable pageable);
}
