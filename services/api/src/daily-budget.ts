import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';

export type DailyReservationBinding = { bindingId: string; jobId: string; budgetDate: string };
export async function reserveDailyCost(tx: PoolClient, binding: DailyReservationBinding,
  costCenter: 'content'|'review', maxNanoUsd: bigint): Promise<string> {
  if (!/^[0-9a-f-]{36}$/i.test(binding.bindingId) || !/^[0-9a-f-]{36}$/i.test(binding.jobId) || !/^\d{4}-\d{2}-\d{2}$/.test(binding.budgetDate) ||
      !['content','review'].includes(costCenter) || maxNanoUsd <= 0n) throw new Error('invalid_daily_reservation');
  const old = await tx.query<{id:string;max_nano_usd:string}>(`SELECT id,max_nano_usd FROM daily_cost_reservations
    WHERE cost_center=$1 AND binding_id=$2 FOR UPDATE`, [costCenter,binding.bindingId]);
  if (old.rows[0]) {
    if (BigInt(old.rows[0].max_nano_usd) !== maxNanoUsd) throw new Error('daily_reservation_conflict');
    return old.rows[0].id;
  }
  const budget = await tx.query<{max_nano_usd:string}>(`SELECT max_nano_usd FROM daily_budget_limits
    WHERE cost_center=$1 AND budget_date=$2 FOR UPDATE`, [costCenter,binding.budgetDate]);
  const limit = BigInt(budget.rows[0]?.max_nano_usd ?? '0');
  const usedResult = await tx.query<{used:string}>(`SELECT COALESCE(SUM(CASE WHEN state='settled' THEN COALESCE(settled_nano_usd,0)
    ELSE max_nano_usd END),0)::text AS used FROM daily_cost_reservations
    WHERE cost_center=$1 AND budget_date=$2 AND state IN ('held','settled','unknown')`, [costCenter,binding.budgetDate]);
  const used = BigInt(usedResult.rows[0]?.used ?? '0');
  if (!budget.rows[0] || limit === 0n || used + maxNanoUsd > limit) throw new Error('daily_budget_unavailable');
  const id = randomUUID();
  await tx.query(`INSERT INTO daily_cost_reservations(id,job_id,cost_center,budget_date,binding_id,max_nano_usd,state)
    VALUES($1,$2,$3,$4,$5,$6,'held')`, [id,binding.jobId,costCenter,binding.budgetDate,binding.bindingId,maxNanoUsd.toString()]);
  return id;
}

export async function settleDailyCost(tx: PoolClient, reservationId: string, actualNanoUsd: bigint): Promise<void> {
  if (!/^[0-9a-f-]{36}$/i.test(reservationId) || actualNanoUsd < 0n) throw new Error('invalid_daily_settlement');
  const result = await tx.query(`UPDATE daily_cost_reservations SET state='settled',settled_nano_usd=$2,settled_at=now()
    WHERE id=$1 AND state IN ('held','unknown') AND $2 <= max_nano_usd`, [reservationId,actualNanoUsd.toString()]);
  if (result.rowCount !== 1) throw new Error('daily_reservation_not_settleable');
}
