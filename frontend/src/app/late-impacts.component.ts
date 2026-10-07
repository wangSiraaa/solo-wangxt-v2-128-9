import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LateImpact, LateImpactBoard } from './models';

interface ImpactTarget {
  snapshotDate: string;
  watermarkEventId: number;
  reasons: LateImpact[];
}

interface EventGroup {
  eventId: number;
  eventType: string;
  instrument: string;
  businessDate: string;
  targets: ImpactTarget[];
}

/**
 * 迟到事件影响预览：
 *  - 时间线：已发布快照水位（刻度）× 迟到事件（行）× 受影响快照范围（条带）；
 *  - 明细：每条影响只标“待复核”，不展示任何金额差值——
 *    精确金额只能由事件账本重放复算，预览不伪造差值；
 *  - 只读视图：不撤销旧快照、不改写历史投影。
 */
@Component({
  selector: 'app-late-impacts',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="panel">
      <h2>迟到事件影响预览
        <span class="muted" style="font-weight:400">
          只标记待复核 · 不撤销已发布快照 · 不改写历史投影 · 金额须重放复算，不提供差值
        </span>
      </h2>

      <div *ngIf="!board || !board.published.length" class="muted">
        尚无已发布的正式快照。发布日终快照后，之后到达的迟到事件会在这里按时间线标出影响范围。
      </div>

      <ng-container *ngIf="board && board.published.length">
        <div class="row" style="align-items:stretch">
          <div class="stat" *ngFor="let p of board.published" style="min-width:170px">
            <div class="k">已发布快照 {{ p.businessDate }}</div>
            <div class="v" style="font-size:16px">水位 #{{ p.watermarkEventId }}</div>
            <div class="k" style="margin-top:4px">
              <span *ngIf="pendingOn(p.businessDate); else clean"
                    class="badge pending">{{ pendingOn(p.businessDate) }} 项待复核</span>
              <ng-template #clean><span class="diff-ok">未受迟到事件影响</span></ng-template>
            </div>
          </div>
        </div>

        <div class="spacer"></div>

        <div *ngIf="groups.length; else noImpact" class="tl">
          <div class="tl-ruler">
            <div class="tl-marker" *ngFor="let p of board.published"
                 [style.left.%]="pct(p.businessDate)">
              <span class="tl-tick"></span>
              <span class="tl-label mono">{{ p.businessDate.slice(5) }} · 水位#{{ p.watermarkEventId }}</span>
            </div>
          </div>
          <div class="tl-row" *ngFor="let g of groups">
            <div class="tl-event">
              <span class="mono">#{{ g.eventId }}</span>
              <span class="badge" [ngClass]="typeClass(g.eventType)">{{ typeLabel(g.eventType) }}</span>
              <span class="mono">{{ g.instrument }}</span>
              <span class="muted mono">{{ g.businessDate }}</span>
            </div>
            <div class="tl-track">
              <span class="tl-dot" [style.left.%]="pct(g.businessDate)"></span>
              <div class="tl-bar" *ngFor="let t of g.targets"
                   [style.left.%]="pct(g.businessDate)"
                   [style.width.%]="spanPct(g.businessDate, t.snapshotDate)"
                   [title]="tooltip(t)">
                <span class="tl-end mono">{{ t.snapshotDate.slice(5) }}</span>
              </div>
            </div>
          </div>
          <div class="muted" style="margin-top:8px; font-size:11px">
            圆点 = 迟到事件业务日；条带 = 从业务日到受影响的已发布快照日（悬停查看原因）。
          </div>
        </div>
        <ng-template #noImpact>
          <div class="alert ok">当前没有迟到事件影响任何已发布快照。</div>
        </ng-template>
      </ng-container>
    </div>

    <div class="panel" *ngIf="board && board.impacts.length">
      <h2>待复核影响明细（{{ board.impacts.length }}）</h2>
      <table>
        <thead>
          <tr>
            <th>事件</th><th>类型</th><th>证券</th><th>事件业务日</th>
            <th>影响生效日</th><th>受影响快照</th><th class="num">快照水位</th>
            <th>原因</th><th>说明</th><th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let i of board.impacts">
            <td class="num mono">#{{ i.eventId }}</td>
            <td><span class="badge" [ngClass]="typeClass(i.eventType)">{{ typeLabel(i.eventType) }}</span></td>
            <td class="mono">{{ i.instrument }}</td>
            <td>{{ i.businessDate }}</td>
            <td>{{ i.effectiveDate }}</td>
            <td>{{ i.snapshotDate }}</td>
            <td class="num mono">#{{ i.snapshotWatermarkEventId }}</td>
            <td><span class="badge calc">{{ reasonLabel(i.reasonCode) }}</span></td>
            <td class="muted">{{ i.reasonDetail }}<span *ngIf="i.relatedEventId" class="mono">（关联事件 #{{ i.relatedEventId }}）</span></td>
            <td><span class="badge pending">待复核</span></td>
          </tr>
        </tbody>
      </table>
    </div>
  `
})
export class LateImpactsComponent {
  @Input() board: LateImpactBoard | null = null;

  get groups(): EventGroup[] {
    if (!this.board) {
      return [];
    }
    const byEvent = new Map<number, EventGroup>();
    for (const i of this.board.impacts) {
      let g = byEvent.get(i.eventId);
      if (!g) {
        g = {
          eventId: i.eventId, eventType: i.eventType, instrument: i.instrument,
          businessDate: i.businessDate, targets: []
        };
        byEvent.set(i.eventId, g);
      }
      let t = g.targets.find(x => x.snapshotDate === i.snapshotDate);
      if (!t) {
        t = {
          snapshotDate: i.snapshotDate,
          watermarkEventId: i.snapshotWatermarkEventId,
          reasons: []
        };
        g.targets.push(t);
      }
      t.reasons.push(i);
    }
    return [...byEvent.values()]
      .sort((a, b) => a.eventId - b.eventId)
      .map(g => ({
        ...g,
        targets: g.targets.sort((a, b) => a.snapshotDate.localeCompare(b.snapshotDate))
      }));
  }

  pendingOn(snapshotDate: string): number {
    return this.board ? this.board.impacts.filter(i => i.snapshotDate === snapshotDate).length : 0;
  }

  /** 时间线横轴：覆盖已发布快照日与迟到事件业务日，左右各留 4% 边距。 */
  pct(date: string): number {
    const t = Date.parse(date + 'T00:00:00Z');
    const { min, max } = this.range();
    if (max <= min) {
      return 50;
    }
    return 4 + ((t - min) / (max - min)) * 92;
  }

  spanPct(from: string, to: string): number {
    return Math.max(0.6, this.pct(to) - this.pct(from));
  }

  tooltip(t: ImpactTarget): string {
    return t.reasons.map(r => `${r.effectiveDate} · ${r.reasonDetail}`).join('\n');
  }

  typeLabel(t: string): string {
    return ({ TRADE: '成交', STOCK_SPLIT: '拆股', CASH_DIVIDEND: '分红', RIGHTS_OFFER: '配股' } as Record<string, string>)[t] || t;
  }

  typeClass(t: string): string {
    return ({ TRADE: 'trade', STOCK_SPLIT: 'split', CASH_DIVIDEND: 'dividend', RIGHTS_OFFER: 'rights' } as Record<string, string>)[t] || '';
  }

  reasonLabel(code: string): string {
    return ({
      TRADE_SETTLEMENT: '成交结算',
      RECORD_DATE_ELIGIBILITY: '登记日资格',
      RELATED_PAYMENT: '关联支付',
      SPLIT_ADJUSTMENT: '拆股调整',
      IN_FLIGHT_TRADE_RESCALE: '在途折算',
      DIVIDEND_ENTITLEMENT: '分红资格',
      DIVIDEND_PAYMENT: '分红入账',
      RIGHTS_ENTITLEMENT: '配股资格',
      RIGHTS_PAYMENT: '配股扣款',
      RIGHTS_ALLOTMENT: '配股到账'
    } as Record<string, string>)[code] || code;
  }

  private range(): { min: number; max: number } {
    const dates: string[] = [];
    if (this.board) {
      for (const p of this.board.published) {
        dates.push(p.businessDate);
      }
      for (const i of this.board.impacts) {
        dates.push(i.businessDate, i.snapshotDate, i.effectiveDate);
      }
    }
    if (!dates.length) {
      return { min: 0, max: 0 };
    }
    const ts = dates.map(d => Date.parse(d + 'T00:00:00Z'));
    return { min: Math.min(...ts), max: Math.max(...ts) };
  }
}
