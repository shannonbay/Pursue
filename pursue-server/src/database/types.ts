import type { ColumnType, Generated, Insertable, Selectable, Updateable } from 'kysely';

// Users table
export interface UsersTable {
  id: Generated<string>;
  email: string;
  display_name: string;
  avatar_data: Buffer | null;
  avatar_mime_type: string | null;
  password_hash: string | null;
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
  created_at: ColumnType<Date, string | undefined, never>;
  updated_at: ColumnType<Date, string | undefined, string | undefined>;
}

export type Group = Selectable<GroupsTable>;
export type NewGroup = Insertable<GroupsTable>;
export type GroupUpdate = Updateable<GroupsTable>;

// Group memberships table
export interface GroupMembershipsTable {
  id: Generated<string>;
  group_id: string;
  user_id: string;
  role: string;
  status: ColumnType<'pending' | 'active' | 'declined', string | undefined, string>;
  joined_at: ColumnType<Date, string | undefined, never>;
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

// Photo upload log table (permanent record for quota enforcement)
export interface PhotoUploadLogTable {
  id: Generated<string>;
  user_id: string;
  uploaded_at: ColumnType<Date, string | undefined, never>;
}

export type PhotoUploadLog = Selectable<PhotoUploadLogTable>;
export type NewPhotoUploadLog = Insertable<PhotoUploadLogTable>;

// Database interface combining all tables
export interface Database {
  users: UsersTable;
  auth_providers: AuthProvidersTable;
  refresh_tokens: RefreshTokensTable;
  password_reset_tokens: PasswordResetTokensTable;
  devices: DevicesTable;
  groups: GroupsTable;
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
}
