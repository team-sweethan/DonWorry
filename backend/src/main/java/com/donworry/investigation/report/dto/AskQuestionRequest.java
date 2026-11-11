package com.donworry.investigation.report.dto;

// 화면 → Spring 사건 질문 (ADR-0086 1절) — 길이(1~300자)는 서비스가 앞뒤 공백을 뺀 뒤 확인한다
public record AskQuestionRequest(String question) {}
