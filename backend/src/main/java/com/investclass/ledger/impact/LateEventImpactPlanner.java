package com.investclass.ledger.impact;

import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 迟到事件影响预览的纯函数规划器（确定性，无副作用）。
 *
 * 依据新事件的业务日、结算日及相关除权/登记/支付/到账日，推导它可能波及的
 * “生效日 + 原因”。生效日 &lt;= 某已发布快照日的，该快照即需重新复核。
 *
 * 只标记“待复核”，不计算任何金额差值：精确金额只能由事件账本重放复算，
 * 预览阶段不伪造差值。
 */
public final class LateEventImpactPlanner {

    /** 无关联事件时 related_event_id 使用 0（参与唯一约束，保证幂等）。 */
    public static final long NO_RELATED = 0L;

    private LateEventImpactPlanner() {
    }

    /**
     * @param effectiveDate 该原因开始影响账面的业务日
     * @param code          原因码（见各 plan* 方法）
     * @param relatedEventId 关联事件（如被改变资格的分红/配股、被重折算的在途成交），无则 0
     * @param detail        人类可读原因（不含任何伪造的金额差值）
     */
    public record ImpactReason(LocalDate effectiveDate, String code, long relatedEventId,
                               String detail) {
    }

    /**
     * @param e                    新登记的事件
     * @param sameInstrumentEvents 同账户同证券的全部已知事件（含 e 自身，用于关联分析）
     */
    public static List<ImpactReason> reasons(Event e, List<Event> sameInstrumentEvents) {
        List<ImpactReason> out = new ArrayList<>();
        switch (e.type()) {
            case TRADE -> planTrade(e, sameInstrumentEvents, out);
            case STOCK_SPLIT -> planSplit(e, sameInstrumentEvents, out);
            case CASH_DIVIDEND -> planDividend(e, out);
            case RIGHTS_OFFER -> planRights(e, out);
        }
        return out;
    }

    /** 成交：结算日交割变动；若在既有公司行动登记日（含）前交割，还会改变在册资格。 */
    private static void planTrade(Event e, List<Event> all, List<ImpactReason> out) {
        LocalDate settle = e.settlementDate();
        if (settle == null) {
            return;
        }
        out.add(new ImpactReason(settle, "TRADE_SETTLEMENT", NO_RELATED,
                "成交于 %s 结算交割，该日起持仓批次与现金变动".formatted(settle)));
        for (Event ca : all) {
            if (!ca.isCorporateAction() || ca.id() == null || ca.id().equals(e.id())
                    || ca.recordDate() == null) {
                continue;
            }
            // 登记日（含）前交割 ⇒ 参与该次权益的在册数量
            if (!settle.isAfter(ca.recordDate())) {
                String kind = ca.type() == EventType.CASH_DIVIDEND ? "分红" : "配股";
                out.add(new ImpactReason(ca.recordDate(), "RECORD_DATE_ELIGIBILITY", ca.id(),
                        "在 %s 登记日（含）前交割，改变 %s 在册资格（关联%s事件 #%d）"
                                .formatted(ca.recordDate(), e.instrument(), kind, ca.id())));
                if (ca.paymentDate() != null) {
                    out.add(new ImpactReason(ca.paymentDate(), "RELATED_PAYMENT", ca.id(),
                            "关联%s于 %s 支付，在册资格变化使入账金额待复核"
                                    .formatted(kind, ca.paymentDate())));
                }
            }
        }
    }

    /** 拆股：除权日调整既有批次；重折算跨除权日的在途成交与登记日资格。 */
    private static void planSplit(Event e, List<Event> all, List<ImpactReason> out) {
        LocalDate ex = e.businessDate();
        String ratio = e.payload().asSplit().ratio().stripTrailingZeros().toPlainString();
        out.add(new ImpactReason(ex, "SPLIT_ADJUSTMENT", NO_RELATED,
                "拆股除权日 %s，既有批次按 ×%s 调整（整股/零碎股拆批）".formatted(ex, ratio)));
        for (Event o : all) {
            if (o.id() != null && o.id().equals(e.id())) {
                continue;
            }
            if (o.type() == EventType.TRADE && o.settlementDate() != null
                    && o.businessDate().isBefore(ex) && !o.settlementDate().isBefore(ex)) {
                out.add(new ImpactReason(o.settlementDate(), "IN_FLIGHT_TRADE_RESCALE", o.id(),
                        "在途成交 #%d 跨越除权日 %s，结算数量按拆股比例重新折算"
                                .formatted(o.id(), ex)));
            }
            if (o.isCorporateAction() && o.recordDate() != null
                    && !o.recordDate().isBefore(ex)) {
                String kind = o.type() == EventType.CASH_DIVIDEND ? "分红" : "配股";
                out.add(new ImpactReason(o.recordDate(), "RECORD_DATE_ELIGIBILITY", o.id(),
                        "登记日 %s 在册数量按拆股比例重新折算（关联%s事件 #%d）"
                                .formatted(o.recordDate(), kind, o.id())));
                if (o.paymentDate() != null) {
                    out.add(new ImpactReason(o.paymentDate(), "RELATED_PAYMENT", o.id(),
                            "关联%s于 %s 支付，资格折算变化使入账金额待复核"
                                    .formatted(kind, o.paymentDate())));
                }
            }
        }
    }

    private static void planDividend(Event e, List<ImpactReason> out) {
        if (e.recordDate() != null) {
            out.add(new ImpactReason(e.recordDate(), "DIVIDEND_ENTITLEMENT", NO_RELATED,
                    "分红登记日 %s，新增权益资格（在册数量 × 每股 %s）"
                            .formatted(e.recordDate(),
                                    e.payload().asDividend().amountPerShare()
                                            .stripTrailingZeros().toPlainString())));
        }
        if (e.paymentDate() != null) {
            out.add(new ImpactReason(e.paymentDate(), "DIVIDEND_PAYMENT", NO_RELATED,
                    "分红支付日 %s，现金分红入账".formatted(e.paymentDate())));
        }
    }

    private static void planRights(Event e, List<ImpactReason> out) {
        if (e.recordDate() != null) {
            out.add(new ImpactReason(e.recordDate(), "RIGHTS_ENTITLEMENT", NO_RELATED,
                    "配股登记日 %s，新增配股资格".formatted(e.recordDate())));
        }
        if (e.paymentDate() != null) {
            out.add(new ImpactReason(e.paymentDate(), "RIGHTS_PAYMENT", NO_RELATED,
                    "配股支付日 %s，认购扣款".formatted(e.paymentDate())));
        }
        if (e.allotmentDate() != null) {
            out.add(new ImpactReason(e.allotmentDate(), "RIGHTS_ALLOTMENT", NO_RELATED,
                    "配股到账日 %s，新增独立成本批次".formatted(e.allotmentDate())));
        }
    }
}
