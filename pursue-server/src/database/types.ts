import type { ColumnType, Generated, Insertable, Selectable, Updateable } from 'kysely';

// Users table
export interface UsersTable {
  id: Generated<string>;
  email: string;
  display_name: string;
  avatar_data: Buffer | null;
  avatar_mime_type: string | null;
  password_hash: string | null;
  timezone: ColumnType<string, string | undefined, string>; // Cached timezone for smart reminders
  created_at: ColumnType<Date, string | undefined, never>;
  updated_at: ColumnType<Date, string | undefined, string | undefined>;
  deleted_at: ColumnType<Date | null, string | undefined, string | undefined>;
  current_subscription_tier: ColumnType<'free' | 'premium', string | undefined, string>;
  subscription_status: ColumnType<'active' | 'cancelled' | 'expired' | 'grace_period' | 'over_limit', string | undefined, string>;
  group_limit: ColumnType<number, number | undefined, number>;
  current_group_count: ColumnType<number, number | undefined, number>;
}

export type User = Selectable<UsersTable>;
export type NewUser = Insertable<UsersTable>;
export type UserUpdate = Updateable<UsersTable>;

// Auth providers table
export interface AuthProvidersTable {
  id: Generated<string>;
  user_id: string;
  provider: string;
  provider_user_id: string;
  provider_email: string | null;
  linked_at: ColumnType<Date, string | undefined, never>;
}

export type AuthProvider = Selectable<AuthProvidersTable>;
export type NewAuthProvider = Insertable<AuthProvidersTable>;

// Refresh tokens table
export interface RefreshTokensTable {
  id: Generated<string>;
  user_id: string;
  token_hash: string;
  expires_at: Date;
  created_at: ColumnType<Date, string | undefined, never>;
  revoked_at: Date | null;
}

export type RefreshToken = Selectable<RefreshTokensTable>;
export type NewRefreshToken = Insertable<RefreshTokensTable>;

// Password reset tokens table
export interface PasswordResetTokensTable {
  id: Generated<string>;
  user_id: string;
  token_hash: string;
  expires_at: Date;
  created_at: ColumnType<Date, string | undefined, never>;
  used_at: Date | null;
}

export type PasswordResetToken = Selectable<PasswordResetTokensTable>;
export type NewPasswordResetToken = Insertable<PasswordResetTokensTable>;

// Devices table
export interface DevicesTable {
  id: Generated<string>;
  user_id: string;
  fcm_token: string;
  device_name: string | null;
  platform: string | null;
  last_active: ColumnType<Date, string | undefined, string | undefined>;
  created_at: ColumnType<Date, string | undefined, never>;
}

export type Device = Selectable<DevicesTable>;
export type NewDevice = Insertable<DevicesTable>;

// Groups table
export interface GroupsTable {
  id: Generated<string>;
  name: string;
  description: string | null;
  icon_emoji: string | null;
  icon_color: string | null;
  icon_data: Buffer | null;
  icon_mime_type: string | null;
  creator_user_id: string;
  is_challenge: ColumnType<boolean, boolean | undefined, boolean>;
  challenge_start_date: string | null; // DATE as YYYY-MM-DD
  challenge_end_date: string | null; // DATE as YYYY-MM-DD
  challenge_template_id: string | null;
  challenge_status: ColumnType<'upcoming' | 'active' | 'completed' | 'cancelled' | null, string | null | undefined, string | null>;
  challenge_invite_card_data: ColumnType<Record<string, unknown> | null, Record<string, unknown> | null | undefined, Record<string, unknown> | null>;
  created_at: ColumnType<Date, string | undefined, never>;
  updated_at: ColumnType<Date, string | undefined, string | undefined>;
  deleted_at: ColumnType<Date | null, string | undefined, string | undefined>;
}

export type Group = Selectable<GroupsTable>;
export type NewGroup = Insertable<GroupsTable>;
export type GroupUpdate = Updateable<GroupsTable>;

// Challenge templates table
export interface ChallengeTemplatesTable {
  id: Generated<string>;
  slug: string;
  title: string;
  description: string;
  icon_emoji: string;
  duration_days: number;
  category: string;
  difficulty: ColumnType<'easy' | 'moderate' | 'hard', string | undefined, string>;
  is_featured: ColumnType<boolean, boolean | undefined, boolean>;
  sort_order: ColumnType<number, number | undefined, number>;
  created_at: ColumnType<Date, string | undefined, never>;
  updated_at: ColumnType<Date, string | undefined, string | undefined>;
}

export type ChallengeTemplate = Selectable<ChallengeTemplatesTable>;
export type NewChallengeTemplate = Insertable<ChallengeTemplatesTable>;
export type ChallengeTemplateUpdate = Updateable<ChallengeTemplatesTable>;

// Challenge template goals table
export interface ChallengeTemplateGoalsTable {
  id: Generated<string>;
  template_id: string;
  title: string;
  description: string | null;
  cadence: string;
  metric_type: string;
  target_value: number | null;
  unit: string | null;
  sort_order: ColumnType<number, number | undefined, number>;
}

export type ChallengeTemplateGoal = Selectable<ChallengeTemplateGoalsTable>;
export type NewChallengeTemplateGoal = Insertable<ChallengeTemplateGoalsTable>;

// Group memberships table
export interface GroupMembershipsTable {
  id: Generated<string>;
  group_id: string;
  user_id: string;
  role: string;
  status: ColumnType<'pending' | 'active' | 'declined', string | undefined, string>;
  joined_at: ColumnType<Date, string | undefined, never>;
  weekly_recap_enabled: ColumnType<boolean, boolean | undefined, boolean>;
}

export type GroupMembership = Selectable<GroupMembershipsTable>;
export type NewGroupMembership = Insertable<GroupMembershipsTable>;
export type GroupMembershipUpdate = Updateable<GroupMembershipsTable>;

// Invite codes table (one active code per group; revoked_at null = active)
export interface InviteCodesTable {
  id: Generated<string>;
  group_id: string;
  code: string;
  created_by_user_id: string | null;
  created_at: ColumnType<Date, string | undefined, never>;
  revoked_at: Date | null;
}

export type InviteCode = Selectable<InviteCodesTable>;
export type NewInviteCode = Insertable<InviteCodesTable>;

// Goals table
export interface GoalsTable {
  id: Generated<string>;
  group_id: string;
  title: string;
  description: string | null;
  cadence: string;
  metric_type: string;
  target_value: number | null;
  unit: string | null;
  created_by_user_id: string | null;
  created_at: ColumnType<Date, string | undefined, never>;
  deleted_at: Date | null;
  deleted_by_user_id: string | null;
}

export type Goal = Selectable<GoalsTable>;
export type NewGoal = Insertable<GoalsTable>;
export type GoalUpdate = Updateable<GoalsTable>;

// Progress entries table
export interface ProgressEntriesTable {
  id: Generated<string>;
  goal_id: string;
  user_id: string;
  value: number;
  note: string | null;
  logged_at: ColumnType<Date, string | undefined, never>;
  period_start: string; // DATE stored as string YYYY-MM-DD
  user_timezone: string | null;
  created_at: ColumnType<Date, string | undefined, never>;
}

export type ProgressEntry = Selectable<ProgressEntriesTable>;
export type NewProgressEntry = Insertable<ProgressEntriesTable>;

// Group activities table
export interface GroupActivitiesTable {
  id: Generated<string>;
  group_id: string;
  user_id: string | null;
  activity_type: string;
  metadata: Record<string, unknown> | null;
  created_at: ColumnType<Date, string | undefined, never>;
}

export type GroupActivity = Selectable<GroupActivitiesTable>;
export type NewGroupActivity = Insertable<GroupActivitiesTable>;

// Nudges table
export interface NudgesTable {
  id: Generated<string>;
  sender_user_id: string;
  recipient_user_id: string;
  group_id: string;
  goal_id: string | null;
  sent_at: ColumnType<Date, string | undefined, never>;
  sender_local_date: string; // DATE as YYYY-MM-DD
}

export type Nudge = Selectable<NudgesTable>;
export type NewNudge = Insertable<NudgesTable>;

// Activity reactions table
export interface ActivityReactionsTable {
  id: Generated<string>;
  activity_id: string;
  user_id: string;
  emoji: string;
  created_at: ColumnType<Date, string | undefined, never>;
}

export type ActivityReaction = Selectable<ActivityReactionsTable>;
export type NewActivityReaction = Insertable<ActivityReactionsTable>;

// User subscriptions table
export interface UserSubscriptionsTable {
  id: Generated<string>;
  user_id: string;
  tier: 'free' | 'premium';
  status: 'active' | 'cancelled' | 'expired' | 'grace_period';
  started_at: ColumnType<Date, string | undefined, never>;
  expires_at: Date | null;
  cancelled_at: Date | null;
  platform: 'google_play' | 'app_store' | null;
  platform_subscription_id: string | null;
  platform_purchase_token: string | null;
  auto_renew: boolean;
  created_at: ColumnType<Date, string | undefined, never>;
  updated_at: ColumnType<Date, string | undefined, string | undefined>;
}

export type UserSubscription = Selectable<UserSubscriptionsTable>;
export type NewUserSubscription = Insertable<UserSubscriptionsTable>;
export type UserSubscriptionUpdate = Updateable<UserSubscriptionsTable>;

// Subscription downgrade history table
export interface SubscriptionDowngradeHistoryTable {
  id: Generated<string>;
  user_id: string;
  downgrade_date: ColumnType<Date, string | undefined, never>;
  previous_tier: string;
  groups_before_downgrade: number;
  kept_group_id: string | null;
  removed_group_ids: string[];
  created_at: ColumnType<Date, string | undefined, never>;
}

export type SubscriptionDowngradeHistory = Selectable<SubscriptionDowngradeHistoryTable>;
export type NewSubscriptionDowngradeHistory = Insertable<SubscriptionDowngradeHistoryTable>;

// Subscription transactions table
export interface SubscriptionTransactionsTable {
  id: Generated<string>;
  subscription_id: string;
  transaction_type: 'purchase' | 'renewal' | 'cancellation' | 'refund';
  platform: 'google_play' | 'app_store';
  platform_transaction_id: string;
  amount_cents: number | null;
  currency: string | null;
  transaction_date: Date;
  raw_receipt: string | null;
  created_at: ColumnType<Date, string | undefined, never>;
}

export type SubscriptionTransaction = Selectable<SubscriptionTransactionsTable>;
export type NewSubscriptionTransaction = Insertable<SubscriptionTransactionsTable>;

// User consents table
export interface UserConsentsTable {
  id: Generated<string>;
  user_id: string | null;
  consent_type: string;
  agreed_at: ColumnType<Date, string | undefined, never>;
  ip_address: string | null;
  email_hash: string | null;
}

export type UserConsent = Selectable<UserConsentsTable>;
export type NewUserConsent = Insertable<UserConsentsTable>;

// Progress photos table (ephemeral photo attachments on progress entries)
export interface ProgressPhotosTable {
  id: Generated<string>;
  progress_entry_id: string;
  user_id: string;
  gcs_object_path: string;
  width_px: number;
  height_px: number;
  uploaded_at: ColumnType<Date, string | undefined, never>;
  expires_at: ColumnType<Date, string | undefined, never>;
  gcs_deleted_at: Date | null;
  created_at: ColumnType<Date, string | undefined, never>;
}

export type ProgressPhoto = Selectable<ProgressPhotosTable>;
export type NewProgressPhoto = Insertable<ProgressPhotosTable>;

// User notifications table (personal inbox)
export interface UserNotificationsTable {
  id: Generated<string>;
  user_id: string;
  type: string;
  actor_user_id: string | null;
  group_id: string | null;
  goal_id: string | null;
  progress_entry_id: string | null;
  metadata: Record<string, unknown> | null;
  shareable_card_data: ColumnType<Record<string, unknown> | null, Record<string, unknown> | null | undefined, Record<string, unknown> | null>;
  is_read: ColumnType<boolean, boolean | undefined, boolean>;
  created_at: ColumnType<Date, string | undefined, never>;
}

export type UserNotification = Selectable<UserNotificationsTable>;
export type NewUserNotification = Insertable<UserNotificationsTable>;
export type UserNotificationUpdate = Updateable<UserNotificationsTable>;

// Deferred challenge completion push queue
export interface ChallengeCompletionPushQueueTable {
  id: Generated<string>;
  group_id: string;
  user_id: string;
  send_at: Date;
  status: ColumnType<'pending' | 'sent' | 'failed', 'pending' | 'sent' | 'failed' | undefined, 'pending' | 'sent' | 'failed'>;
  attempt_count: ColumnType<number, number | undefined, number>;
  last_error: string | null;
  created_at: ColumnType<Date, string | undefined, never>;
  updated_at: ColumnType<Date, string | undefined, string | undefined>;
}

export type ChallengeCompletionPushQueue = Selectable<ChallengeCompletionPushQueueTable>;
export type NewChallengeCompletionPushQueue = Insertable<ChallengeCompletionPushQueueTable>;
export type ChallengeCompletionPushQueueUpdate = Updateable<ChallengeCompletionPushQueueTable>;

// User milestone grants table (deduplication for milestone notifications)
export interface UserMilestoneGrantsTable {
  id: Generated<string>;
  user_id: string;
  milestone_key: string;
  goal_id: string | null;
  granted_at: ColumnType<Date, string | undefined, string | undefined>;
}

export type UserMilestoneGrant = Selectable<UserMilestoneGrantsTable>;
export type NewUserMilestoneGrant = Insertable<UserMilestoneGrantsTable>;

// Referral tokens table (stable opaque token per user for share attribution)
export interface ReferralTokensTable {
  token: string;
  user_id: string;
  created_at: ColumnType<Date, string | undefined, never>;
}

export type ReferralToken = Selectable<ReferralTokensTable>;
export type NewReferralToken = Insertable<ReferralTokensTable>;

// Photo upload log table (permanent record for quota enforcement)
export interface PhotoUploadLogTable {
  id: Generated<string>;
  user_id: string;
  uploaded_at: ColumnType<Date, string | undefined, never>;
}

export type PhotoUploadLog = Selectable<PhotoUploadLogTable>;
export type NewPhotoUploadLog = Insertable<PhotoUploadLogTable>;

// Group heat table (momentum indicator per group)
export interface GroupHeatTable {
  group_id: string;
  heat_score: ColumnType<number, number | undefined, number>;
  heat_tier: ColumnType<number, number | undefined, number>;
  last_calculated_at: Date | null;
  streak_days: ColumnType<number, number | undefined, number>;
  peak_score: ColumnType<number, number | undefined, number>;
  peak_date: string | null; // DATE as YYYY-MM-DD
  created_at: ColumnType<Date, string | undefined, never>;
  updated_at: ColumnType<Date, string | undefined, string | undefined>;
}

export type GroupHeat = Selectable<GroupHeatTable>;
export type NewGroupHeat = Insertable<GroupHeatTable>;
export type GroupHeatUpdate = Updateable<GroupHeatTable>;

// Group daily GCR table (daily completion rate snapshots)
export interface GroupDailyGcrTable {
  id: Generated<string>;
  group_id: string;
  date: string; // DATE as YYYY-MM-DD
  total_possible: number;
  total_completed: number;
  gcr: number;
  member_count: number;
  goal_count: number;
  created_at: ColumnType<Date, string | undefined, never>;
}

export type GroupDailyGcr = Selectable<GroupDailyGcrTable>;
export type NewGroupDailyGcr = Insertable<GroupDailyGcrTable>;

// User logging patterns table (cached patterns for smart reminders)
export interface UserLoggingPatternsTable {
  user_id: string;
  goal_id: string;
  day_of_week: number; // -1 for general, 0-6 for day-specific
  typical_hour_start: number;
  typical_hour_end: number;
  confidence_score: number;
  sample_size: number;
  last_calculated_at: ColumnType<Date, string | undefined, string | undefined>;
}

export type UserLoggingPattern = Selectable<UserLoggingPatternsTable>;
export type NewUserLoggingPattern = Insertable<UserLoggingPatternsTable>;
export type UserLoggingPatternUpdate = Updateable<UserLoggingPatternsTable>;

// Reminder history table (track sent reminders and effectiveness)
export interface ReminderHistoryTable {
  id: Generated<string>;
  user_id: string;
  goal_id: string;
  reminder_tier: string; // 'gentle', 'supportive', 'last_chance'
  sent_at: ColumnType<Date, string | undefined, never>;
  sent_at_local_date: string; // DATE as YYYY-MM-DD
  was_effective: boolean | null;
  social_context: Record<string, unknown> | null;
  user_timezone: string;
}

export type ReminderHistory = Selectable<ReminderHistoryTable>;
export type NewReminderHistory = Insertable<ReminderHistoryTable>;

// User reminder preferences table (per-goal reminder settings)
export interface UserReminderPreferencesTable {
  user_id: string;
  goal_id: string;
  enabled: ColumnType<boolean, boolean | undefined, boolean>;
  mode: ColumnType<string, string | undefined, string>; // 'smart', 'fixed', 'disabled'
  fixed_hour: number | null;
  aggressiveness: ColumnType<string, string | undefined, string>; // 'gentle', 'balanced', 'persistent'
  quiet_hours_start: number | null;
  quiet_hours_end: number | null;
  last_modified_at: ColumnType<Date, string | undefined, string | undefined>;
}

export type UserReminderPreferences = Selectable<UserReminderPreferencesTable>;
export type NewUserReminderPreferences = Insertable<UserReminderPreferencesTable>;
export type UserReminderPreferencesUpdate = Updateable<UserReminderPreferencesTable>;

// Weekly recaps sent table (deduplication)
export interface WeeklyRecapsSentTable {
  group_id: string;
  week_end: string; // DATE as YYYY-MM-DD
  sent_at: ColumnType<Date, string | undefined, never>;
}

export type WeeklyRecapSent = Selectable<WeeklyRecapsSentTable>;
export type NewWeeklyRecapSent = Insertable<WeeklyRecapsSentTable>;

// Database interface combining all tables
export interface Database {
  users: UsersTable;
  auth_providers: AuthProvidersTable;
  refresh_tokens: RefreshTokensTable;
  password_reset_tokens: PasswordResetTokensTable;
  devices: DevicesTable;
  groups: GroupsTable;
  challenge_templates: ChallengeTemplatesTable;
  challenge_template_goals: ChallengeTemplateGoalsTable;
  group_memberships: GroupMembershipsTable;
  invite_codes: InviteCodesTable;
  goals: GoalsTable;
  progress_entries: ProgressEntriesTable;
  nudges: NudgesTable;
  group_activities: GroupActivitiesTable;
  activity_reactions: ActivityReactionsTable;
  user_subscriptions: UserSubscriptionsTable;
  subscription_downgrade_history: SubscriptionDowngradeHistoryTable;
  subscription_transactions: SubscriptionTransactionsTable;
  user_consents: UserConsentsTable;
  progress_photos: ProgressPhotosTable;
  photo_upload_log: PhotoUploadLogTable;
  user_notifications: UserNotificationsTable;
  challenge_completion_push_queue: ChallengeCompletionPushQueueTable;
  user_milestone_grants: UserMilestoneGrantsTable;
  referral_tokens: ReferralTokensTable;
  group_heat: GroupHeatTable;
  group_daily_gcr: GroupDailyGcrTable;
  user_logging_patterns: UserLoggingPatternsTable;
  reminder_history: ReminderHistoryTable;
  user_reminder_preferences: UserReminderPreferencesTable;
  weekly_recaps_sent: WeeklyRecapsSentTable;
}
