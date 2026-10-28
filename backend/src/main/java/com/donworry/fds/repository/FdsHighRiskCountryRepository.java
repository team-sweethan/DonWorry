package com.donworry.fds.repository;

import com.donworry.fds.entity.FdsHighRiskCountry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FdsHighRiskCountryRepository extends JpaRepository<FdsHighRiskCountry, Long> {
    boolean existsByCountryCodeAndActiveTrue(String countryCode);
}
