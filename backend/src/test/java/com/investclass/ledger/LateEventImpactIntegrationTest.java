package com.investclass.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import com.investclass.ledger.eod.EodService;
import com.investclass.ledger.impact.LateEventImpactRepository;
import com.investclass.ledger.impact.LateEventImpactService;
import com.investclass.ledger.importing.Idempotency;
import com.investclass.ledger.importing.ImportService;
import com.investclass.ledger.ledger.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迟到事件影响预览端到端验收（真实 PG）：
 *  1. 迟到成交跨登记日 ⇒ 相关分红快照被标记（登记日资格 + 关联支付 + 成交结算）；
 *  2. 只影响未来日期的事件 ⇒ 不污染更早的已发布快照；
 *  3. 重复导入被幂等拦截 ⇒ 不会重复产生影响项；重复评估亦幂等；
 *  4. 发布期间到达的迟到成交 ⇒ 对新发布的快照同样登记影响预览；
 *  全过程不撤销旧快照、不改写历史投影（快照数量保持发布时取值）。
 */
class LateEventImpactIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EventRepository events;
    @Autowired private EodService eod;
    @Autowired private ImportService imports;
    @Autowired private LateEventImpactService impactService;
    @Autowired private LateEventImpactRepository impacts;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String HEADER = String.join(",",
            "rowType", "accountId", "instrument", "businessDate", "settlementDate",
            "recordDate", "paymentDate", "allotmentDate", "side", "quantity", "price",
            "commission", "ratio", "amountPerShare", "rightsPerShare", "subscriptionPrice",
            "subscribedQty", "currency", "sourceKey");

    private Event trade(String acc, long id, String date, String settle, String side,
                        String qty, String price) {
        return new Event(null, EventType.TRADE, acc, "AAA", LocalDate.parse(date),
                LocalDate.parse(settle), null, null, null,
                new EventPayload.Trade(side, new BigDecimal(qty), new BigDecimal(price),
                        BigDecimal.ZERO, "CNY"),
                "file", acc + "-T" + id, null, false, null);
    }

    private Event dividend(String acc, long id, String ex, String record, String pay,
                           String perShare) {
        return new Event(null, EventType.CASH_DIVIDEND, acc, "AAA", LocalDate.parse(ex),
                null, LocalDate.parse(record), LocalDate.parse(pay), null,
                new EventPayload.CashDividend(new BigDecimal(perShare), "CNY"),
                "file", acc + "-D" + id, null, false, null);
    }

    private long insert(Event e) throws Exception {
        String json = mapper.writeValueAsString(e.payload());
        String idem = Idempotency.sha256Hex("file|" + e.canonicalFingerprint());
        return events.insertIfAbsent(e, json, idem).event().id();
    }

    private static String tradeCsvRow(String acc, String businessDate, String settle,
                                      String side, String qty, String price, String sourceKey) {
        return "TRADE," + acc + ",AAA," + businessDate + "," + settle + ",,,,"
                + side + "," + qty + "," + price + ",0,,,,,,CNY," + sourceKey;
    }

    @Test
    void lateTradeCrossingRecordDateFlagsDividendSnapshot() throws Exception {
        String acc = "LI1";
        insert(trade(acc, 1, "2026-09-23", "2026-09-24", "BUY", "100", "10"));
        long divId = insert(dividend(acc, 2, "2026-09-25", "2026-09-26", "2026-09-30", "0.5"));

        LocalDate day = LocalDate.parse("2026-09-30");
        eod.prepareDraft(acc, day);
        assertThat(eod.publish(acc, day).allBalanced()).isTrue();
        assertThat(impacts.countByAccount(acc)).isZero();

        // 正式发布后导入较早交易日的成交：09-25 结算，早于 09-26 登记日 ⇒ 跨登记日
        String csv = HEADER + "\n"
                + tradeCsvRow(acc, "2026-09-24", "2026-09-25", "SELL", "40", "11",
                        acc + "-LATE-SELL-1") + "\n";
        var accepted = imports.accept("late-sell.csv", csv.getBytes(), "teaching-upload");
        assertThat(accepted.status()).isNotEqualTo("DUPLICATE");

        List<LateEventImpactRepository.ImpactRow> rows = impacts.listByAccount(acc);
        assertThat(rows).isNotEmpty();
        // 全部指向已发布的 09-30 快照，且一律待复核（不伪造差值）
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.snapshotDate()).isEqualTo(day);
            assertThat(r.status()).isEqualTo("PENDING_REVIEW");
        });
        assertThat(rows).extracting(LateEventImpactRepository.ImpactRow::reasonCode)
                .contains("TRADE_SETTLEMENT", "RECORD_DATE_ELIGIBILITY", "RELATED_PAYMENT");
        // 跨登记日 ⇒ 关联到分红事件的资格与支付影响
        assertThat(rows).filteredOn(r -> r.relatedEventId() == divId)
                .extracting(LateEventImpactRepository.ImpactRow::reasonCode)
                .containsExactlyInAnyOrder("RECORD_DATE_ELIGIBILITY", "RELATED_PAYMENT");
        assertThat(rows).filteredOn(r -> r.reasonCode().equals("RECORD_DATE_ELIGIBILITY"))
                .allSatisfy(r -> assertThat(r.effectiveDate())
                        .isEqualTo(LocalDate.parse("2026-09-26")));
        assertThat(rows).filteredOn(r -> r.reasonCode().equals("RELATED_PAYMENT"))
                .allSatisfy(r -> assertThat(r.effectiveDate())
                        .isEqualTo(LocalDate.parse("2026-09-30")));

        // 预览不撤销旧快照：09-30 正式视图仍是发布时的 100 股
        BigDecimal snapshotQty = jdbcTemplate.queryForObject(
                "SELECT qty FROM snapshot_position WHERE account_id=? AND business_date=?",
                BigDecimal.class, acc, java.sql.Date.valueOf(day));
        assertThat(snapshotQty).isEqualByComparingTo("100");
    }

    @Test
    void futureOnlyEventDoesNotPolluteEarlierSnapshots() throws Exception {
        String acc = "LI2";
        insert(trade(acc, 1, "2026-09-23", "2026-09-24", "BUY", "100", "10"));
        LocalDate day = LocalDate.parse("2026-09-30");
        eod.prepareDraft(acc, day);
        assertThat(eod.publish(acc, day).allBalanced()).isTrue();

        // 成交与结算都在快照日之后 ⇒ 只影响未来日期
        String csv = HEADER + "\n"
                + tradeCsvRow(acc, "2026-10-04", "2026-10-06", "BUY", "10", "9",
                        acc + "-FUTURE-BUY-1") + "\n";
        imports.accept("future-buy.csv", csv.getBytes(), "teaching-upload");

        assertThat(impacts.countByAccount(acc)).isZero();
    }

    @Test
    void duplicateImportIsIdempotentlyBlockedAndDoesNotDuplicateImpacts() throws Exception {
        String acc = "LI3";
        insert(trade(acc, 1, "2026-09-23", "2026-09-24", "BUY", "100", "10"));
        LocalDate day = LocalDate.parse("2026-09-30");
        eod.prepareDraft(acc, day);
        assertThat(eod.publish(acc, day).allBalanced()).isTrue();

        String csv = HEADER + "\n"
                + tradeCsvRow(acc, "2026-09-28", "2026-09-29", "BUY", "50", "9",
                        acc + "-LATE-BUY-1") + "\n";
        imports.accept("late-buy.csv", csv.getBytes(), "teaching-upload");
        long afterFirst = impacts.countByAccount(acc);
        assertThat(afterFirst).isGreaterThan(0);

        // 1) 同一文件重复提交：文件级 sha256 判 DUPLICATE，根本不启动作业
        var repeat = imports.accept("late-buy.csv", csv.getBytes(), "teaching-upload");
        assertThat(repeat.status()).isEqualTo("DUPLICATE");
        assertThat(impacts.countByAccount(acc)).isEqualTo(afterFirst);

        // 2) 换文件名/微调字节（业务事实相同）：行级幂等拦截，不重复产生影响项
        byte[] sameFacts = csv.replace("50,9", "50  ,9").getBytes();
        imports.accept("late-buy-renamed.csv", sameFacts, "teaching-upload");
        assertThat(impacts.countByAccount(acc)).isEqualTo(afterFirst);

        // 3) 对同一事件直接重复评估：唯一约束保证幂等
        Event existing = events.findBySourceKey("teaching-upload", acc + "-LATE-BUY-1").orElseThrow();
        assertThat(impactService.evaluate(existing)).isZero();
        assertThat(impacts.countByAccount(acc)).isEqualTo(afterFirst);
    }

    @Test
    void arrivalDuringPublishFlagsNewlyPublishedSnapshot() throws Exception {
        String acc = "LI4";
        insert(trade(acc, 1, "2026-09-23", "2026-09-24", "BUY", "100", "10"));
        LocalDate day1 = LocalDate.parse("2026-09-30");
        eod.prepareDraft(acc, day1);
        assertThat(eod.publish(acc, day1).allBalanced()).isTrue();

        // 先准备次日草稿（绑定水位），迟到成交在草稿之后、发布之前到达
        LocalDate day2 = day1.plusDays(1);
        eod.prepareDraft(acc, day2);
        String csv = HEADER + "\n"
                + tradeCsvRow(acc, "2026-09-29", "2026-09-30", "BUY", "50", "9",
                        acc + "-DURING-PUBLISH-1") + "\n";
        imports.accept("during-publish.csv", csv.getBytes(), "teaching-upload");
        // 导入时已对 09-30 快照登记影响
        assertThat(impacts.listByAccount(acc))
                .anySatisfy(r -> assertThat(r.snapshotDate()).isEqualTo(day1));

        // 发布次日快照：迟到成交被冻结水位排除，同时登记对新快照的影响预览
        assertThat(eod.publish(acc, day2).allBalanced()).isTrue();
        assertThat(impacts.listByAccount(acc))
                .anySatisfy(r -> {
                    assertThat(r.snapshotDate()).isEqualTo(day2);
                    assertThat(r.reasonCode()).isEqualTo("TRADE_SETTLEMENT");
                    assertThat(r.status()).isEqualTo("PENDING_REVIEW");
                });

        // 次日快照同样不被反向改写：仍是发布时的 100 股
        BigDecimal snapshotQty = jdbcTemplate.queryForObject(
                "SELECT qty FROM snapshot_position WHERE account_id=? AND business_date=?",
                BigDecimal.class, acc, java.sql.Date.valueOf(day2));
        assertThat(snapshotQty).isEqualByComparingTo("100");
    }
}
