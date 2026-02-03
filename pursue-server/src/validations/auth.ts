import { z } from 'zod';

export const RegisterSchema = z.object({
  email: z.string().email('Invalid email format').max(255),
  password: z.string().min(8, 'Password must be at least 8 characters').max(100),
  display_name: z.string().min(1, 'Display name is required').max(100),
}).strict();

export const LoginSchema = z.object({
  email: z.string().email('Invalid email format'),
  password: z.string().min(1, 'Password is required'),
}).strict();

export const GoogleAuthSchema = z.object({
  id_token: z.string().min(1, 'ID token is required'),
}).strict();

export const RefreshTokenSchema = z.object({
  refresh_token: z.string().min(1, 'Refresh token is required'),
}).strict();

export const ForgotPasswordSchema = z.object({
  email: z.string().email('Invalid email format'),
}).strict();

export const ResetPasswordSchema = z.object({
  token: z.string().min(1, 'Token is required'),
  new_password: z.string().min(8, 'Password must be at least 8 characters').max(100),
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
