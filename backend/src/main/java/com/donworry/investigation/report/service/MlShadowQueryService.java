package com.donworry.investigation.report.service;

import com.donworry.investigation.report.dto.MlShadowScoreResponse;
import com.donworry.investigation.report.repository.MlShadowScoreRepository;
import com.donworry.investigation.service.InvestigationErrorCode;
import com.donworry.investigation.service.InvestigationException;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 거래 내역 "ML 참고" 칸 조회 (#441) — 화면이 한 페이지의 결제 번호로 부른다. 읽기만 한다
@Service
@RequiredArgsConstructor
public class MlShadowQueryService {

    // 사건 칸 조회(payment-links)와 같은 상한 — 거래 내역 한 페이지 최대 100건
    static final int MAX_PAYMENT_IDS = 100;

    private final MlShadowScoreRepository mlShadowScoreRepository;
    private final MlShadowProperties properties;

    // 지금 설정한 모델 버전의 기록만 준다 (결제당 1건). 기록이 없는 결제는 빠지고, 화면이 "기록 없음"으로 그린다
    @Transactional(readOnly = true)
    public List<MlShadowScoreResponse> findByPayments(List<Long> paymentIds) {
        List<Long> ids = paymentIds == null
                ? List.of()
                : paymentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > MAX_PAYMENT_IDS) {
            throw new InvestigationException(InvestigationErrorCode.PAYMENT_IDS_OUT_OF_RANGE);
        }
        return mlShadowScoreRepository.findByModelVersionAndPaymentIdIn(properties.modelVersion(), ids).stream()
                .map(MlShadowScoreResponse::from)
                .toList();
    }
}
