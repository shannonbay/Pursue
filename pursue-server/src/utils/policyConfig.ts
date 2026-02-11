import fs from 'fs';
import path from 'path';

interface PolicyVersions {
  termsVersion: string;
  privacyVersion: string;
}

let cached: PolicyVersions | null = null;

export async function getPolicyVersions(): Promise<PolicyVersions> {
  if (cached) return cached;

  // Primary: fetch from marketing site (works everywhere including Cloud Run)
  try {
    const response = await fetch('https://getpursue.app/config.json');
    if (response.ok) {
      const config = (await response.json()) as {
        min_required_terms_version: string;
        min_required_privacy_version: string;
      };
      cached = {
        termsVersion: config.min_required_terms_version,
        privacyVersion: config.min_required_privacy_version,
      };
      return cached;
    }
  } catch {
    // Network unavailable — fall through to local file
  }

  // Fallback: read from local file (local development without internet)
  try {
    const configPath = path.resolve(process.cwd(), '../pursue-web/public/config.json');
    const raw = fs.readFileSync(configPath, 'utf-8');
    const config = JSON.parse(raw);
    cached = {
      termsVersion: config.min_required_terms_version,
      privacyVersion: config.min_required_privacy_version,
    };
    return cached;
  } catch {
    throw new Error(
      'Policy versions not available from https://getpursue.app/config.json or local file'
    );
  }
}
