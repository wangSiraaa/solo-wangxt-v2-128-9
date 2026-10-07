import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BusinessEvent, LateImpact, LateImpactPreview } from './models';

/**
 * 迟到事件影响预览。
 *
 * 上半部分是一条时间线，把两类“水位”画在同一时间轴上：
 *  - 已发布快照（蓝点，冻结事件水位 + 待复核影响数）；
 *  - 发布后迟到的事件（橙点，标注交易日/结算日/除权/登记/支付/到账日）。
 * 下半部分按已发布快照列出受影响范围与原因。全部影响只标“待复核”，
 * 不展示也不计算任何差值金额；预览不撤销旧快照、不改写历史投影。
 */
@Component({
  selector: 'app-late-impact',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="panel">
      <div class="row" style="justify-content:space-between">
        <h2 style="margin:0">迟到事件影响预览</h2>
        <button class="primary" [disabled]="scanning" (click)="scan.emit()">
          {{ scanning ? '扫描中…' : '重新扫描影响' }}
        </button>
      </div>
      <div class="spacer"></div>
      <div class="alert" style="background:#2e2410;border:1px solid #6e5320">
        仅依据迟到事件的业务日、结算日及相关除权/登记/支付日标记
        <strong>可能需要重新复核</strong>的已发布快照。预览不会撤销旧快照、不自动改写历史投影；
        无法确定精确金额的影响一律标为<span class="badge late">待复核</span>，不提供伪造差值。
      </div>

      <div class="stat-grid" style="grid-template-columns:repeat(3,1fr)">
        <div class="stat"><div class="k">已发布快照</div>
          <div class="v">{{ preview?.snapshots?.length || 0 }}</div></div>
        <div class="stat"><div class="k">发布后迟到事件</div>
          <div class="v">{{ preview?.lateEvents?.length || 0 }}</div></div>
        <div class="stat"><div class="k">待复核影响项</div>
          <div class="v" [class.diff-bad]="(preview?.pendingCount || 0) > 0">
            {{ preview?.pendingCount || 0 }}</div></div>
      </div>

      <div class="spacer"></div>

      <h2 style="font-size:13px">事件水位时间线</h2>
      <ul class="tl" *ngIf="nodes.length; else noPreview">
        <li *ngFor="let n of nodes" class="tl-item" [class.snap]="n.kind==='snap'">
          <span class="tl-dot" [class.snap-dot]="n.kind==='snap'"
            [class.late-dot]="n.kind==='late'"></span>
          <div class="tl-body">
            <div class="tl-head">
              <span class="mono">{{ n.date }}</span>
              <span *ngIf="n.kind==='snap'" class="badge calc">已发布快照 · 水位 #{{ n.watermark }}</span>
              <span *ngIf="n.kind==='late'" class="badge late">迟到事件 #{{ n.event?.id }}</span>
              <span *ngIf="n.kind==='snap' && n.pendingCount! > 0" class="badge bad">
                {{ n.pendingCount }} 项待复核</span>
            </div>
            <div *ngIf="n.kind==='snap'" class="muted" style="margin-top:3px">
              正式视图冻结于事件水位 #{{ n.watermark }}；之后到达的事件不进入该快照。
            </div>
            <div *ngIf="n.kind==='late'" class="tl-event">
              <span class="badge" [class.trade]="n.event?.eventType==='TRADE'"
                [class.split]="n.event?.eventType==='STOCK_SPLIT'"
                [class.dividend]="n.event?.eventType==='CASH_DIVIDEND'"
                [class.rights]="n.event?.eventType==='RIGHTS_OFFER'">
                {{ label(n.event!.eventType) }} · {{ n.event?.instrument }}</span>
              <span class="muted">交易日/除权 {{ n.event?.businessDate }}</span>
              <span class="muted">结算 {{ n.event?.settlementDate || '—' }}</span>
              <span class="muted">登记 {{ n.event?.recordDate || '—' }}</span>
              <span class="muted">支付 {{ n.event?.paymentDate || '—' }}</span>
              <span class="muted">到账 {{ n.event?.allotmentDate || '—' }}</span>
              <div class="mono muted" style="margin-top:2px">{{ detail(n.event!) }}</div>
            </div>
          </div>
        </li>
      </ul>
      <ng-template #noPreview>
        <div class="muted">尚无已发布快照或迟到事件，点击“重新扫描影响”生成预览。</div>
      </ng-template>
    </div>

    <div class="panel" *ngIf="preview && preview.impacts.length">
      <h2>受影响范围（按已发布快照）</h2>
      <table>
        <thead>
          <tr>
            <th>快照日</th><th>迟到事件</th><th>证券</th><th>原因</th>
            <th>相关业务日</th><th>状态</th><th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let r of preview.impacts">
            <td class="mono">{{ r.snapshotDate }}</td>
            <td class="num mono">#{{ r.eventId }}</td>
            <td class="mono">{{ r.instrument }}</td>
            <td><span class="badge late">{{ reason(r.reasonCode) }}</span></td>
            <td class="mono">{{ r.relatedDate || '—' }}</td>
            <td><span class="badge" [class.bad]="r.status==='PENDING_REVIEW'"
                      [class.ok]="r.status!=='PENDING_REVIEW'">
              {{ r.status === 'PENDING_REVIEW' ? '待复核' : r.status }}</span></td>
            <td class="muted">{{ r.detail }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    .tl { list-style: none; margin: 8px 0 0; padding: 0 0 0 6px; }
    .tl-item { position: relative; padding: 0 0 18px 22px; border-left: 2px solid var(--border); }
    .tl-item:last-child { border-left-color: transparent; padding-bottom: 0; }
    .tl-dot { position: absolute; left: -7px; top: 2px; width: 12px; height: 12px;
      border-radius: 50%; background: var(--muted); }
    .snap-dot { background: var(--accent); box-shadow: 0 0 0 3px rgba(78,161,255,.18); }
    .late-dot { background: var(--amber); box-shadow: 0 0 0 3px rgba(210,153,34,.18); }
    .tl-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .tl-event { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; margin-top: 5px; }
  `]
})
export class LateImpactComponent {
  @Input() preview: LateImpactPreview | null = null;
  @Input() scanning = false;
  @Output() scan = new EventEmitter<void>();

  /** 合并快照水位与迟到事件到同一时间轴，按日期（同日快照在前）排序。 */
  get nodes(): TimelineNode[] {
    if (!this.preview) {
      return [];
    }
    const nodes: TimelineNode[] = [];
    for (const s of this.preview.snapshots) {
      nodes.push({ kind: 'snap', date: s.businessDate, watermark: s.watermarkEventId,
        pendingCount: s.pendingCount });
    }
    for (const e of this.preview.lateEvents) {
      nodes.push({ kind: 'late', date: e.businessDate, event: e });
    }
    return nodes.sort((a, b) =>
      a.date < b.date ? -1 : a.date > b.date ? 1
        : (a.kind === 'snap' ? -1 : 1));
  }

  label(t: string): string {
    return ({ TRADE: '成交', STOCK_SPLIT: '拆股', CASH_DIVIDEND: '分红',
      RIGHTS_OFFER: '配股' } as Record<string, string>)[t] || t;
  }

  detail(e: BusinessEvent): string {
    const p = e.payload as any;
    switch (e.eventType) {
      case 'TRADE':
        return `${p.side === 'BUY' ? '买' : '卖'} ${p.quantity} @ ${p.price}`;
      case 'STOCK_SPLIT':
        return `比例 ×${p.ratio}`;
      case 'CASH_DIVIDEND':
        return `每股 ${p.amountPerShare}`;
      case 'RIGHTS_OFFER':
        return `每股权 ${p.rightsPerShare} @ ${p.subscriptionPrice} 认购 ${p.subscribedQty ?? 0}`;
      default:
        return '';
    }
  }

  reason(code: string): string {
    return (LateImpactComponent.REASONS as Record<string, string>)[code] || code;
  }

  private static readonly REASONS: Record<string, string> = {
    TRADE_SETTLED_IN_SNAPSHOT: '成交结算落在快照内',
    CROSS_RECORD_DIVIDEND: '跨分红登记日',
    DIVIDEND_CASH_PAYMENT: '分红现金待复核',
    CROSS_RECORD_RIGHTS: '跨配股登记日',
    RIGHTS_PAYMENT_IMPACT: '配股扣款待复核',
    RIGHTS_ALLOTMENT_IMPACT: '配股到账批次待复核',
    INTRANSIT_SPLIT_ADJUST: '在途遇拆股折算',
    SPLIT_EX_DATE_PAST: '拆股除权日已过',
    DIVIDEND_RECORD_PAST: '分红登记日已过',
    DIVIDEND_PAY_DATE_PAST: '分红支付日已过',
    RIGHTS_RECORD_PAST: '配股登记日已过',
    RIGHTS_PAY_DATE_PAST: '配股支付日已过',
    RIGHTS_ALLOT_DATE_PAST: '配股到账日已过'
  };
}

interface TimelineNode {
  kind: 'snap' | 'late';
  date: string;
  watermark?: number;
  pendingCount?: number;
  event?: BusinessEvent;
}
