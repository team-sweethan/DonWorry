package com.donworry.investigation.report.service;

import com.donworry.fds.api.InsiderRule;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.api.StaffAccessResult;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.investigation.entity.InsiderCase;
import com.donworry.investigation.entity.InsiderCaseAlert;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// 해제 검토 보고서 본문 (#675) — 사실은 코드가 세고, 설명은 걸린 규칙에 맞춘 고정 문장(AI 미사용)
// 고객 번호 · 세션 번호 · IP · 원문은 값으로 넣지 않는다. 고객은 "서로 다른 몇 명"만 센다
// 추천 문장(풀어 주세요 · 유지하세요)은 쓰지 않는다 — 판정은 관리자가 한다 (ADR-0082)
final class InsiderReportFacts {

    static final String KIND = "INSIDER_RELEASE_REVIEW";

    // 접근 기록 종류 — 보고서는 그때의 모습으로 남기므로 화면 이름도 본문에 담는다
    private static final Map<StaffAccessType, String> ACCESS_LABELS = accessLabels();

    private InsiderReportFacts() {}

    static Map<String, Object> body(
            InsiderCase insiderCase,
            String staffName,
            boolean accountLocked,
            List<InsiderCaseAlert> alerts,
            List<StaffAccessRecorded> events,
            Map<Long, String> caseCustomers,
            Instant now) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("insiderCaseId", insiderCase.getId());
        facts.put("staffId", insiderCase.getStaffId());
        facts.put("staffName", staffName);
        facts.put("accountLocked", accountLocked);
        facts.put("caseSeverity", insiderCase.getSeverity().name());
        facts.put("openedAt", insiderCase.getOpenedAt().toString());
        facts.put("alerts", alerts.stream().map(InsiderReportFacts::alertRow).toList());
        facts.put("access", access(events, caseCustomers));

        Set<InsiderRule> rules =
                alerts.stream().map(InsiderCaseAlert::getRuleCode).collect(Collectors.toSet());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", KIND);
        body.put("generatedAt", now.toString());
        body.put("facts", facts);
        body.put("narrative", fixedNarrative(rules, accountLocked));
        body.put(
                "limits",
                List.of(
                        "고객 번호 · IP · 주문자 원문은 이 보고서에 넣지 않았습니다.",
                        "숫자는 경보 근거로 묶인 접근 기록만 셉니다. 경보와 관계없는 다른 업무 기록은 세지 않았습니다.",
                        "이 판은 만든 시각의 기록입니다. 그 뒤에 생긴 경보 · 기록은 다음 판이나 사건 상세에서 확인하세요."));
        return body;
    }

    private static Map<String, Object> alertRow(InsiderCaseAlert alert) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("alertId", alert.getAlertId());
        row.put("ruleCode", alert.getRuleCode().code());
        row.put("ruleLabel", alert.getRuleCode().label());
        row.put("severity", alert.getSeverity().name());
        row.put("summary", alert.getSummary());
        row.put("raisedAt", alert.getRaisedAt().toString());
        return row;
    }

    // 전체: 건수 · 기간 · 서로 다른 고객 · 가맹점 · 사건 수. 종류별: 허용 · 거부 · 행 수 · 사건 수
    private static Map<String, Object> access(List<StaffAccessRecorded> events, Map<Long, String> caseCustomers) {
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("total", events.size());
        access.put("periodFrom", time(events, Comparator.naturalOrder()));
        access.put("periodTo", time(events, Comparator.reverseOrder()));
        Map<StaffAccessType, List<StaffAccessRecorded>> byType = new EnumMap<>(StaffAccessType.class);
        events.forEach(event -> byType.computeIfAbsent(event.accessType(), type -> new ArrayList<>())
                .add(event));
        access.put(
                "byType",
                byType.entrySet().stream()
                        .map(entry -> typeRow(entry.getKey(), entry.getValue()))
                        .toList());
        customers(events, caseCustomers, access);
        access.put("distinctMerchants", distinct(events, StaffAccessRecorded::merchantId));
        access.put("distinctCases", distinct(events, StaffAccessRecorded::caseId));
        return access;
    }

    private static Map<String, Object> typeRow(StaffAccessType type, List<StaffAccessRecorded> rows) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type.name());
        row.put("label", ACCESS_LABELS.getOrDefault(type, type.name()));
        row.put(
                "allowed",
                rows.stream()
                        .filter(e -> e.result() == StaffAccessResult.ALLOWED)
                        .count());
        row.put(
                "denied",
                rows.stream()
                        .filter(e -> e.result() == StaffAccessResult.DENIED)
                        .count());
        // 거래 목록 조회 · 내보내기의 행 수 합 — 다른 종류는 0
        row.put(
                "items",
                rows.stream()
                        .map(StaffAccessRecorded::itemCount)
                        .filter(Objects::nonNull)
                        .mapToLong(Integer::longValue)
                        .sum());
        row.put("cases", distinct(rows, StaffAccessRecorded::caseId));
        return row;
    }

    // 서로 다른 고객 수 — 기록의 고객 번호, 없으면 사건 번호로 찾은 사건 고객 (#675 Codex 리뷰: 원문 열람 기록엔 고객 번호가 없다)
    // 사건 · 결제를 가리키는데 고객을 못 찾은 기록은 0명으로 숨기지 않고 따로 센다. 고객 번호 값은 본문에 넣지 않는다
    private static void customers(
            List<StaffAccessRecorded> events, Map<Long, String> caseCustomers, Map<String, Object> access) {
        Set<String> customers = new HashSet<>();
        long unresolved = 0;
        for (StaffAccessRecorded event : events) {
            String ref = event.customerRef() != null
                    ? event.customerRef()
                    : event.caseId() == null ? null : caseCustomers.get(event.caseId());
            if (ref != null) {
                customers.add(ref);
            } else if (event.caseId() != null || event.paymentId() != null) {
                unresolved++;
            }
        }
        access.put("distinctCustomers", (long) customers.size());
        access.put("unresolvedCustomerRecords", unresolved);
    }

    private static String time(List<StaffAccessRecorded> events, Comparator<Instant> order) {
        return events.stream()
                .map(StaffAccessRecorded::occurredAt)
                .filter(Objects::nonNull)
                .min(order)
                .map(Instant::toString)
                .orElse(null);
    }

    private static long distinct(List<StaffAccessRecorded> events, Function<StaffAccessRecorded, ?> key) {
        return events.stream().map(key).filter(Objects::nonNull).distinct().count();
    }

    // 고정 설명 — 걸린 규칙에 맞춰 고른다. 사실을 해석하지 않고, 판정 전에 확인할 곳만 알려 준다
    private static Map<String, Object> fixedNarrative(Set<InsiderRule> rules, boolean accountLocked) {
        List<String> normal = new ArrayList<>();
        normal.add("코드는 행동의 횟수와 순서만 셌습니다. 업무상 이유가 있었는지는 본인 소명과 담당 사건 기록으로 확인해야 합니다.");
        if (rules.contains(InsiderRule.INS_01)) {
            normal.add("담당이 아닌 사건에 접근하거나 담당이 아닌 고객의 거래를 내보내려 한 시각에 담당 변경 요청 · 인수인계가 있었는지 사건 배정 이력으로 확인할 수 있습니다.");
        }
        if (rules.contains(InsiderRule.INS_03)) {
            normal.add(
                    "전체 전화번호 보기는 매번 근거(이의제기 번호 · 가맹점 연락 내역 · 사유)를 남겼습니다. 그 근거가 실제 사건과 맞는지 [원문 열람 검토] 화면에서 확인할 수 있습니다.");
        }
        if (rules.contains(InsiderRule.INS_07)) {
            normal.add("처음 보는 브라우저로 로그인한 직후의 행동이라, 본인이 아닐 가능성(계정 도용)도 함께 확인해야 합니다.");
        }
        if (rules.contains(InsiderRule.INS_08) || rules.contains(InsiderRule.INS_09)) {
            normal.add("거래 목록을 많이 보거나 여러 고객을 검색한 것은 가맹점 문의 · 차지백 처리처럼 담당 사건과 이어진 업무였다면 설명될 수 있습니다.");
        }

        List<String> released = new ArrayList<>();
        released.add("정지를 풀면 이 직원은 다시 고객정보 화면을 쓸 수 있습니다. 같은 행동이 반복되면 규칙이 다시 경보를 띄웁니다.");
        if (rules.contains(InsiderRule.INS_05)) {
            released.add("차단된 내보내기는 실행되지 않았습니다. 막힌 시도로는 파일이 만들어지지 않았습니다.");
        }

        List<String> kept = new ArrayList<>();
        kept.add("정지를 유지하면 이 직원은 로그인할 수 없고, 남은 FDS 사건은 다른 직원에게 넘겨야 판정이 이어집니다.");
        if (!accountLocked) {
            kept.add("이 판을 만들 때 계정은 정지되지 않은 상태였습니다. 판정에서 [징계 요청]을 고르면 정지됩니다.");
        }

        Map<String, Object> narrative = new LinkedHashMap<>();
        narrative.put("source", "FIXED");
        narrative.put("sourceLabel", "자동 문장 (AI 미사용)");
        narrative.put("normalExplanation", normal);
        narrative.put("ifReleased", released);
        narrative.put("ifKept", kept);
        return narrative;
    }

    private static Map<StaffAccessType, String> accessLabels() {
        Map<StaffAccessType, String> labels = new EnumMap<>(StaffAccessType.class);
        labels.put(StaffAccessType.CASE_DETAIL_VIEW, "사건 상세 열기");
        labels.put(StaffAccessType.CASE_DECISION, "판정");
        labels.put(StaffAccessType.REPORT_REQUEST, "보고서 요청");
        labels.put(StaffAccessType.HANDOFF_RECHECK, "인계 다시 확인");
        labels.put(StaffAccessType.REPORT_PROGRESS_VIEW, "보고서 진행 조회");
        labels.put(StaffAccessType.REPORT_PRINT, "보고서 인쇄");
        labels.put(StaffAccessType.TRANSACTION_LIST_VIEW, "거래 목록 조회");
        labels.put(StaffAccessType.TRANSACTION_EXPORT, "거래 내역 내보내기");
        labels.put(StaffAccessType.CONTACT_MASKED_VIEW, "주문자 정보(가림) 보기");
        labels.put(StaffAccessType.CONTACT_MATCH, "전화번호 일치 확인");
        labels.put(StaffAccessType.CONTACT_MATCH_HIT, "전화번호 일치 확인 · 일치");
        labels.put(StaffAccessType.CONTACT_MATCH_MISS, "전화번호 일치 확인 · 불일치");
        labels.put(StaffAccessType.CONTACT_RAW_VIEW, "전체 전화번호 보기");
        return labels;
    }
}
