import fs from 'fs';
import path from 'path';

interface PolicyVersions {
  termsVersion: string;
  privacyVersion: string;
}

let cached: PolicyVersions | null = null;

export function getPolicyVersions(): PolicyVersions {
  if (cached) return cached;

  const configPath = path.resolve(process.cwd(), '../pursue-web/public/config.json');
  const raw = fs.readFileSync(configPath, 'utf-8');
  const config = JSON.parse(raw);

  cached = {
    termsVersion: config.min_required_terms_version,
    privacyVersion: config.min_required_privacy_version,
  };

  return cached;
}
