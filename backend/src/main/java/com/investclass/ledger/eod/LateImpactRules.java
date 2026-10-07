package com.investclass.ledger.eod;

import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 迟到事件影响预览规则（纯函数、确定性、零金额估算）。
 *
 * <p>正式日终快照发布后又导入业务日更早的事件时，本类只依据新事件的业务日、结算日以及
 * 相关的除权日/登记日/支付日，<b>判定哪些已发布快照可能需要重新复核并给出原因</b>。
 *
 * <ul>
 *   <li>只标记“可能受影响”，一律 {@link #STATUS_REVIEW}，绝不计算或伪造差值金额；</li>
 *   <li>只列出效应日（结算/除权/登记/支付/到账）&le; 快照日的影响，
 *       只影响未来日期的事件不会污染更早的快照；</li>
 *   <li>跨事件的资格/拆股影响还要求相关公司行动在快照冻结水位内（事件 id &le; 水位），
 *       否则发布当时该公司行动尚不存在，不产生影响项；</li>
 *   <li>本预览不撤销旧快照、不改写历史投影，结果仅用于复核提示。</li>
 * </ul>
 */
public final class LateImpactRules {

    private LateImpactRules() {
    }

    /** 待复核：无法确定精确差值，只提示可能受影响，不伪造金额。 */
    public static final String STATUS_REVIEW = "PENDING_REVIEW";

    // ---- 原因代码（同时用于幂等键，禁止随意改名） ----
    /** 迟到成交的结算日落在快照日前/当日：股份与现金交割本应进入该正式视图。 */
    public static final String REASON_TRADE_SETTLED = "TRADE_SETTLED_IN_SNAPSHOT";
    /** 迟到成交跨分红登记日：发布时在册资格股数可能变化，分红资格需复核。 */
    public static final String REASON_CROSS_RECORD_DIVIDEND = "CROSS_RECORD_DIVIDEND";
    /** 迟到成交改变了登记日在册数量，分红支付日现金可能变化。 */
    public static final String REASON_DIVIDEND_CASH = "DIVIDEND_CASH_PAYMENT";
    /** 迟到成交跨配股登记日：可认购权数与资格状态可能变化。 */
    public static final String REASON_CROSS_RECORD_RIGHTS = "CROSS_RECORD_RIGHTS";
    /** 迟到成交改变配股认购，支付日扣款可能变化。 */
    public static final String REASON_RIGHTS_PAYMENT = "RIGHTS_PAYMENT_IMPACT";
    /** 迟到成交改变在册数量，配股到账批次可能变化。 */
    public static final String REASON_RIGHTS_ALLOTMENT = "RIGHTS_ALLOTMENT_IMPACT";
    /** 迟到成交在途期间遇拆股：结算数量/复权价被折算，影响含除权日的快照。 */
    public static final String REASON_INTRANSIT_SPLIT = "INTRANSIT_SPLIT_ADJUST";
    /** 迟到拆股的除权日在快照日前/当日：发布时批次拆分结果可能变化。 */
    public static final String REASON_SPLIT_EX_PAST = "SPLIT_EX_DATE_PAST";
    /** 迟到分红的登记日在快照日前/当日：在册资格可能变化。 */
    public static final String REASON_DIVIDEND_RECORD_PAST = "DIVIDEND_RECORD_PAST";
    /** 迟到分红的支付日在快照日前/当日：现金入账可能变化。 */
    public static final String REASON_DIVIDEND_PAY_PAST = "DIVIDEND_PAY_DATE_PAST";
    /** 迟到配股的登记日在快照日前/当日：可认购权数可能变化。 */
    public static final String REASON_RIGHTS_RECORD_PAST = "RIGHTS_RECORD_PAST";
    /** 迟到配股的支付日在快照日前/当日：认购扣款可能变化。 */
    public static final String REASON_RIGHTS_PAY_PAST = "RIGHTS_PAY_DATE_PAST";
    /** 迟到配股的到账日在快照日前/当日：新增成本批次可能变化。 */
    public static final String REASON_RIGHTS_ALLOT_PAST = "RIGHTS_ALLOT_DATE_PAST";

    public record Finding(long eventId, LocalDate snapshotDate, String reasonCode,
                          LocalDate relatedDate, String instrument, String detail) {
    }

    public record PublishedSnapshot(LocalDate businessDate, long watermarkEventId) {
    }

    /**
     * 评估单个迟到事件对账户全部已发布快照的可能影响。
     *
     * @param lateEvent 发布后才导入的事件
     * @param snapshots 该账户全部已发布快照（业务日升序）
     * @param knownEvents 快照水位判定用的全部已知事件（用于定位相关公司行动/拆股）
     */
    public static List<Finding> evaluate(Event lateEvent,
                                         List<PublishedSnapshot> snapshots,
                                         List<Event> knownEvents) {
        List<Finding> out = new ArrayList<>();
        for (PublishedSnapshot snap : snapshots) {
            evaluateAgainst(lateEvent, snap, knownEvents, out);
        }
        return out;
    }

    private static void evaluateAgainst(Event late, PublishedSnapshot snap,
                                        List<Event> all, List<Finding> out) {
        LocalDate d = snap.businessDate();
        if (late.type() == EventType.TRADE) {
            tradeFindings(late, d, snap.watermarkEventId(), all, out);
        } else if (late.type() == EventType.STOCK_SPLIT) {
            // 拆股在除权日（业务日）改批次
            if (!late.businessDate().isAfter(d)) {
                out.add(finding(late, d, REASON_SPLIT_EX_PAST, late.businessDate(),
                        "迟到拆股除权日 " + late.businessDate()
                                + " 不晚于快照日，发布时批次折算结果可能变化，待复核。"));
            }
        } else if (late.type() == EventType.CASH_DIVIDEND) {
            if (late.recordDate() != null && !late.recordDate().isAfter(d)) {
                out.add(finding(late, d, REASON_DIVIDEND_RECORD_PAST, late.recordDate(),
                        "迟到分红登记日 " + late.recordDate()
                                + " 不晚于快照日，登记日在册资格可能变化，待复核。"));
            }
            if (late.paymentDate() != null && !late.paymentDate().isAfter(d)) {
                out.add(finding(late, d, REASON_DIVIDEND_PAY_PAST, late.paymentDate(),
                        "迟到分红支付日 " + late.paymentDate()
                                + " 不晚于快照日，现金入账可能变化，待复核。"));
            }
        } else if (late.type() == EventType.RIGHTS_OFFER) {
            if (late.recordDate() != null && !late.recordDate().isAfter(d)) {
                out.add(finding(late, d, REASON_RIGHTS_RECORD_PAST, late.recordDate(),
                        "迟到配股登记日 " + late.recordDate()
                                + " 不晚于快照日，可认购权数可能变化，待复核。"));
            }
            if (late.paymentDate() != null && !late.paymentDate().isAfter(d)) {
                out.add(finding(late, d, REASON_RIGHTS_PAY_PAST, late.paymentDate(),
                        "迟到配股支付日 " + late.paymentDate()
                                + " 不晚于快照日，认购扣款可能变化，待复核。"));
            }
            if (late.allotmentDate() != null && !late.allotmentDate().isAfter(d)) {
                out.add(finding(late, d, REASON_RIGHTS_ALLOT_PAST, late.allotmentDate(),
                        "迟到配股到账日 " + late.allotmentDate()
                                + " 不晚于快照日，新增成本批次可能变化，待复核。"));
            }
        }
    }

    private static void tradeFindings(Event late, LocalDate d, long watermark,
                                      List<Event> all, List<Finding> out) {
        LocalDate settle = late.settlementDate();
        if (settle == null) {
            return;
        }
        // 结算日晚于快照日：股份/现金在发布当日尚未交割，不污染更早快照。
        if (settle.isAfter(d)) {
            return;
        }

        boolean buy = late.payload().asTrade().isBuy();
        String side = buy ? "买入" : "卖出";
        // 结算交割落在快照窗口内：持仓数量/成本与现金都可能变化
        out.add(finding(late, d, REASON_TRADE_SETTLED, settle,
                "迟到" + side + "结算日 " + settle + " 不晚于快照日，"
                        + (buy ? "股份应已入账、现金应已流出" : "股份应已交割、现金应已流入")
                        + "，正式视图可能变化，待复核。"));

        // 在途期间（业务日, 结算日] 遇拆股除权日：结算数量/复权价被折算
        for (Event s : all) {
            if (s.type() == EventType.STOCK_SPLIT && s.instrument().equals(late.instrument())
                    && s.id() <= watermark) {
                LocalDate ex = s.businessDate();
                if (ex.isAfter(late.businessDate()) && !ex.isAfter(settle)) {
                    out.add(finding(late, d, REASON_INTRANSIT_SPLIT, ex,
                            "迟到" + side + "在途期间遇除权日 " + ex + " 拆股，"
                                    + "结算数量/复权价被折算，正式视图可能变化，待复核。"));
                }
            }
        }

        // 跨登记日：成交在登记日（含）前结算交割，改变登记日在册数量
        for (Event ca : all) {
            if (ca.id() > watermark || !ca.instrument().equals(late.instrument())) {
                continue;
            }
            LocalDate record = ca.recordDate();
            if (record == null || settle.isAfter(record)) {
                continue; // 登记日尚未交割，不影响在册
            }
            if (ca.type() == EventType.CASH_DIVIDEND) {
                out.add(finding(late, d, REASON_CROSS_RECORD_DIVIDEND, record,
                        "迟到" + side + "于分红登记日 " + record
                                + "（含）前结算交割，发布时在册资格股数可能变化，分红资格待复核。"));
                LocalDate pay = ca.paymentDate();
                if (pay != null && !pay.isAfter(d)) {
                    out.add(finding(late, d, REASON_DIVIDEND_CASH, pay,
                            "登记日在册数量变化且分红支付日 " + pay
                                    + " 不晚于快照日，分红现金可能变化，待复核。"));
                }
            } else if (ca.type() == EventType.RIGHTS_OFFER) {
                out.add(finding(late, d, REASON_CROSS_RECORD_RIGHTS, record,
                        "迟到" + side + "于配股登记日 " + record
                                + "（含）前结算交割，可认购权数与资格状态可能变化，待复核。"));
                LocalDate pay = ca.paymentDate();
                if (pay != null && !pay.isAfter(d)) {
                    out.add(finding(late, d, REASON_RIGHTS_PAYMENT, pay,
                            "配股登记日在册数量变化且支付日 " + pay
                                    + " 不晚于快照日，认购扣款可能变化，待复核。"));
                }
                LocalDate allot = ca.allotmentDate();
                if (allot != null && !allot.isAfter(d)) {
                    out.add(finding(late, d, REASON_RIGHTS_ALLOTMENT, allot,
                            "配股登记日在册数量变化且到账日 " + allot
                                    + " 不晚于快照日，配股新增成本批次可能变化，待复核。"));
                }
            }
        }
    }

    private static Finding finding(Event e, LocalDate snapshotDate, String reason,
                                   LocalDate relatedDate, String detail) {
        return new Finding(e.id(), snapshotDate, reason, relatedDate, e.instrument(), detail);
    }
}
