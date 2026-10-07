package com.investclass.ledger.impact;

import com.investclass.ledger.core.Event;
import com.investclass.ledger.eod.EodDraftRepository;
import com.investclass.ledger.ledger.EventRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 迟到事件影响预览：
 * 新事件登记后（或发布期间发现迟到事件时），对照该账户全部已发布快照，
 * 把“生效日 &lt;= 快照日”的原因登记为待复核影响项。
 *
 * 边界承诺：
 *  - 只向 late_event_impact 追加行；不撤销旧快照、不改写历史投影；
 *  - 事件 id &lt;= 快照水位说明已纳入该快照，不属于迟到，跳过；
 *  - 只影响未来日期的事件（全部生效日 &gt; 快照日）不会污染更早快照；
 *  - 影响项只标待复核，不伪造金额差值；
 *  - 幂等：唯一约束去重，重复导入被拦截时调用方根本不会触发评估。
 */
@Service
public class LateEventImpactService {

    private final EventRepository events;
    private final EodDraftRepository drafts;
    private final LateEventImpactRepository impacts;

    public LateEventImpactService(EventRepository events, EodDraftRepository drafts,
                                  LateEventImpactRepository impacts) {
        this.events = events;
        this.drafts = drafts;
        this.impacts = impacts;
    }

    /**
     * 评估一个事件对当前全部已发布快照的影响，返回新登记的影响项数量。
     * 可重复调用（幂等）。
     */
    public int evaluate(Event e) {
        if (e.id() == null) {
            return 0;
        }
        List<EodDraftRepository.PublishedSnapshot> published =
                drafts.publishedSnapshots(e.accountId());
        if (published.isEmpty()) {
            return 0;
        }
        List<Event> sameInstrument =
                events.findByAccountAndInstrument(e.accountId(), e.instrument());
        List<LateEventImpactPlanner.ImpactReason> reasons =
                LateEventImpactPlanner.reasons(e, sameInstrument);

        int inserted = 0;
        for (EodDraftRepository.PublishedSnapshot p : published) {
            if (e.id() <= p.watermarkEventId()) {
                continue; // 已包含在该快照水位内，非迟到
            }
            for (LateEventImpactPlanner.ImpactReason r : reasons) {
                if (r.effectiveDate().isAfter(p.businessDate())) {
                    continue; // 只影响未来日期，不污染该快照
                }
                if (impacts.insertIfAbsent(e.id(), e.accountId(), e.instrument(),
                        r.effectiveDate(), p.businessDate(), p.watermarkEventId(),
                        r.code(), r.relatedEventId(), r.detail())) {
                    inserted++;
                }
            }
        }
        return inserted;
    }
}
