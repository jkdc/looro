/* Pure helpers for fencing daily-practice admin responses to the selected view. */
'use strict';

export function dailyRequestIsCurrent(selected, request) {
  return Boolean(selected && request && selected.language === request.language &&
    selected.editorialDate === request.editorialDate &&
    (request.generationRunId === undefined || selected.generationRunId === request.generationRunId));
}

export function dailyRunStatus(run) {
  if (!run || !['queued', 'running', 'completed', 'failed', 'unknown'].includes(run.state)) return 'unknown';
  if (run.state === 'failed' && typeof run.errorCode === 'string' && run.errorCode.length) return `failed: ${run.errorCode}`;
  return run.state;
}

export function dailyProviderUsageText(operations) {
  if (!Array.isArray(operations)) return '—';
  const items = operations.flatMap(operation => {
    const usage = operation?.usage;
    if (!['generate', 'judge', 'tts', 'audio_check', 'review'].includes(operation?.phase) ||
      !['reserved', 'sending', 'succeeded', 'failed', 'unknown'].includes(operation?.state) ||
      !usage || Object.keys(usage).some(key => !['state', 'tokensIn', 'tokensOut'].includes(key)) ||
      !['complete', 'partial', 'unknown'].includes(usage.state) ||
      ![usage.tokensIn, usage.tokensOut].every(value => value === null || (Number.isSafeInteger(value) && value >= 0)) ||
      (usage.state === 'complete' && (usage.tokensIn === null || usage.tokensOut === null)) ||
      (usage.state === 'partial' && usage.tokensIn === null && usage.tokensOut === null) ||
      (usage.state === 'unknown' && (usage.tokensIn !== null || usage.tokensOut !== null))) return [];
    const count = value => value === null ? '—' : String(value);
    return [`${operation.phase}: ${usage.state} (in ${count(usage.tokensIn)} / out ${count(usage.tokensOut)})`];
  });
  return items.length ? items.join('; ') : '—';
}

export function dailyPreviewText(detail) {
  if (!detail || !detail.preview || typeof detail.preview !== 'object') return 'Vista previa no disponible.';
  return JSON.stringify(detail.preview, null, 2);
}

export function dailyPackageIsCurrent(selectedPackageId, requestPackageId) {
  return typeof selectedPackageId === 'string' && selectedPackageId === requestPackageId;
}

const DAILY_CONFIG_FIELDS = Object.freeze([
  ['readEnabled', 'read_enabled'],
  ['newAttemptsEnabled', 'new_attempts_enabled'],
  ['generationEnabled', 'generation_enabled'],
  ['autoPublishEnabled', 'auto_publish_enabled'],
  ['enabledLanguages', 'enabled_languages'],
  ['contentBudgetNanoUsd', 'content_budget_nano_usd'],
  ['reviewBudgetNanoUsd', 'review_budget_nano_usd'],
  ['retentionDays', 'retention_days']
]);

export function dailyConfigIsDirty(saved, draft) {
  return Boolean(saved && draft && DAILY_CONFIG_FIELDS.some(([draftKey, savedKey]) => {
    const left = draft[draftKey], right = saved[savedKey];
    return Array.isArray(left) && Array.isArray(right)
      ? left.length !== right.length || left.some((value, index) => value !== right[index])
      : String(left) !== String(right);
  }));
}

export function dailyConfigRequest(saved, draft, reason) {
  if (!saved || !Number.isSafeInteger(Number(saved.revision)) || Number(saved.revision) < 0)
    throw new TypeError('config');
  if (!dailyConfigIsDirty(saved, draft)) throw new TypeError('config_not_dirty');
  if (typeof reason !== 'string' || reason.trim().length < 10 || reason.length > 500)
    throw new TypeError('reason');
  if (!['readEnabled', 'newAttemptsEnabled', 'generationEnabled', 'autoPublishEnabled']
    .every(key => typeof draft[key] === 'boolean') ||
    !Array.isArray(draft.enabledLanguages) || draft.enabledLanguages.length !== 1 || draft.enabledLanguages[0] !== 'en' ||
    !/^(0|[1-9]\d{0,18})$/.test(draft.contentBudgetNanoUsd) ||
    !/^(0|[1-9]\d{0,18})$/.test(draft.reviewBudgetNanoUsd) ||
    BigInt(draft.contentBudgetNanoUsd) > 9_223_372_036_854_775_807n ||
    BigInt(draft.reviewBudgetNanoUsd) > 9_223_372_036_854_775_807n ||
    !Number.isSafeInteger(draft.retentionDays) || draft.retentionDays < 30 || draft.retentionDays > 3650)
    throw new TypeError('config');
  const expectedRevision = Number(saved.revision);
  return {
    expectedRevision,
    reason: reason.trim(),
    config: { schemaVersion: 1, expectedRevision, ...draft, enabledLanguages: [...draft.enabledLanguages] }
  };
}

export function dailyPendingRequest(pending, operation, body, createKey) {
  const serialized = typeof body === 'string' ? body : JSON.stringify(body);
  if (pending && pending.operation === operation && pending.body === serialized && typeof pending.key === 'string') return pending;
  return { operation, body: serialized, key: createKey() };
}

