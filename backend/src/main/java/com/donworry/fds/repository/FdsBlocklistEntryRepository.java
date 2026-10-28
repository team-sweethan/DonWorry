package com.donworry.fds.repository;

import com.donworry.fds.entity.FdsBlocklistEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FdsBlocklistEntryRepository extends JpaRepository<FdsBlocklistEntry, Long> {
    List<FdsBlocklistEntry> findByIdentifierTypeAndIdentifierValue(String identifierType, String identifierValue);
}
