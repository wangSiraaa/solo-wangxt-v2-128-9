package com.investclass.ledger.eod;

import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 迟到事件影响预览纯函数规则验收：不依赖数据库，不产生任何金额差值。 */
class LateImpactRulesTest {

    private static final String ACC = "A1";
    private static final String STK = "AAA";

    private static Event trade(long id, String date, String settle, String side, String qty) {
        return new Event(id, EventType.TRADE, ACC, STK, LocalDate.parse(date),
                LocalDate.parse(settle), null, null, null,
                new EventPayload.Trade(side, new BigDecimal(qty), new BigDecimal("10"),
                        BigDecimal.ZERO, "CNY"),
                "file", "T" + id, "idem-" + id, false, null);
    }

    private static Event dividend(long id, String ex, String record, String pay) {
        return new Event(id, EventType.CASH_DIVIDEND, ACC, STK, LocalDate.parse(ex),
                null, LocalDate.parse(record), LocalDate.parse(pay), null,
                new EventPayload.CashDividend(new BigDecimal("0.5"), "CNY"),
                "ca", "D" + id, "idem-d" + id, false, null);
    }

    private static Event split(long id, String ex, String ratio) {
        return new Event(id, EventType.STOCK_SPLIT, ACC, STK, LocalDate.parse(ex),
                null, null, null, null,
                new EventPayload.StockSplit(new BigDecimal(ratio)),
                "ca", "S" + id, "idem-s" + id, false, null);
    }

    private static final LocalDate D = LocalDate.parse("2026-09-30");

    private List<LateImpactRules.Finding> eval(Event late, long watermark, List<Event> all) {
        var snap = new LateImpactRules.PublishedSnapshot(D, watermark);
        return LateImpactRules.evaluate(late, List.of(snap), all);
    }

    @Test
    void lateSellSettledBeforeRecordDateFlagsDividendSnapshot() {
        Event buy = trade(1, "2026-09-20", "2026-09-21", "BUY", "100");
        Event div = dividend(2, "2026-09-25", "2026-09-26", "2026-09-30");
        // 发布水位只到分红事件；迟到卖出在登记日前结算
        Event lateSell = trade(3, "2026-09-24", "2026-09-25", "SELL", "40");

        List<LateImpactRules.Finding> f = eval(lateSell, 2, List.of(buy, div, lateSell));

        assertThat(f).extracting(LateImpactRules.Finding::reasonCode)
                .contains(LateImpactRules.REASON_TRADE_SETTLED,
                        LateImpactRules.REASON_CROSS_RECORD_DIVIDEND,
                        LateImpactRules.REASON_DIVIDEND_CASH);
        assertThat(f).allSatisfy(x -> {
            assertThat(x.snapshotDate()).isEqualTo(D);
            assertThat(x.eventId()).isEqualTo(3L);
            // 全部只标记待复核，且没有任何差值金额字段
        });
    }

    @Test
    void futureOnlyTradeDoesNotPolluteEarlierSnapshot() {
        // 成交结算日晚于快照日：即便业务日“较晚”，也不污染更早快照
        Event lateBuy = trade(9, "2026-10-02", "2026-10-03", "BUY", "50");
        assertThat(eval(lateBuy, 5, List.of(lateBuy))).isEmpty();
    }

    @Test
    void futureOnlyDividendDoesNotPolluteEarlierSnapshot() {
        // 登记日与支付日均晚于快照日
        Event lateDiv = dividend(9, "2026-10-05", "2026-10-06", "2026-10-10");
        assertThat(eval(lateDiv, 5, List.of(lateDiv))).isEmpty();
    }

    @Test
    void lateDividendRecordAndPayBothBeforeSnapshotFlagsEligibilityAndCash() {
        // 登记日已过、支付日仍在快照日前：只提示现金入账
        Event lateDiv = dividend(9, "2026-09-25", "2026-09-26", "2026-09-29");
        List<LateImpactRules.Finding> f = eval(lateDiv, 5, List.of(lateDiv));
        assertThat(f).extracting(LateImpactRules.Finding::reasonCode)
                .containsExactlyInAnyOrder(
                        LateImpactRules.REASON_DIVIDEND_RECORD_PAST,
                        LateImpactRules.REASON_DIVIDEND_PAY_PAST);
    }

    @Test
    void lateTradeHittingRecordButSnapshotBeforePaymentSkipsCash() {
        Event buy = trade(1, "2026-09-20", "2026-09-21", "BUY", "100");
        Event div = dividend(2, "2026-09-25", "2026-09-26", "2026-09-30");
        Event lateBuy = trade(3, "2026-09-23", "2026-09-24", "BUY", "20");
        // 快照日 09-27：跨登记日（09-26）但支付日 09-30 在未来 -> 只提示资格，不提示现金
        var snap = new LateImpactRules.PublishedSnapshot(LocalDate.parse("2026-09-27"), 2L);
        List<LateImpactRules.Finding> f =
                LateImpactRules.evaluate(lateBuy, List.of(snap), List.of(buy, div, lateBuy));
        assertThat(f).extracting(LateImpactRules.Finding::reasonCode)
                .contains(LateImpactRules.REASON_CROSS_RECORD_DIVIDEND)
                .doesNotContain(LateImpactRules.REASON_DIVIDEND_CASH);
    }

    @Test
    void inTransitSplitIsFlagged() {
        Event lateBuy = trade(3, "2026-08-08", "2026-08-12", "BUY", "100");
        Event split = split(2, "2026-08-10", "3");
        List<LateImpactRules.Finding> f = eval(lateBuy, 2, List.of(split, lateBuy));
        assertThat(f).extracting(LateImpactRules.Finding::reasonCode)
                .contains(LateImpactRules.REASON_INTRANSIT_SPLIT,
                        LateImpactRules.REASON_TRADE_SETTLED);
    }

    @Test
    void corporateActionOutsideSnapshotWatermarkIsNotCrossMatched() {
        Event lateSell = trade(3, "2026-09-24", "2026-09-25", "SELL", "40");
        // 分红本身也是发布后才到（id > 水位）：发布当时该公司行动不存在，不应跨事件误配
        Event futureDiv = dividend(9, "2026-09-25", "2026-09-26", "2026-09-30");
        List<LateImpactRules.Finding> f = eval(lateSell, 2, List.of(lateSell, futureDiv));
        assertThat(f).extracting(LateImpactRules.Finding::reasonCode)
                .doesNotContain(LateImpactRules.REASON_CROSS_RECORD_DIVIDEND);
    }
}
