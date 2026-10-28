package com.donworry.investigation.report.service;

// ai-server 조사 보고서가 끝남(SUCCEEDED · FAILED) — Case 상태를 다시 계산하게 알린다
// 같은 모듈 안의 동기 이벤트다. P3 CaseStatusService가 ReportGateway에 의존해 직접 부르면 순환이 생겨 이벤트로 둔다
record AiReportFinished(Long caseId) {}
