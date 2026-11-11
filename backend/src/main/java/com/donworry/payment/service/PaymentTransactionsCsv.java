package com.donworry.payment.service;

import com.donworry.payment.dto.PaymentTransactionDetailResponse;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

// 거래 내역 CSV (#651) — 거래 상세와 같은 칸(가린 값)만 쓴다. 주문자 이름 · 전화 같은 원문 칸은 없다
// 목록 화면은 #732부터 카드 · 접속 · 기기 · 이메일을 싣지 않지만 CSV는 보안 직원이 담당 사건 고객만 내보낼 수 있어(#730) 그대로 둔다
// 엑셀에서 수식으로 실행되지 않게 = + - @ 탭 · 줄바꿈으로 시작하는 값은 앞에 ' 를 붙인다
public final class PaymentTransactionsCsv {
    private static final DateTimeFormatter KST =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    private record Column(
            String header, BiFunction<PaymentTransactionDetailResponse, Map<Long, String>, Object> value) {}

    private static final List<Column> COLUMNS = List.of(
            new Column("결제번호", (t, m) -> t.paymentId()),
            new Column("결제시각(KST)", (t, m) -> t.createdAt() == null ? null : KST.format(t.createdAt())),
            new Column("가맹점번호", (t, m) -> t.merchantId()),
            new Column("가맹점", (t, m) -> t.merchantId() == null ? null : m.get(t.merchantId())),
            new Column("고객번호", (t, m) -> t.customerRef()),
            new Column("주문번호", (t, m) -> t.orderRef()),
            new Column("시도", (t, m) -> t.attemptNo()),
            new Column("금액", (t, m) -> t.amount()),
            new Column("결제상태", (t, m) -> t.paymentStatus()),
            new Column("FDS결과", (t, m) -> t.fdsResult()),
            new Column("평가번호", (t, m) -> t.evaluationId()),
            new Column("카드", (t, m) -> t.cardDisplayLabel()),
            new Column("IP국가", (t, m) -> t.ipCountryCode()),
            new Column("IP(가림)", (t, m) -> t.maskedIpAddress()),
            new Column("기기(가림)", (t, m) -> t.maskedDeviceId()),
            new Column("회원이메일(가림)", (t, m) -> t.maskedBuyerEmail()));

    private PaymentTransactionsCsv() {}

    public static List<String> headers() {
        return COLUMNS.stream().map(Column::header).toList();
    }

    // merchantNames: 가맹점 번호 → 이름. 없는 번호는 이름 칸을 비운다
    public static String write(List<PaymentTransactionDetailResponse> items, Map<Long, String> merchantNames) {
        var out = new StringBuilder();
        out.append(line(headers().stream().map(h -> (Object) h).toList()));
        for (var item : items) {
            out.append(line(COLUMNS.stream()
                    .map(c -> c.value().apply(item, merchantNames))
                    .toList()));
        }
        return out.toString();
    }

    private static String line(List<Object> values) {
        return String.join(
                        ",", values.stream().map(PaymentTransactionsCsv::cell).toList()) + "\r\n";
    }

    private static String cell(Object value) {
        if (value == null) return "";
        String text = value.toString();
        // 숫자 칸(금액 등)은 그대로 둔다 — 음수가 없고 엑셀이 숫자로 읽어야 한다
        if (!(value instanceof Number) && !text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0)) >= 0)
            text = "'" + text;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r"))
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        return text;
    }
}
