/**
 * Extract the base language code from a BCP 47 language tag.
 * Examples:
 *   "es-ES" → "es"
 *   "pt-BR" → "pt"
 *   "en" → "en"
 */
export function getBaseLanguage(languageTag: string): string {
  if (!languageTag) return 'en';
  const baseLang = languageTag.split('-')[0].toLowerCase();
  return baseLang || 'en';
}

/**
 * Given a language preference, return both the exact tag and base language for fallback queries.
 * This ensures that regional variants (e.g., es-ES, pt-BR) fall back to the base language (es, pt)
 * if no exact match is found in the database.
 *
 * Examples:
 *   "es-ES" → { exact: "es-ES", base: "es" }
 *   "es" → { exact: "es", base: "es" }
 *   "en" → { exact: "en", base: "en" }
 */
export function getLanguageVariants(languageTag?: string): { exact: string; base: string } {
  if (!languageTag) {
    return { exact: 'en', base: 'en' };
  }
  const normalized = languageTag.toLowerCase().trim();
  const baseLanguage = getBaseLanguage(normalized);
  return {
    exact: normalized,
    base: baseLanguage,
  };
}
