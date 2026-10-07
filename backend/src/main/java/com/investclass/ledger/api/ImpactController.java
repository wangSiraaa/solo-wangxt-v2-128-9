package com.investclass.ledger.api;

import com.investclass.ledger.eod.EodDraftRepository;
import com.investclass.ledger.impact.LateEventImpactRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 迟到事件影响预览（只读）：
 * 已发布快照水位 + 迟到事件对哪些已发布快照需重新复核。
 * 预览不撤销旧快照、不自动改写历史投影；影响项一律待复核，不提供伪造差值。
 */
@RestController
public class ImpactController {

    private final LateEventImpactRepository impacts;
    private final EodDraftRepository drafts;

    public ImpactController(LateEventImpactRepository impacts, EodDraftRepository drafts) {
        this.impacts = impacts;
        this.drafts = drafts;
    }

    /** 影响预览看板：published = 已发布快照水位时间线；impacts = 待复核影响项。 */
    @GetMapping("/api/accounts/{accountId}/late-impacts")
    public Map<String, Object> board(@PathVariable String accountId) {
        return Map.of(
                "published", drafts.publishedSnapshots(accountId),
                "impacts", impacts.listByAccount(accountId));
    }
}
