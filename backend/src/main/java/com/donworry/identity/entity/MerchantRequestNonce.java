package com.donworry.identity.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

// INSERT와 만료 DELETE만 제공한다. 업무 이력이나 키 저장소가 아니다.
@Entity
@Table(name = "merchant_request_nonces")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantRequestNonce {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long requestNonceId;

    @Column(nullable = false)
    private Long merchantId;

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID nonce;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant expiresAt;
}
