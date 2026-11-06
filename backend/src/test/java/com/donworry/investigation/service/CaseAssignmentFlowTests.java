package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.SessionUserTestSupport;
import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.entity.AssignmentRequestStatus;
import com.donworry.investigation.entity.CaseAssignmentRequest;
import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.CaseHistoryEventType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CaseAssignmentRequestRepository;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

// FDS 사건 자동 배정 · 담당 변경 (#608). 보안 직원 목록은 P1 공개 계약 대역으로 정하고,
// 직원 ID는 테스트마다 새로 만들어 같은 DB의 다른 테스트 사건 건수와 섞이지 않게 한다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("mariadb")
@SpringBootTest
@AutoConfigureMockMvc
@Import({MariaDbTestContainerConfig.class, TestPaymentInfoStore.class, TestFinancialActionGateway.class})
class CaseAssignmentFlowTests {

    private static final AtomicLong NEXT_STAFF_ID = new AtomicLong(9_000_000_000L + System.nanoTime() % 1_000_000_000L);
    private static final Long ADMIN_ID = 1L;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    CaseIntakeService caseIntakeService;

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    CaseHistoryRepository caseHistoryRepository;

    @Autowired
    CaseAssignmentRequestRepository requestRepository;

    @Autowired
    TestPaymentInfoStore payments;

    @Autowired
    TransactionTemplate transactionTemplate;

    @MockitoBean
    CurrentStaff currentStaff;

    @MockitoBean
    SecurityStaffDirectory staffDirectory;

    // 활성 직원이 없어도 사건은 만들어진다 — 배정 실패로 HELD 결제가 사건 없이 남으면 아무도 판정할 수 없다
    @Test
    void receive_withoutActiveStaff_createsUnassignedCase() {
        staffAre(new SecurityStaffEntry(newStaffId(), "정지", false));

        Long caseId = receive(customer());

        assertThat(caseOf(caseId).getAssigneeStaffId()).isNull();
        assertThat(eventTypes(caseId))
                .contains(CaseHistoryEventType.CASE_CREATED)
                .doesNotContain(CaseHistoryEventType.CASE_ASSIGNED);
    }

    // 동시에 생긴 사건도 미종결 담당 수가 고르게 나뉜다 — 배정 잠금 뒤 스냅숏으로 세지 않으면 한 직원에게 몰린다
    @Test
    void receive_newCasesConcurrently_spreadAcrossStaff() throws Exception {
        Long a = newStaffId();
        Long b = newStaffId();
        Long c = newStaffId();
        staffAre(
                new SecurityStaffEntry(a, "가", true),
                new SecurityStaffEntry(b, "나", true),
                new SecurityStaffEntry(c, "다", true));

        int cases = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> created = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(cases)) {
            for (int i = 0; i < cases; i++) {
                String customer = customer();
                Callable<Long> receive = () -> {
                    start.await();
                    return receive(customer);
                };
                created.add(executor.submit(receive));
            }
            start.countDown();
            List<Long> assignees = new ArrayList<>();
            for (Future<Long> caseId : created) {
                assignees.add(caseOf(caseId.get(60, TimeUnit.SECONDS)).getAssigneeStaffId());
            }

            assertThat(assignees.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting())))
                    .isEqualTo(Map.of(a, 2L, b, 2L, c, 2L));
        }
    }

    // 결제가 기존 사건에 병합되면 담당은 처음 담당 그대로다 — 조사 중에 담당이 바뀌지 않게
    @Test
    void receive_mergedPayment_keepsAssignee() {
        Long first = newStaffId();
        staffAre(new SecurityStaffEntry(first, "나중", true));
        String customer = customer();
        Long caseId = receive(customer);

        // 건수가 더 적고 이름도 앞서는 직원이 생겨도 병합 사건은 다시 배정하지 않는다
        staffAre(new SecurityStaffEntry(newStaffId(), "가장먼저", true), new SecurityStaffEntry(first, "나중", true));
        Long mergedCaseId = receive(customer);

        assertThat(mergedCaseId).isEqualTo(caseId);
        assertThat(caseOf(caseId).getAssigneeStaffId()).isEqualTo(first);
    }

    // 담당 변경 요청이 대기 중이면 판정을 거부한다 — 바뀌기 전 담당이 판정하지 않게
    @Test
    void decide_whileAssignmentRequestPending_isConflict() throws Exception {
        Long assignee = newStaffId();
        staffAre(new SecurityStaffEntry(assignee, "담당", true));
        Long caseId = receive(customer());
        loginAs(assignee, "SECURITY_STAFF");
        perform(requestChange(caseId, "CONFLICT_OF_INTEREST", null)).andExpect(status().isOk());

        perform(post("/api/security-cases/{caseId}/decisions", caseId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "evidenceVersion",
                                1,
                                "decisions",
                                List.of(Map.of(
                                        "casePaymentId",
                                        firstCasePaymentId(caseId),
                                        "decision",
                                        "ACCEPT",
                                        "reason",
                                        "판정 사유"))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INV_020"));
    }

    // 담당이 아닌 보안 직원은 남의 사건 담당 변경을 요청하지 못한다
    @Test
    void requestChange_byNonAssignee_isForbidden() throws Exception {
        Long assignee = newStaffId();
        staffAre(new SecurityStaffEntry(assignee, "담당", true));
        Long caseId = receive(customer());
        loginAs(newStaffId(), "SECURITY_STAFF");

        perform(requestChange(caseId, "CONFLICT_OF_INTEREST", null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INV_018"));
        assertThat(requestRepository.findByCaseIdAndStatus(caseId, AssignmentRequestStatus.PENDING))
                .isEmpty();
    }

    // 한 사건에 대기 요청은 1건뿐이다 — 2건이 쌓이면 승인 결과가 엇갈린다
    @Test
    void requestChange_whilePending_isConflict() throws Exception {
        Long assignee = newStaffId();
        staffAre(new SecurityStaffEntry(assignee, "담당", true));
        Long caseId = receive(customer());
        loginAs(assignee, "SECURITY_STAFF");
        perform(requestChange(caseId, "CONFLICT_OF_INTEREST", null)).andExpect(status().isOk());

        perform(requestChange(caseId, "OTHER", "다시 요청"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INV_019"));
    }

    // 승인하면 새 담당으로 바뀌고 요청은 APPROVED, 이력에 승인한 ADMIN이 남는다
    @Test
    void approve_changesAssigneeAndRecordsHistory() throws Exception {
        Long assignee = newStaffId();
        Long next = newStaffId();
        staffAre(new SecurityStaffEntry(assignee, "담당", true), new SecurityStaffEntry(next, "새담당", true));
        Long caseId = assignedCase(assignee);
        Long requestId = pendingRequest(caseId, assignee);
        loginAs(ADMIN_ID, "ADMIN");

        perform(post("/api/admin/case-assignments/requests/{requestId}/approve", requestId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("newStaffId", next))))
                .andExpect(status().isOk());

        assertThat(caseOf(caseId).getAssigneeStaffId()).isEqualTo(next);
        CaseAssignmentRequest request = requestRepository.findById(requestId).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(AssignmentRequestStatus.APPROVED);
        assertThat(request.getNewAssigneeStaffId()).isEqualTo(next);
        CaseHistory assigned = lastOf(caseId, CaseHistoryEventType.CASE_ASSIGNED);
        assertThat(assigned.getActorId()).isEqualTo(ADMIN_ID);
        assertThat(assigned.getEventData()).containsEntry("reason", "REQUEST_APPROVED");
        assertThat(((Number) assigned.getEventData().get("requestId")).longValue())
                .isEqualTo(requestId);
    }

    // 거절하면 담당은 그대로이고 요청은 REJECTED, 거절 메모가 이력에 남는다
    @Test
    void reject_keepsAssignee() throws Exception {
        Long assignee = newStaffId();
        staffAre(new SecurityStaffEntry(assignee, "담당", true));
        Long caseId = assignedCase(assignee);
        Long requestId = pendingRequest(caseId, assignee);
        staffAre(new SecurityStaffEntry(assignee, "담당", true), new SecurityStaffEntry(newStaffId(), "다른", true));
        loginAs(ADMIN_ID, "ADMIN");

        perform(post("/api/admin/case-assignments/requests/{requestId}/reject", requestId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("memo", "이해충돌 아님"))))
                .andExpect(status().isOk());

        assertThat(caseOf(caseId).getAssigneeStaffId()).isEqualTo(assignee);
        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(AssignmentRequestStatus.REJECTED);
        assertThat(lastOf(caseId, CaseHistoryEventType.CASE_ASSIGNMENT_REQUEST_REJECTED)
                        .getEventData())
                .containsEntry("memo", "이해충돌 아님");
    }

    // 모두 넘기기는 미종결 사건만 옮기고, 대기 요청은 함께 승인한다 — 종료 사건 담당이 바뀌면 판정 기록과 어긋나고,
    // 대기 요청이 남으면 그 사건 판정이 계속 막힌다
    @Test
    void handOver_movesOnlyOpenCasesAndApprovesPendingRequests() throws Exception {
        Long from = newStaffId();
        Long x = newStaffId();
        Long y = newStaffId();
        staffAre(new SecurityStaffEntry(from, "넘김", true));
        Long openWithRequest = receive(customer());
        Long open = receive(customer());
        Long closed = receive(customer());
        transactionTemplate.executeWithoutResult(tx ->
                securityCaseRepository.findByIdForUpdate(closed).orElseThrow().close(Instant.now()));
        Long requestId = pendingRequest(openWithRequest, from);
        staffAre(
                new SecurityStaffEntry(from, "넘김", true),
                new SecurityStaffEntry(x, "가", true),
                new SecurityStaffEntry(y, "나", true));
        loginAs(ADMIN_ID, "ADMIN");

        perform(post("/api/admin/case-assignments/staff/{staffId}/handover", from)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("reason", "OTHER", "memo", "휴가"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved").value(2));

        assertThat(List.of(
                        caseOf(openWithRequest).getAssigneeStaffId(),
                        caseOf(open).getAssigneeStaffId()))
                .containsExactlyInAnyOrder(x, y);
        assertThat(caseOf(closed).getAssigneeStaffId()).isEqualTo(from);
        CaseAssignmentRequest request = requestRepository.findById(requestId).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(AssignmentRequestStatus.APPROVED);
        assertThat(request.getNewAssigneeStaffId())
                .isEqualTo(caseOf(openWithRequest).getAssigneeStaffId());
    }

    // ADMIN 담당 화면에는 고객 식별자가 나가지 않는다 — ADMIN은 FDS 사건 상세를 보지 않는다 (ADR-0065)
    @Test
    void adminAssignedCases_doNotExposeCustomerRef() throws Exception {
        Long assignee = newStaffId();
        staffAre(new SecurityStaffEntry(assignee, "담당", true));
        String customer = customer();
        Long caseId = receive(customer);
        pendingRequest(caseId, assignee);
        loginAs(ADMIN_ID, "ADMIN");

        String cases = perform(get("/api/admin/case-assignments/staff/{staffId}/cases", assignee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].caseId").value(caseId))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String requests = perform(get("/api/admin/case-assignments/requests"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(cases).doesNotContain("customerRef").doesNotContain(customer);
        assertThat(requests).doesNotContain("customerRef").doesNotContain(customer);
    }

    private Long assignedCase(Long assignee) {
        Long caseId = receive(customer());
        assertThat(caseOf(caseId).getAssigneeStaffId()).isEqualTo(assignee);
        return caseId;
    }

    private Long pendingRequest(Long caseId, Long assignee) throws Exception {
        loginAs(assignee, "SECURITY_STAFF");
        perform(requestChange(caseId, "CONFLICT_OF_INTEREST", null)).andExpect(status().isOk());
        return requestRepository
                .findByCaseIdAndStatus(caseId, AssignmentRequestStatus.PENDING)
                .orElseThrow()
                .getId();
    }

    private MockHttpServletRequestBuilder requestChange(Long caseId, String reason, String memo) throws Exception {
        Map<String, Object> body = memo == null ? Map.of("reason", reason) : Map.of("reason", reason, "memo", memo);
        return post("/api/security-cases/{caseId}/assignment-requests", caseId)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
    }

    // 리스너처럼 트랜잭션 밖에서 부른다 — 사건 연결은 자기 트랜잭션(REQUIRES_NEW)으로 커밋된다
    private Long receive(String customerRef) {
        Instant now = Instant.now();
        Long paymentId = payments.registerHeld(100_000L, 70, TestPaymentInfoStore.DEFAULT_RULES, now)
                .paymentId();
        return caseIntakeService.receive(
                new HeldPayment(paymentId, 1L, customerRef, 100_000L, "TEST-AUTH-" + paymentId, null, now));
    }

    // 사건의 첫 결제 연결 번호 — 연결 이력에서 읽는다
    private Long firstCasePaymentId(Long caseId) {
        return caseHistoryRepository.findBySecurityCaseIdOrderByCreatedAtAscIdAsc(caseId).stream()
                .filter(history -> history.getEventType() == CaseHistoryEventType.PAYMENT_LINKED)
                .map(history -> ((Number) history.getEventData().get("casePaymentId")).longValue())
                .findFirst()
                .orElseThrow();
    }

    private SecurityCase caseOf(Long caseId) {
        return securityCaseRepository.findById(caseId).orElseThrow();
    }

    private List<CaseHistoryEventType> eventTypes(Long caseId) {
        return caseHistoryRepository.findBySecurityCaseIdOrderByCreatedAtAscIdAsc(caseId).stream()
                .map(CaseHistory::getEventType)
                .toList();
    }

    private CaseHistory lastOf(Long caseId, CaseHistoryEventType type) {
        return caseHistoryRepository.findBySecurityCaseIdOrderByCreatedAtAscIdAsc(caseId).stream()
                .filter(history -> history.getEventType() == type)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private void staffAre(SecurityStaffEntry... staff) {
        given(staffDirectory.securityStaff()).willReturn(List.of(staff));
    }

    private void loginAs(Long userId, String role) {
        given(currentStaff.findCurrent())
                .willReturn(Optional.of(
                        StaffSummary.builder().userId(userId).role(role).build()));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(SessionUserTestSupport.asCurrentStaff(request, currentStaff));
    }

    private static Long newStaffId() {
        return NEXT_STAFF_ID.incrementAndGet();
    }

    private static String customer() {
        return "customer-" + UUID.randomUUID();
    }
}
