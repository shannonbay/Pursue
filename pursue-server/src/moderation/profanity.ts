import { checkProfanity } from 'glin-profanity';

/**
 * Returns true if the text contains profanity.
 */
export function containsProfanity(text: string): boolean {
  return checkProfanity(text).containsProfanity;
}
