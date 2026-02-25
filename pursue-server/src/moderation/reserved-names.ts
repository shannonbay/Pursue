/**
 * Reserved names that cannot be used as display names or group names.
 */

// Exact-match reserved display names (case-insensitive)
export const RESERVED_NAMES = new Set([
  // Pursue brand
  'pursue',
  'getpursue',
  'pursue official',
  'pursue team',
  'pursue support',
  'pursue admin',
  'pursue moderator',
  'pursue mod',
  'pursue staff',
  'pursueapp',
  'pursue_app',
  'pursue.app',

  // Platform roles
  'admin',
  'administrator',
  'moderator',
  'mod',
  'staff',
  'support',
  'help',
  'helpdesk',
  'system',
  'root',
  'superuser',
  'superadmin',
  'owner',
  'manager',
  'official',
  'verified',
  'bot',
  'automod',

  // Technical / reserved terms
  'null',
  'undefined',
  'anonymous',
  'user',
  'guest',
  'test',
  'demo',
  'default',
  'example',
  'placeholder',
  'everyone',
  'nobody',
  'somebody',

  // Trust exploitation
  'customer service',
  'customer support',
  'technical support',
  'tech support',
  'safety team',
  'trust and safety',
  'security team',

  // Common impersonation targets
  'anthropic',
  'google',
  'apple',
  'microsoft',
  'facebook',
  'meta',
  'instagram',
  'twitter',
  'x',
  'tiktok',
  'snapchat',
  'openai',
  'chatgpt',
  'youtube',
  'amazon',
  'netflix',
]);

// Brand terms used for substring containment check in group names
export const RESERVED_BRAND_TERMS = [
  'pursue',
  'anthropic',
  'google',
  'apple',
  'facebook',
  'meta',
  'instagram',
  'twitter',
  'tiktok',
  'snapchat',
  'openai',
];

/**
 * Returns true if the name exactly matches a reserved name (case-insensitive).
 */
export function isReservedName(name: string): boolean {
  return RESERVED_NAMES.has(name.toLowerCase().trim());
}

/**
 * Returns true if the name contains any reserved brand term as a substring (case-insensitive).
 * Used for group names where containment is sufficient cause for rejection.
 */
export function containsReservedBrandTerm(name: string): boolean {
  const lower = name.toLowerCase();
  return RESERVED_BRAND_TERMS.some((term) => lower.includes(term));
}
