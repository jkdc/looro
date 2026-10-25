export interface GenerationUsage {
  state: 'complete' | 'partial' | 'unknown';
  /** Known subtotal, not an estimate of unreported provider work. */
  tokensIn: number | null;
  tokensOut: number | null;
}

/** Independent direction totals preserve partial replies and legitimate zero. */
export class GenerationUsageTracker {
  private tokensIn: number | null = null;
  private tokensOut: number | null = null;
  private complete = true;
  private calls = 0;
  private inFlight = false;

  begin(): GenerationUsage {
    this.calls += 1;
    this.inFlight = true;
    return this.snapshot();
  }

  report(value?: { tokensIn?: number; tokensOut?: number }): GenerationUsage {
    this.inFlight = false;
    for (const key of ['tokensIn', 'tokensOut'] as const) {
      const reported = value?.[key];
      if (typeof reported !== 'number' || !Number.isSafeInteger(reported) || reported < 0 ||
          !Number.isSafeInteger((this[key] ?? 0) + reported)) {
        this.complete = false;
      } else this[key] = (this[key] ?? 0) + reported;
    }
    return this.snapshot();
  }

  snapshot(): GenerationUsage {
    return { state: this.complete && this.calls > 0 && !this.inFlight ? 'complete'
      : this.tokensIn !== null || this.tokensOut !== null ? 'partial' : 'unknown',
    tokensIn: this.tokensIn, tokensOut: this.tokensOut };
  }
}
