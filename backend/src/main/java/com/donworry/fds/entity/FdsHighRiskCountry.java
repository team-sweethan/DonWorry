package com.donworry.fds.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "fds_high_risk_countries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FdsHighRiskCountry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long countryId;

    @Column(nullable = false, length = 2)
    private String countryCode;

    @Column(nullable = false)
    private boolean active;
}
