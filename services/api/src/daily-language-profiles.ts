export interface DailyLanguageProfile {
  language: string;
  profileVersion: string;
  enabled: boolean;
  challengeReference: 'B1-B2';
  speechLocale: string;
  supportLocales: readonly ('en' | 'es')[];
  maxPackageBytes: number;
  maxArtifactBytes: number;
}

export const dailyLanguageProfiles: Readonly<Record<string, DailyLanguageProfile>> = Object.freeze({
  en: Object.freeze({ language: 'en', profileVersion: 'en-v1', enabled: true,
    challengeReference: 'B1-B2', speechLocale: 'en-US', supportLocales: Object.freeze(['en', 'es'] as const),
    maxPackageBytes: 65_536, maxArtifactBytes: 98_304 }),
  es: Object.freeze({ language: 'es', profileVersion: 'es-v1', enabled: false,
    challengeReference: 'B1-B2', speechLocale: 'es-ES', supportLocales: Object.freeze(['en', 'es'] as const),
    maxPackageBytes: 65_536, maxArtifactBytes: 98_304 }),
});

export function resolveDailyLanguageProfile(language: string): DailyLanguageProfile | null {
  return dailyLanguageProfiles[language] ?? null;
}
