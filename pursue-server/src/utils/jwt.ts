import jwt from 'jsonwebtoken';
import crypto from 'crypto';

const ACCESS_TOKEN_EXPIRY = '1h';
const REFRESH_TOKEN_EXPIRY = '30d';

interface AccessTokenPayload {
  user_id: string;
  email: string;
}

interface RefreshTokenPayload {
  user_id: string;
  token_id: string;
}

export function generateAccessToken(userId: string, email: string): string {
  const payload: AccessTokenPayload = {
    user_id: userId,
    email,
  };

  return jwt.sign(payload, process.env.JWT_SECRET!, {
    expiresIn: ACCESS_TOKEN_EXPIRY,
  });
}

export function generateRefreshToken(userId: string, tokenId: string): string {
  const payload: RefreshTokenPayload = {
    user_id: userId,
    token_id: tokenId,
  };

  return jwt.sign(payload, process.env.JWT_REFRESH_SECRET!, {
    expiresIn: REFRESH_TOKEN_EXPIRY,
  });
}

export function verifyAccessToken(token: string): AccessTokenPayload {
  return jwt.verify(token, process.env.JWT_SECRET!) as AccessTokenPayload;
}

export function verifyRefreshToken(token: string): RefreshTokenPayload {
  return jwt.verify(token, process.env.JWT_REFRESH_SECRET!) as RefreshTokenPayload;
}

export function hashToken(token: string): string {
  return crypto.createHash('sha256').update(token).digest('hex');
}

export function generateRandomToken(): string {
  return crypto.randomBytes(32).toString('hex');
}

export function getRefreshTokenExpiryDate(): Date {
  const now = new Date();
  now.setDate(now.getDate() + 30);
  return now;
}

export function getPasswordResetExpiryDate(): Date {
  const now = new Date();
  now.setHours(now.getHours() + 1);
  return now;
}
