import { z } from 'zod';

// Password schema with complexity requirements
const passwordSchema = z.string()
  .min(8, 'Password must be at least 8 characters')
  .max(100)
  .refine(
    (password) => /[a-z]/.test(password),
    'Password must contain at least one lowercase letter'
  )
  .refine(
    (password) => /[A-Z]/.test(password),
    'Password must contain at least one uppercase letter'
  )
  .refine(
    (password) => /[0-9]/.test(password),
    'Password must contain at least one number'
  )
  .refine(
    (password) => /[^a-zA-Z0-9]/.test(password),
    'Password must contain at least one special character'
  );

export const RegisterSchema = z.object({
  email: z.string().email('Invalid email format').max(255),
  password: passwordSchema,
  display_name: z.string().min(1, 'Display name is required').max(100),
  date_of_birth: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Invalid date format (expected YYYY-MM-DD)'),
  consent_agreed: z.literal(true, {
    error: 'You must agree to the Terms of Service and Privacy Policy',
  }),
  consent_terms_version: z.string().max(30).optional(),
  consent_privacy_version: z.string().max(30).optional(),
}).strict();

export const LoginSchema = z.object({
  email: z.string().email('Invalid email format'),
  password: z.string().min(1, 'Password is required'),
}).strict();

export const GoogleAuthSchema = z.object({
  id_token: z.string().min(1, 'ID token is required'),
  consent_agreed: z.boolean().optional(),
  consent_terms_version: z.string().max(30).optional(),
  consent_privacy_version: z.string().max(30).optional(),
}).strict();

export const RefreshTokenSchema = z.object({
  refresh_token: z.string().min(1, 'Refresh token is required'),
}).strict();

export const ForgotPasswordSchema = z.object({
  email: z.string().email('Invalid email format'),
}).strict();

export const ResetPasswordSchema = z.object({
  token: z.string().min(1, 'Token is required'),
  new_password: passwordSchema,
}).strict();

export const LinkGoogleSchema = z.object({
  id_token: z.string().min(1, 'ID token is required'),
}).strict();

export type RegisterInput = z.infer<typeof RegisterSchema>;
export type LoginInput = z.infer<typeof LoginSchema>;
export type GoogleAuthInput = z.infer<typeof GoogleAuthSchema>;
export type RefreshTokenInput = z.infer<typeof RefreshTokenSchema>;
export type ForgotPasswordInput = z.infer<typeof ForgotPasswordSchema>;
export type ResetPasswordInput = z.infer<typeof ResetPasswordSchema>;
export type LinkGoogleInput = z.infer<typeof LinkGoogleSchema>;
