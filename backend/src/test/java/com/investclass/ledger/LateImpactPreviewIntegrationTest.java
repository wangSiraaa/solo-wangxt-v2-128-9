package com.investclass.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import com.investclass.ledger.eod.EodService;
import com.investclass.ledger.eod.LateImpactPreviewService;
import com.investclass.ledger.eod.LateImpactRepository;
import com.investclass.ledger.eod.LateImpactRules;
import com.investclass.ledger.importing.Idempotency;
import com.investclass.ledger.ledger.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迟到事件影响预览端到端验收（真实 PG）：
 *  1. 迟到成交跨登记日 → 相关分红快照被标记待复核，且不撤销/改写旧快照、不伪造差值；
 *  2. 只影响未来日期的事件不污染更早快照；
 *  3. 重复导入被幂等拦截、重复扫描，都不会重复产生影响项。
 *
 * <p>共享同一嵌入式实例，(source_system, source_key) 全局唯一，因此各用例使用
 * 独立账户与带前缀的来源键，互不依赖执行顺序。
 */
class LateImpactPreviewIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EventRepository events;
    @Autowired private EodService eod;
    @Autowired private LateImpactPreviewService preview;
    @Autowired private LateImpactRepository impactRepo;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String STK = "AAA";

    private Event trade(String acc, String key, String date, String settle,
                        String side, String qty) {
        return new Event(null, EventType.TRADE, acc, STK, LocalDate.parse(date),
                LocalDate.parse(settle), null, null, null,
                new EventPayload.Trade(side, new BigDecimal(qty), new BigDecimal("10"),
                        BigDecimal.ZERO, "CNY"),
                "li-file", key, null, false, null);
    }

    private Event dividend(String acc, String key, String ex, String record, String pay) {
        return new Event(null, EventType.CASH_DIVIDEND, acc, STK, LocalDate.parse(ex),
                null, LocalDate.parse(record), LocalDate.parse(pay), null,
                new EventPayload.CashDividend(new BigDecimal("0.5"), "CNY"),
                "li-file", key, null, false, null);
    }

    private long insert(Event e) throws Exception {
        String json = mapper.writeValueAsString(e.payload());
        String idem = Idempotency.sha256Hex("li-file|" + e.canonicalFingerprint());
        return events.insertIfAbsent(e, json, idem).event().id();
    }

    private long reasonCount(String acc, String reason) {
        Long v = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM late_event_impact
                WHERE account_id = ? AND reason_code = ?
                """, Long.class, acc, reason);
        return v == null ? 0L : v;
    }

    @Test
    void lateTradeAcrossRecordDateFlagsDividendSnapshotWithoutRewritingHistory()
            throws Exception {
        String acc = "LI1";
        LocalDate day = LocalDate.parse("2026-09-30");
        insert(trade(acc, "LI1-T1", "2026-09-23", "2026-09-24", "BUY", "100"));
        insert(dividend(acc, "LI1-D1", "2026-09-25", "2026-09-26", "2026-09-30"));

        eod.prepareDraft(acc, day);
        assertThat(eod.publish(acc, day).allBalanced()).isTrue();

        BigDecimal qtyBefore = jdbcTemplate.queryForObject(
                "SELECT qty FROM snapshot_position WHERE account_id=? AND business_date=?",
                BigDecimal.class, acc, day);
        assertThat(qtyBefore).isEqualByComparingTo("100");

        // 发布后导入更早业务日的卖出（09-24 成交、09-25 结算，早于 09-26 登记日）
        long lateId = insert(trade(acc, "LI1-T2", "2026-09-24", "2026-09-25", "SELL", "40"));

        LateImpactPreviewService.Preview result = preview.scan(acc);
        assertThat(result.lateEvents()).extracting(Event::id).contains(lateId);
        assertThat(result.impacts()).isNotEmpty();
        assertThat(result.newImpactCount()).isGreaterThan(0);

        // 跨登记日 → 分红快照被标记
        assertThat(reasonCount(acc, LateImpactRules.REASON_CROSS_RECORD_DIVIDEND)).isEqualTo(1L);
        assertThat(reasonCount(acc, LateImpactRules.REASON_TRADE_SETTLED)).isEqualTo(1L);

        // 所有影响项一律待复核，且全部指向这张已发布快照（无任何差值金额）
        var rows = impactRepo.findByAccount(acc);
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(LateImpactRules.STATUS_REVIEW);
            assertThat(r.snapshotDate()).isEqualTo(day);
            assertThat(r.instrument()).isEqualTo(STK);
            assertThat(r.detail()).isNotBlank();
        });

        // 预览不撤销旧快照、不改写历史投影：正式视图仍是 100 股
        BigDecimal qtyAfter = jdbcTemplate.queryForObject(
                "SELECT qty FROM snapshot_position WHERE account_id=? AND business_date=?",
                BigDecimal.class, acc, day);
        assertThat(qtyAfter).isEqualByComparingTo("100");
    }

    @Test
    void futureOnlyEventDoesNotPolluteEarlierSnapshot() throws Exception {
        String acc = "LI2";
        LocalDate day = LocalDate.parse("2026-09-30");
        insert(trade(acc, "LI2-T1", "2026-09-23", "2026-09-24", "BUY", "100"));
        eod.prepareDraft(acc, day);
        eod.publish(acc, day);

        // 只影响未来日期：业务日与结算日均晚于快照日
        insert(trade(acc, "LI2-FUT", "2026-10-02", "2026-10-03", "BUY", "50"));

        LateImpactPreviewService.Preview result = preview.scan(acc);
        assertThat(result.impacts()).isEmpty();
        assertThat(result.newImpactCount()).isZero();
        assertThat(impactRepo.findByAccount(acc)).isEmpty();
    }

    @Test
    void duplicateImportAndRescanDoNotDuplicateImpacts() throws Exception {
        String acc = "LI3";
        LocalDate day = LocalDate.parse("2026-09-30");
        insert(trade(acc, "LI3-T1", "2026-09-23", "2026-09-24", "BUY", "100"));
        insert(dividend(acc, "LI3-D1", "2026-09-25", "2026-09-26", "2026-09-30"));
        eod.prepareDraft(acc, day);
        eod.publish(acc, day);

        Event late = trade(acc, "LI3-T2", "2026-09-24", "2026-09-25", "SELL", "40");
        insert(late);

        LateImpactPreviewService.Preview first = preview.scan(acc);
        assertThat(first.newImpactCount()).isGreaterThan(0);
        long totalAfterFirst = impactRepo.findByAccount(acc).size();
        assertThat(totalAfterFirst).isGreaterThan(0);

        // 重复导入同一笔迟到成交：账本唯一约束判重，不产生新事件
        String json = mapper.writeValueAsString(late.payload());
        String idem = Idempotency.sha256Hex("li-file|" + late.canonicalFingerprint());
        var dup = events.insertIfAbsent(late, json, idem);
        assertThat(dup.duplicate()).isTrue();

        // 再次扫描：全部命中唯一键，不新增影响项
        LateImpactPreviewService.Preview again = preview.scan(acc);
        assertThat(again.newImpactCount()).isZero();
        assertThat((long) impactRepo.findByAccount(acc).size()).isEqualTo(totalAfterFirst);
        assertThat(reasonCount(acc, LateImpactRules.REASON_CROSS_RECORD_DIVIDEND)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM business_event WHERE source_system='li-file' "
                        + "AND source_key='LI3-T2'", Long.class)).isEqualTo(1L);
    }
}
