package com.investclass.ledger.impact;

import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迟到影响原因规划（纯函数）：
 *  - 迟到成交跨登记日 ⇒ 产生登记日资格 + 关联支付原因（关联到分红事件）；
 *  - 生效日均在未来 ⇒ 不产生任何原因之外的“提前污染”；
 *  - 拆股 ⇒ 批次调整 + 在途成交重折算 + 登记日资格重折算；
 *  - 配股 ⇒ 资格/扣款/到账三个生效日。
 */
class LateEventImpactPlannerTest {

    private static final String ACC = "PL1";
    private static final String STK = "AAA";

    private Event trade(long id, String date, String settle, String side, String qty,
                        String price) {
        return new Event(id, EventType.TRADE, ACC, STK, LocalDate.parse(date),
                LocalDate.parse(settle), null, null, null,
                new EventPayload.Trade(side, new BigDecimal(qty), new BigDecimal(price),
                        BigDecimal.ZERO, "CNY"),
                "file", "T" + id, null, false, null);
    }

    private Event dividend(long id, String ex, String record, String pay, String perShare) {
        return new Event(id, EventType.CASH_DIVIDEND, ACC, STK, LocalDate.parse(ex),
                null, LocalDate.parse(record), LocalDate.parse(pay), null,
                new EventPayload.CashDividend(new BigDecimal(perShare), "CNY"),
                "file", "D" + id, null, false, null);
    }

    private Event split(long id, String ex, String ratio) {
        return new Event(id, EventType.STOCK_SPLIT, ACC, STK, LocalDate.parse(ex),
                null, null, null, null,
                new EventPayload.StockSplit(new BigDecimal(ratio)),
                "file", "S" + id, null, false, null);
    }

    private Event rights(long id, String ex, String record, String pay, String allot) {
        return new Event(id, EventType.RIGHTS_OFFER, ACC, STK, LocalDate.parse(ex),
                null, LocalDate.parse(record), LocalDate.parse(pay), LocalDate.parse(allot),
                new EventPayload.RightsOffer(new BigDecimal("0.2"), new BigDecimal("3"),
                        new BigDecimal("7"), "CNY"),
                "file", "R" + id, null, false, null);
    }

    @Test
    void lateTradeCrossingRecordDateYieldsEligibilityAndPaymentReasons() {
        Event buy = trade(1, "2026-09-23", "2026-09-24", "BUY", "100", "10");
        Event div = dividend(2, "2026-09-25", "2026-09-26", "2026-09-30", "0.5");
        // 迟到成交：09-25 结算，早于 09-26 登记日 ⇒ 跨登记日
        Event lateSell = trade(3, "2026-09-24", "2026-09-25", "SELL", "40", "11");

        List<LateEventImpactPlanner.ImpactReason> reasons =
                LateEventImpactPlanner.reasons(lateSell, List.of(buy, div, lateSell));

        assertThat(reasons).anySatisfy(r -> {
            assertThat(r.code()).isEqualTo("TRADE_SETTLEMENT");
            assertThat(r.effectiveDate()).isEqualTo(LocalDate.parse("2026-09-25"));
        });
        assertThat(reasons).anySatisfy(r -> {
            assertThat(r.code()).isEqualTo("RECORD_DATE_ELIGIBILITY");
            assertThat(r.effectiveDate()).isEqualTo(LocalDate.parse("2026-09-26"));
            assertThat(r.relatedEventId()).isEqualTo(2L);
        });
        assertThat(reasons).anySatisfy(r -> {
            assertThat(r.code()).isEqualTo("RELATED_PAYMENT");
            assertThat(r.effectiveDate()).isEqualTo(LocalDate.parse("2026-09-30"));
            assertThat(r.relatedEventId()).isEqualTo(2L);
        });
    }

    @Test
    void tradeSettlingAfterRecordDateDoesNotTouchEligibility() {
        Event div = dividend(2, "2026-09-25", "2026-09-26", "2026-09-30", "0.5");
        // 09-29 才结算，晚于登记日 ⇒ 不影响在册资格
        Event lateBuy = trade(3, "2026-09-28", "2026-09-29", "BUY", "10", "9");

        List<LateEventImpactPlanner.ImpactReason> reasons =
                LateEventImpactPlanner.reasons(lateBuy, List.of(div, lateBuy));

        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0).code()).isEqualTo("TRADE_SETTLEMENT");
        assertThat(reasons.get(0).effectiveDate())
                .isEqualTo(LocalDate.parse("2026-09-29"));
    }

    @Test
    void splitRescalesInFlightTradesAndRecordDateEligibility() {
        Event inFlight = trade(1, "2026-10-01", "2026-10-06", "BUY", "100", "10");
        Event div = dividend(2, "2026-10-07", "2026-10-09", "2026-10-15", "0.5");
        Event split = split(3, "2026-10-05", "3");

        List<LateEventImpactPlanner.ImpactReason> reasons =
                LateEventImpactPlanner.reasons(split, List.of(inFlight, div, split));

        assertThat(reasons).anySatisfy(r -> {
            assertThat(r.code()).isEqualTo("SPLIT_ADJUSTMENT");
            assertThat(r.effectiveDate()).isEqualTo(LocalDate.parse("2026-10-05"));
        });
        assertThat(reasons).anySatisfy(r -> {
            assertThat(r.code()).isEqualTo("IN_FLIGHT_TRADE_RESCALE");
            assertThat(r.relatedEventId()).isEqualTo(1L);
            assertThat(r.effectiveDate()).isEqualTo(LocalDate.parse("2026-10-06"));
        });
        assertThat(reasons).anySatisfy(r -> {
            assertThat(r.code()).isEqualTo("RECORD_DATE_ELIGIBILITY");
            assertThat(r.relatedEventId()).isEqualTo(2L);
        });
    }

    @Test
    void dividendAndRightsExposeTheirOwnEffectiveDates() {
        Event div = dividend(2, "2026-09-25", "2026-09-26", "2026-09-30", "0.5");
        assertThat(LateEventImpactPlanner.reasons(div, List.of(div)))
                .extracting(LateEventImpactPlanner.ImpactReason::code)
                .containsExactly("DIVIDEND_ENTITLEMENT", "DIVIDEND_PAYMENT");

        Event rights = rights(4, "2026-10-01", "2026-10-03", "2026-10-08", "2026-10-10");
        assertThat(LateEventImpactPlanner.reasons(rights, List.of(rights)))
                .extracting(LateEventImpactPlanner.ImpactReason::code)
                .containsExactly("RIGHTS_ENTITLEMENT", "RIGHTS_PAYMENT", "RIGHTS_ALLOTMENT");
    }

    @Test
    void futureOnlyEventHasNoReasonAtOrBeforeEarlierDates() {
        // 全部生效日都在 10 月 ⇒ 对 9 月已发布快照不应产生任何影响项
        Event futureTrade = trade(5, "2026-10-04", "2026-10-06", "BUY", "10", "9");
        List<LateEventImpactPlanner.ImpactReason> reasons =
                LateEventImpactPlanner.reasons(futureTrade, List.of(futureTrade));
        assertThat(reasons).isNotEmpty();
        assertThat(reasons).allSatisfy(r -> assertThat(r.effectiveDate())
                .isAfter(LocalDate.parse("2026-09-30")));
    }
}
