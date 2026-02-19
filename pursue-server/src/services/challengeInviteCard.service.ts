import { getOrCreateReferralToken } from './referral.service.js';

const SHARE_BASE_URL = process.env.PURSUE_SHARE_BASE_URL ?? 'https://getpursue.app';
const DEFAULT_CTA_TEXT = 'Are you in?';
const DEFAULT_GRADIENT: [string, string] = ['#E53935', '#C62828'];

export interface ChallengeInviteCardBaseData extends Record<string, unknown> {
  card_type: 'challenge_invite';
  title: string;
  subtitle: string;
  icon_emoji: string;
  cta_text: string;
  background_gradient: [string, string];
  invite_url: string;
}

export interface ChallengeInviteCardData extends ChallengeInviteCardBaseData {
  referral_token: string;
  share_url: string;
  qr_url: string;
  generated_at: string;
}

interface BuildChallengeInviteCardBaseInput {
  challengeName: string;
  startDate: string;
  endDate: string;
  iconEmoji: string | null;
  inviteCode: string;
}

function formatDateRange(startDate: string, endDate: string): string {
  const start = new Date(`${startDate}T00:00:00.000Z`);
  const end = new Date(`${endDate}T00:00:00.000Z`);
  const startFmt = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(start);
  const endFmt = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric' }).format(end);
  return `${startFmt} - ${endFmt}`;
}

function buildChallengeLink(inviteCode: string): string {
  return `${SHARE_BASE_URL}/challenge/${inviteCode}`;
}

export function buildChallengeInviteCardBase(
  input: BuildChallengeInviteCardBaseInput
): ChallengeInviteCardBaseData {
  return {
    card_type: 'challenge_invite',
    title: input.challengeName,
    subtitle: formatDateRange(input.startDate, input.endDate),
    icon_emoji: input.iconEmoji ?? '\u{1F3AF}',
    cta_text: DEFAULT_CTA_TEXT,
    background_gradient: DEFAULT_GRADIENT,
    invite_url: buildChallengeLink(input.inviteCode),
  };
}

function buildAttributedUrl(
  inviteCode: string,
  token: string,
  source: 'share' | 'qr',
  campaign: string
): string {
  const base = buildChallengeLink(inviteCode);
  return `${base}?utm_source=${encodeURIComponent(source)}&utm_medium=challenge_card&utm_campaign=${encodeURIComponent(campaign)}&ref=${encodeURIComponent(token)}`;
}

export async function attachInviteCardAttribution(
  base: ChallengeInviteCardBaseData,
  inviteCode: string,
  requesterUserId: string,
  campaign = 'challenge_invite'
): Promise<ChallengeInviteCardData> {
  const referralToken = await getOrCreateReferralToken(requesterUserId);
  return {
    ...base,
    referral_token: referralToken,
    share_url: buildAttributedUrl(inviteCode, referralToken, 'share', campaign),
    qr_url: buildAttributedUrl(inviteCode, referralToken, 'qr', campaign),
    generated_at: new Date().toISOString(),
  };
}
