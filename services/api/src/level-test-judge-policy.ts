/** Shared English judge evidence contract for generation and publication. */
export const ENGLISH_JUDGE_REASON_CODES = ['answer_key', 'ambiguity', 'unsupported', 'level_mismatch', 'format', 'explanation', 'language'] as const;

export function hasEnglishJudgeEvidence(value: { reason?: unknown; reasonCode?: unknown; languageStatus?: unknown; assessedSkill?: unknown; skillEvidence?: unknown }, expectedSkill?: string): boolean {
  return (!expectedSkill || value.assessedSkill===expectedSkill && typeof value.skillEvidence==='string' &&
    value.skillEvidence.trim().length>=8 && value.skillEvidence.length<=240) && typeof value.reason === 'string' && value.reason.trim().length >= 8 && value.reason.length <= 240 &&
    ENGLISH_JUDGE_REASON_CODES.includes(value.reasonCode as typeof ENGLISH_JUDGE_REASON_CODES[number]) &&
    value.languageStatus === 'pass';
}
