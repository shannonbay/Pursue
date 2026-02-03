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
  created_by_user_id: string;
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
  group_activities: GroupActivitiesTable;
}
