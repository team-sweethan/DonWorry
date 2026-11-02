package com.donworry.investigation.report.entity;

import com.donworry.fds.api.FdsMlTarget;
import com.donworry.fds.api.FdsResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 커밋된 FDS 평가를 ML로 따로 채점한 기록 — 결제 판정에 쓰지 않고 Rule 결과와 나란히 비교만 한다 (ADR-0062)
@Entity
@Table(name = "ml_shadow_scores")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MlShadowScore {

    // ERD 비율 규칙 DECIMAL(9,6)과 같은 자릿수 — 학습 코드의 부동소수 오차(0.30000000000000004)도 여기서 정리된다
    private static final int RATE_SCALE = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shadow_score_id")
    private Long id;

    // P5 payments 논리 참조 — 소유 담당이 달라 연관관계를 걸지 않는다
    private Long paymentId;

    // P2 fds_evaluations 논리 참조 — 스케줄러 커서 기준
    private Long evaluationId;

    private String modelVersion;

    @Enumerated(EnumType.STRING)
    private MlShadowStatus status;

    // 평가 당시 Rule 결과 스냅샷 — 비교 조회를 이 테이블 하나로 하기 위해 둔다
    @Enumerated(EnumType.STRING)
    private FdsResult ruleResult;

    private Integer ruleRiskScore;

    @Column(precision = 9, scale = 6)
    private BigDecimal fraudProbability;

    @Column(precision = 9, scale = 6)
    private BigDecimal threshold;

    @Enumerated(EnumType.STRING)
    private MlRiskLevel mlRiskLevel;

    // 모델 입력값 — 확률 근거 확인용
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> features;

    private Integer latencyMs;

    private String failureCode;

    private int attemptCount;

    private Instant scoredAt;

    private Instant createdAt;

    private Instant updatedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private MlShadowScore(
            FdsMlTarget target,
            String modelVersion,
            MlShadowStatus status,
            MlScoreValues values,
            String failureCode,
            int attemptCount,
            Instant now) {
        Objects.requireNonNull(target, "target");
        // ERROR 평가는 Rule 판정이 없어 비교 대상이 아니다 (fds/api findNewTargets가 이미 거른다)
        if (target.result() == null || target.result() == FdsResult.ERROR) {
            throw new IllegalArgumentException("ML Shadow 대상은 ERROR가 아닌 Rule 판정이어야 합니다");
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion은 비어 있을 수 없습니다");
        }
        // DATETIME(6)에 저장되는 값과 같게 마이크로초로 맞춘다
        Instant at = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
        this.paymentId = Objects.requireNonNull(target.paymentId(), "paymentId");
        this.evaluationId = Objects.requireNonNull(target.evaluationId(), "evaluationId");
        this.modelVersion = modelVersion;
        this.status = status;
        this.ruleResult = target.result();
        this.ruleRiskScore = target.riskScore();
        this.failureCode = failureCode;
        this.attemptCount = attemptCount;
        this.createdAt = at;
        this.updatedAt = at;
        if (values != null) {
            this.fraudProbability = toRate(values.fraudProbability());
            this.threshold = toRate(values.threshold());
            this.mlRiskLevel = Objects.requireNonNull(values.mlRiskLevel(), "mlRiskLevel");
            this.features = new LinkedHashMap<>(Objects.requireNonNull(values.features(), "features"));
            this.latencyMs = values.latencyMs();
            this.scoredAt = at;
        }
    }

    // ML 채점 성공 — 시도 1회, 채점 시각 = now
    public static MlShadowScore succeeded(FdsMlTarget target, String modelVersion, MlScoreValues values, Instant now) {
        return MlShadowScore.builder()
                .target(target)
                .modelVersion(modelVersion)
                .status(MlShadowStatus.SUCCEEDED)
                .values(Objects.requireNonNull(values, "values"))
                .attemptCount(1)
                .now(now)
                .build();
    }

    // 다시 시도할 수 있는 실패 — 시도 1회. 재시도 때 갱신하는 메서드는 실행 조각(Ⓔ-4)에서 추가한다
    public static MlShadowScore failed(FdsMlTarget target, String modelVersion, String failureCode, Instant now) {
        return MlShadowScore.builder()
                .target(target)
                .modelVersion(modelVersion)
                .status(MlShadowStatus.FAILED)
                .failureCode(requireCode(failureCode))
                .attemptCount(1)
                .now(now)
                .build();
    }

    // ai-server를 부르지 않고 건너뜀 (예: 이력 상한 초과) — 시도 0회
    public static MlShadowScore skipped(FdsMlTarget target, String modelVersion, String failureCode, Instant now) {
        return MlShadowScore.builder()
                .target(target)
                .modelVersion(modelVersion)
                .status(MlShadowStatus.SKIPPED)
                .failureCode(requireCode(failureCode))
                .attemptCount(0)
                .now(now)
                .build();
    }

    private static String requireCode(String failureCode) {
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode는 비어 있을 수 없습니다");
        }
        return failureCode;
    }

    private static BigDecimal toRate(double value) {
        return BigDecimal.valueOf(value).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }
}
