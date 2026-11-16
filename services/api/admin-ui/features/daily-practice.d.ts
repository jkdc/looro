export function dailyRequestIsCurrent(selected: {
  language: string; editorialDate: string; generationRunId?: string | null
}, request: { language: string; editorialDate: string; generationRunId?: string | null }): boolean;
export function dailyRunStatus(run: { state?: unknown; errorCode?: unknown } | null | undefined): string;
export function dailyProviderUsageText(operations: unknown): string;
export function dailyPreviewText(detail: (Record<string, unknown> & { preview?: unknown }) | null | undefined): string;
export function dailyPackageIsCurrent(selectedPackageId: string | null, requestPackageId: string): boolean;
export interface DailyConfigDraft {
  readEnabled: boolean;
  newAttemptsEnabled: boolean;
  generationEnabled: boolean;
  autoPublishEnabled: boolean;
  enabledLanguages: string[];
  contentBudgetNanoUsd: string;
  reviewBudgetNanoUsd: string;
  retentionDays: number;
}
export function dailyConfigIsDirty(saved: Record<string, unknown> | null | undefined,
  draft: DailyConfigDraft | null | undefined): boolean;
export function dailyConfigRequest(saved: Record<string, unknown> | null | undefined,
  draft: DailyConfigDraft, reason: string): {
    expectedRevision: number; reason: string;
    config: DailyConfigDraft & { schemaVersion: 1; expectedRevision: number }
  };
export function dailyPendingRequest(pending: { operation: string; body: string; key: string } | null | undefined,
  operation: string, body: string | Record<string, unknown>, createKey: () => string):
  { operation: string; body: string; key: string };
