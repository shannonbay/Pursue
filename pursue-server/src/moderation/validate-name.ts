import { isReservedName, containsReservedBrandTerm } from './reserved-names.js';
import { containsProfanity } from './profanity.js';
import { ApplicationError } from '../middleware/errorHandler.js';

export type NameValidationResult =
  | { valid: true }
  | { valid: false; reason: 'reserved' | 'profanity' };

/**
 * Validate a display name.
 * Rejects exact reserved name matches and profanity.
 */
export function validateDisplayName(name: string): NameValidationResult {
  if (isReservedName(name)) {
    return { valid: false, reason: 'reserved' };
  }
  if (containsProfanity(name)) {
    return { valid: false, reason: 'profanity' };
  }
  return { valid: true };
}

/**
 * Validate a group name.
 * Rejects names containing reserved brand terms or profanity.
 */
export function validateGroupName(name: string): NameValidationResult {
  if (containsReservedBrandTerm(name)) {
    return { valid: false, reason: 'reserved' };
  }
  if (containsProfanity(name)) {
    return { valid: false, reason: 'profanity' };
  }
  return { valid: true };
}

/**
 * Validate a goal title.
 * Rejects profanity only.
 */
export function validateGoalName(name: string): NameValidationResult {
  if (containsProfanity(name)) {
    return { valid: false, reason: 'profanity' };
  }
  return { valid: true };
}

/**
 * Throws ApplicationError if the name validation result is invalid.
 */
export function assertValidName(result: NameValidationResult): void {
  if (!result.valid) {
    throw new ApplicationError(
      "This name isn't available. Please choose something different.",
      400,
      'NAME_NOT_AVAILABLE'
    );
  }
}
