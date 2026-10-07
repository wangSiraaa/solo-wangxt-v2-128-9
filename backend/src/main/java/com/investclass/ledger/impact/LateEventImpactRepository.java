package com.investclass.ledger.impact;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 迟到事件影响项仓储：只追加。唯一约束
 * (event_id, snapshot_date, reason_code, related_event_id) 保证
 * 重复评估/重复导入不会产生重复影响项。
 */
@Repository
public class LateEventImpactRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LateEventImpactRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等登记一条影响项；已存在同键行时不做任何事。返回是否新插入。 */
    public boolean insertIfAbsent(long eventId, String accountId, String instrument,
                                  LocalDate effectiveDate, LocalDate snapshotDate,
                                  long snapshotWatermark, String reasonCode,
                                  long relatedEventId, String detail) {
        int n = jdbc.update("""
                INSERT INTO late_event_impact
                  (event_id, account_id, instrument, effective_date, snapshot_date,
                   snapshot_watermark_event_id, reason_code, related_event_id, reason_detail)
                VALUES
                  (:e, :a, :i, :ed, :sd, :w, :c, :r, :d)
                ON CONFLICT (event_id, snapshot_date, reason_code, related_event_id) DO NOTHING
                """,
                new MapSqlParameterSource("e", eventId).addValue("a", accountId)
                        .addValue("i", instrument).addValue("ed", effectiveDate)
                        .addValue("sd", snapshotDate).addValue("w", snapshotWatermark)
                        .addValue("c", reasonCode).addValue("r", relatedEventId)
                        .addValue("d", detail));
        return n > 0;
    }

    /** 读模型：联接不可变事件本体，供时间线展示事件水位与受影响范围。 */
    public record ImpactRow(long id, long eventId, String eventType, String instrument,
                            LocalDate businessDate, LocalDate settlementDate,
                            LocalDate recordDate, LocalDate paymentDate,
                            LocalDate allotmentDate,
                            LocalDate effectiveDate, LocalDate snapshotDate,
                            long snapshotWatermarkEventId,
                            String reasonCode, long relatedEventId, String reasonDetail,
                            String status, Instant createdAt) {
    }

    public List<ImpactRow> listByAccount(String accountId) {
        return jdbc.query("""
                SELECT i.id, i.event_id, be.event_type, i.instrument,
                       be.business_date, be.settlement_date, be.record_date,
                       be.payment_date, be.allotment_date,
                       i.effective_date, i.snapshot_date, i.snapshot_watermark_event_id,
                       i.reason_code, i.related_event_id, i.reason_detail, i.status,
                       i.created_at
                FROM late_event_impact i
                JOIN business_event be ON be.id = i.event_id
                WHERE i.account_id = :a
                ORDER BY i.snapshot_date, i.event_id, i.effective_date, i.reason_code
                """,
                new MapSqlParameterSource("a", accountId),
                (rs, n) -> new ImpactRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4),
                        rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class),
                        rs.getObject(9, LocalDate.class), rs.getObject(10, LocalDate.class),
                        rs.getObject(11, LocalDate.class), rs.getLong(12),
                        rs.getString(13), rs.getLong(14), rs.getString(15),
                        rs.getString(16), rs.getTimestamp(17).toInstant()));
    }

    public long countByAccount(String accountId) {
        Long v = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM late_event_impact WHERE account_id = ?",
                Long.class, accountId);
        return v == null ? 0L : v;
    }
}
