/** 不可变业务事件（账本唯一事实源）。 */
export interface BusinessEvent {
  id: number;
  eventType: 'TRADE' | 'STOCK_SPLIT' | 'CASH_DIVIDEND' | 'RIGHTS_OFFER';
  accountId: string;
  instrument: string;
  businessDate: string;
  settlementDate: string | null;
  recordDate: string | null;
  paymentDate: string | null;
  allotmentDate: string | null;
  payload: Record<string, unknown>;
  sourceSystem: string;
  sourceKey: string;
  idempotencyKey: string;
  late: boolean;
  ingestedAt: string;
}

/** 成本批次（FIFO），含来源链与零碎股标记。 */
export interface Lot {
  id: number;
  lotKey: string;
  instrument: string;
  openingEventId: number;
  sourceEventType: string;
  acquiredDate: string;
  openQty: string;
  remainingQty: string;
  unitCost: string;
  totalCost: string;
  remainingCost: string;
  fractional: boolean;
  closed: boolean;
  derivedFromLotKey: string | null;
  adjustedByEventId: number | null;
}

export interface CashEntry {
  eventId: number;
  businessDate: string;
  valueDate: string;
  effectKey: string;
  direction: 'IN' | 'OUT';
  amount: string;
  category: string;
  idemKey: string;
}

export interface Entitlement {
  eventId: number;
  instrument: string;
  kind: string;
  recordDate: string;
  paymentDate: string | null;
  eligibleQty: string;
  amountPerShare: string | null;
  grossAmount: string | null;
  status: string;
  subscribedQty: string;
}

export interface Checkpoint {
  eventId: number;
  effectKey: string;
  businessDate: string;
  stage: string;
  sharesHash: string;
  cashBalance: string;
  openCost: string;
  realizedPnl: string;
}

export interface Cursor {
  lastEventId: number;
  lastBusinessDate: string | null;
  lastStage: string | null;
  lastEffectKey: string | null;
}

export interface ReconciliationLine {
  instrument: string;
  projectedQty: string;
  projectedFractionalQty: string;
  projectedOpenCost: string;
  externalQty: string;
  externalFractionalQty: string;
  externalCost: string;
  externalCash: string | null;
  qtyMatch: boolean;
  costMatch: boolean;
}

export interface ReconciliationReport {
  accountId: string;
  businessDate: string;
  watermarkEventId: number;
  qtyBalanced: boolean;
  cashBalanced: boolean;
  costBalanced: boolean;
  bookVsExternalBalanced: boolean;
  earliestMismatchEventId: number | null;
  mismatchDetail: string | null;
  allBalanced: boolean;
  positions: ReconciliationLine[];
  projectedCash: string;
  externalCash: string | null;
}

/** 已正式发布（冻结水位）的日终快照。 */
export interface PublishedSnapshot {
  businessDate: string;
  watermarkEventId: number;
  publishedAt: string;
}

/**
 * 迟到事件影响项：只标记待复核，不携带任何金额差值
 * （精确金额只能由事件账本重放复算，预览不伪造差值）。
 */
export interface LateImpact {
  id: number;
  eventId: number;
  eventType: string;
  instrument: string;
  businessDate: string;
  settlementDate: string | null;
  recordDate: string | null;
  paymentDate: string | null;
  allotmentDate: string | null;
  effectiveDate: string;
  snapshotDate: string;
  snapshotWatermarkEventId: number;
  reasonCode: string;
  relatedEventId: number;
  reasonDetail: string;
  status: 'PENDING_REVIEW' | 'REVIEWED';
  createdAt: string;
}

/** 迟到影响预览看板：已发布水位时间线 + 待复核影响项。 */
export interface LateImpactBoard {
  published: PublishedSnapshot[];
  impacts: LateImpact[];
}
