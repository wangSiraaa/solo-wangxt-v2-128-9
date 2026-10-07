package com.investclass.ledger.eod;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 迟到事件影响预览仓储：只追加提示项，幂等写入。
 * 绝不写回快照或投影——预览只读地标记可能受影响的正式快照。
 */
@Repository
public class LateImpactRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LateImpactRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ImpactRow(long id, String accountId, long eventId, LocalDate snapshotDate,
                            String reasonCode, LocalDate relatedDate, String instrument,
                            String detail, String status, java.time.Instant detectedAt) {
    }

    /**
     * 幂等登记一条影响项；同一 (事件, 快照日, 原因, 相关日) 已存在则跳过，
     * 重复导入/重复扫描不会重复产生影响项。返回是否新增。
     */
    public boolean insertIfAbsent(String accountId, long eventId, LocalDate snapshotDate,
                                  String reasonCode, LocalDate relatedDate, String instrument,
                                  String detail) {
        int n = jdbc.update("""
                INSERT INTO late_event_impact
                  (account_id, event_id, snapshot_date, reason_code, related_date,
                   instrument, detail, status)
                VALUES
                  (:a, :e, :sd, :rc, :rd, :i, :det, 'PENDING_REVIEW')
                ON CONFLICT (event_id, snapshot_date, reason_code, related_date) DO NOTHING
                """,
                new MapSqlParameterSource("a", accountId).addValue("e", eventId)
                        .addValue("sd", snapshotDate, java.sql.Types.DATE)
                        .addValue("rc", reasonCode)
                        .addValue("rd", relatedDate, java.sql.Types.DATE)
                        .addValue("i", instrument).addValue("det", detail));
        return n > 0;
    }

    public List<ImpactRow> findByAccount(String accountId) {
        return jdbc.query("""
                SELECT id, account_id, event_id, snapshot_date, reason_code, related_date,
                       instrument, detail, status, detected_at
                FROM late_event_impact
                WHERE account_id = :a
                ORDER BY snapshot_date, event_id, reason_code
                """,
                new MapSqlParameterSource("a", accountId),
                (rs, n) -> new ImpactRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getObject(4, LocalDate.class), rs.getString(5),
                        rs.getObject(6, LocalDate.class), rs.getString(7), rs.getString(8),
                        rs.getString(9),
                        rs.getTimestamp(10) == null ? null
                                : rs.getTimestamp(10).toInstant()));
    }

    public long countByEvent(String accountId, long eventId) {
        Long v = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM late_event_impact
                        WHERE account_id = :a AND event_id = :e
                        """,
                new MapSqlParameterSource("a", accountId).addValue("e", eventId),
                Long.class);
        return v == null ? 0L : v;
    }
}
