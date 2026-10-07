package com.investclass.ledger.eod;

import com.investclass.ledger.core.Event;
import com.investclass.ledger.ledger.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 迟到事件影响预览服务。
 *
 * <p>正式日终快照发布后又导入更早业务日的事件时，{@link #scan} 依据每个新事件的业务日、
 * 结算日及相关除权/登记/支付/到账日，枚举可能需要重新复核的已发布快照并给出原因，
 * 结果幂等落 {@code late_event_impact} 表。
 *
 * <ul>
 *   <li><b>只预览，不撤销</b>：不删除/覆盖任何正式快照，不回写历史投影；</li>
 *   <li><b>只标待复核</b>：无法确定精确差值，不计算、不伪造差额金额；</li>
 *   <li><b>幂等</b>：重复导入被账本唯一约束拦截、重复扫描命中唯一键，都不会重复产生影响项。</li>
 * </ul>
 */
@Service
public class LateImpactPreviewService {

    private final EventRepository events;
    private final EodDraftRepository drafts;
    private final LateImpactRepository impacts;

    public LateImpactPreviewService(EventRepository events, EodDraftRepository drafts,
                                    LateImpactRepository impacts) {
        this.events = events;
        this.drafts = drafts;
        this.impacts = impacts;
    }

    public record SnapshotImpact(
            java.time.LocalDate businessDate, long watermarkEventId,
            int impactCount, int pendingCount) {
    }

    public record Preview(
            String accountId,
            List<SnapshotImpact> snapshots,
            List<Event> lateEvents,
            List<LateImpactRepository.ImpactRow> impacts,
            int newImpactCount,
            int totalImpactCount,
            int pendingCount) {
    }

    /**
     * 扫描账户：对每个已发布快照，找出其冻结水位之后到达的事件，
     * 纯函数判定可能受影响的快照与原因并幂等登记。不改动快照与投影。
     */
    @Transactional
    public Preview scan(String accountId) {
        List<EodDraftRepository.PublishedWatermark> published =
                drafts.listPublishedWatermarks(accountId);
        List<Event> all = events.findByAccountUpTo(accountId, events.maxEventId(accountId));

        int added = 0;
        for (EodDraftRepository.PublishedWatermark snap : published) {
            var snapshot = new LateImpactRules.PublishedSnapshot(
                    snap.businessDate(), snap.watermarkEventId());
            for (Event e : all) {
                // 水位之后到达才是“发布后迟到”；水位内的事件本就参与了发布
                if (e.id() <= snap.watermarkEventId()) {
                    continue;
                }
                for (LateImpactRules.Finding f :
                        LateImpactRules.evaluate(e, List.of(snapshot), all)) {
                    boolean isNew = impacts.insertIfAbsent(accountId, f.eventId(),
                            f.snapshotDate(), f.reasonCode(), f.relatedDate(),
                            f.instrument(), f.detail());
                    if (isNew) {
                        added++;
                    }
                }
            }
        }
        return load(accountId, added);
    }

    /** 读取已登记的预览（不重新计算、不写入）。无数据时返回空视图。 */
    @Transactional(readOnly = true)
    public Preview get(String accountId) {
        return load(accountId, 0);
    }

    private Preview load(String accountId, int newImpactCount) {
        List<LateImpactRepository.ImpactRow> rows = impacts.findByAccount(accountId);
        Set<Long> eventIds = new LinkedHashSet<>();
        for (LateImpactRepository.ImpactRow r : rows) {
            eventIds.add(r.eventId());
        }
        List<Event> lateEvents = events.findByIds(List.copyOf(eventIds));

        List<SnapshotImpact> snapshotViews = drafts.listPublishedWatermarks(accountId).stream()
                .map(w -> {
                    List<LateImpactRepository.ImpactRow> on = rows.stream()
                            .filter(r -> r.snapshotDate().equals(w.businessDate())).toList();
                    int pending = (int) on.stream()
                            .filter(r -> r.status().equals(LateImpactRules.STATUS_REVIEW)).count();
                    return new SnapshotImpact(w.businessDate(), w.watermarkEventId(),
                            on.size(), pending);
                })
                .toList();

        int pending = (int) rows.stream()
                .filter(r -> r.status().equals(LateImpactRules.STATUS_REVIEW)).count();
        return new Preview(accountId, snapshotViews, lateEvents, rows,
                newImpactCount, rows.size(), pending);
    }
}
