import { isDeepStrictEqual } from 'node:util';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { recordAdminAction } from './admins-admin.js';
import { identifySnapshot, type ExamSnapshot } from './level-test-snapshot.js';
import { examModelFor } from './level-tests-llm.js';
import { validateGeneratedPool, type GeneratedPool } from './level-tests-validate.js';

type Generation = { id: string; language_id: string; level: string; week_index: number;
  status: string; pool: GeneratedPool; variants: ExamSnapshot[]; judge_mode: string;
  review: { id: string; verdict: string }[] };

/** Approval belongs to the generation row, not one of its variants. Validation,
    status transition and audit share a locked transaction; replay does not re-audit. */
export async function approveGeneratedWeek(db: Database, id: string, adminID: string, reason: string) {
  if (reason.trim().length < 3 || reason.length > 500 || /[\x00-\x1f]/.test(reason))
    throw new ServiceError('admin_reason_required');
  return transaction(db, async sql => {
    const row = (await sql.query(`SELECT id, language_id, level, week_index, status, pool, variants, judge_mode, review
      FROM level_test_weeks WHERE id=$1 FOR UPDATE`, [id])).rows[0] as Generation | undefined;
    if (!row) throw new ServiceError('level_test_not_found', 404);
    // A running regeneration holds this advisory lock; approving mid-regeneration
    // would let force persistWeek retire the freshly approved exam.
    const gate = await sql.query('SELECT pg_try_advisory_xact_lock(hashtextextended($1, 0)) AS locked',
      [`level-test-generation:${row.language_id}:${row.level}:${row.week_index}`]);
    if (gate.rows[0]?.locked !== true) throw new ServiceError('level_test_regeneration_in_progress', 409);
    if (row.status === 'approved') return { generationId: id, approved: true, alreadyApproved: true };
    if (row.status !== 'draft' || !canPublish(row)) throw new ServiceError('level_test_not_approvable', 409);
    await sql.query(`UPDATE level_test_weeks SET status='approved', approved_at=now(), approved_by=$2
      WHERE id=$1 AND status='draft'`, [id, adminID]);
    await recordAdminAction(sql, adminID, 'level_test.approve', id, reason, { variants: row.variants.length });
    return { generationId: id, approved: true, alreadyApproved: false };
  });
}
function canPublish(row: Generation): boolean {
  try {
    const model = examModelFor(row.level);
    if (!model || !row.pool || !Array.isArray(row.variants) || !row.variants.length) return false;
    const checked = validateGeneratedPool({ recognition: row.pool.recognition, writing: row.pool.writing,
      reading: row.pool.readingPassages, listening: row.pool.listeningPassages }, model.examShape, row.language_id);
    if (!checked.ok) return false;
    const pool = checked.pool;
    const expectedPassages = new Map<string, GeneratedPool['readingPassages'][number]>([
      ...pool.readingPassages.map((p, i) => [`llm-reading-${i + 1}`, p] as const),
      ...pool.listeningPassages.map((p, i) => [`llm-listening-${i + 1}`, p] as const),
    ]);
    const expectedItems = new Map([...pool.recognition, ...pool.writing,
      ...pool.readingPassages.flatMap((p, i) => p.items.map(item => ({ ...item, passageId: `llm-reading-${i + 1}` }))),
      ...pool.listeningPassages.flatMap((p, i) => p.items.map(item => ({ ...item, passageId: `llm-listening-${i + 1}`, speech: item.speech ?? p.text })))]
      .map(item => [item.id, item]));
    if (row.judge_mode === 'strict') {
      if (!Array.isArray(row.review)) return false;
      for (const id of expectedItems.keys()) {
        const verdicts = row.review.filter(verdict => verdict.id === id);
        if (verdicts.length !== 1 || !['ok', 'flagged'].includes(verdicts[0]!.verdict)) return false;
      }
    }
    const shape = { recognition: 12, writing: 3, readingPassages: 2, readingPerPassage: 3,
      listeningPassages: 2, listeningPerPassage: 2, ...model.examShape };
    const variants = new Set<number>();
    for (const exam of row.variants) {
      if (!Number.isSafeInteger(exam.variant) || !exam.variant || exam.variant < 1 || variants.has(exam.variant) || exam.variant > 5 ||
          exam.schemaVersion !== 1 || exam.source !== 'llm' || exam.gradingVersion !== 1 ||
          exam.contentRevision !== exam.version || !Number.isSafeInteger(exam.version) || exam.version < 1 ||
          exam.languageID !== row.language_id || exam.level !== row.level || exam.weekId !== row.week_index ||
          exam.generationId !== row.id || exam.examId !== `${row.id}:v${exam.variant}`) return false;
      variants.add(exam.variant);
      const identity = identifySnapshot(exam, 'llm', row.id);
      if (exam.contentHash !== identity.contentHash || exam.contentId !== identity.contentId) return false;
      const ids = new Set<string>();
      for (const item of exam.items) {
        const expected = expectedItems.get(item.id);
        if (!expected || ids.has(item.id)) return false;
        ids.add(item.id);
        for (const key of ['kind', 'section', 'prompt', 'options', 'answers', 'explain', 'unitID', 'passageId', 'speech'] as const)
          if (!isDeepStrictEqual(item[key], expected[key])) return false;
      }
      for (const section of ['recognition', 'writing'] as const)
        if (exam.items.filter(item => item.section === section).length !== shape[section]) return false;
      const passages = new Map(exam.passages.map(p => [p.id, p]));
      if (passages.size !== exam.passages.length) return false;
      const used = new Set<string>();
      for (const section of ['reading', 'listening'] as const) {
        const selected = section === 'reading' ? exam.readingPassageIds : exam.listeningPassageIds;
        if (selected.length !== shape[`${section}Passages`]) return false;
        for (const id of selected) {
          const passage = passages.get(id);
          const expected = expectedPassages.get(id);
          if (!passage || !expected || used.has(id) || passage.text !== expected.text || passage.title !== expected.title) return false;
          used.add(id);
          if (exam.items.filter(item => item.section === section && item.passageId === id).length !== shape[`${section}PerPassage`]) return false;
        }
        if (exam.items.filter(item => item.section === section).length !== shape[`${section}Passages`]! * shape[`${section}PerPassage`]!) return false;
      }
      if (used.size !== passages.size) return false;
      if (exam.items.length !== shape.recognition + shape.writing + shape.readingPassages * shape.readingPerPassage + shape.listeningPassages * shape.listeningPerPassage) return false;
    }
    return true;
  } catch { return false; }
}

/** Withdraws an approved week from the serving set. Content and judge verdicts
    stay untouched for audit; the operator motive is stored on the row so the
    review page can explain the retirement without reading the audit ledger. */
export async function retireGeneratedWeek(db: Database, id: string, adminID: string, reason: string) {
  if (reason.trim().length < 3 || reason.length > 500 || /[\x00-\x1f]/.test(reason))
    throw new ServiceError('admin_reason_required');
  return transaction(db, async sql => {
    const row = (await sql.query(`SELECT id, status, approved_by, origin FROM level_test_weeks
      WHERE id=$1 FOR UPDATE`, [id])).rows[0] as
      { id: string; status: string; approved_by: string | null; origin: string } | undefined;
    if (!row) throw new ServiceError('level_test_not_found', 404);
    if (row.status === 'retired') return { generationId: id, retired: true, alreadyRetired: true };
    if (row.status !== 'approved') throw new ServiceError('level_test_not_retirable', 409);
    await sql.query(`UPDATE level_test_weeks
      SET status='retired', retired_at=now(), retired_by=$2, retired_reason=$3
      WHERE id=$1 AND status='approved'`, [id, adminID, reason]);
    await recordAdminAction(sql, adminID, 'level_test.retire', id, reason,
      { approvedBy: row.approved_by, origin: row.origin });
    return { generationId: id, retired: true, alreadyRetired: false };
  });
}

export async function rejectGeneratedWeek(db: Database, id: string, adminID: string, reason: string) {
  if (reason.trim().length < 3 || reason.length > 500 || /[\x00-\x1f]/.test(reason))
    throw new ServiceError('admin_reason_required');
  return transaction(db, async sql => {
    const row = (await sql.query('SELECT id, status FROM level_test_weeks WHERE id=$1 FOR UPDATE', [id]))
      .rows[0] as { id: string; status: string } | undefined;
    if (!row) throw new ServiceError('level_test_not_found', 404);
    if (row.status === 'rejected') return { generationId: id, rejected: true, alreadyRejected: true };
    if (row.status !== 'draft') throw new ServiceError('level_test_not_rejectable', 409);
    await sql.query("UPDATE level_test_weeks SET status='rejected' WHERE id=$1 AND status='draft'", [id]);
    await recordAdminAction(sql, adminID, 'level_test.reject', id, reason, {});
    return { generationId: id, rejected: true, alreadyRejected: false };
  });
}
