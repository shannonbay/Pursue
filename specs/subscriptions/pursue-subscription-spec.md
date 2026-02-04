# Pursue Freemium Subscription Model Specification

**Version:** 1.0  
**Last Updated:** 2026-02-05  
**Status:** Draft

## Overview

Pursue operates on a freemium model where users can participate in one group for free with full feature access, or upgrade to Premium for $30/year to participate in up to 10 groups. This model balances accessibility for casual users while monetizing power users who want to pursue multiple goals simultaneously.

## Subscription Tiers

### Free Tier
- **Cost:** $0
- **Group Limit:** 1 group (created or joined)
- **Features:** Full access to all Pursue features within that one group
- **Duration:** Unlimited

### Premium Tier
- **Cost:** $30 USD/year
- **Group Limit:** 10 groups (created or joined)
- **Features:** Full access to all Pursue features across all groups
- **Duration:** Annual subscription with auto-renewal
- **Payment:** Google Play Billing (Android), App Store In-App Purchase (iOS future)

## Feature Access Matrix

| Feature | Free Tier | Premium Tier |
|---------|-----------|--------------|
| Create groups | ✓ (1 total) | ✓ (10 total) |
| Join groups | ✓ (1 total) | ✓ (10 total) |
| Daily habits | ✓ | ✓ |
| Weekly goals | ✓ | ✓ |
| Life milestones | ✓ | ✓ |
| Check-ins | ✓ | ✓ |
| Comments | ✓ | ✓ |
| Reactions | ✓ | ✓ |
| Notifications | ✓ | ✓ |
| Admin controls | ✓ | ✓ |
| Profile customization | ✓ | ✓ |
| **Data export** | **✓ (30 days max)** | **✓ (12 months max)** |

**Note:** Free tier users have access to ALL features within their one allowed group, with the only restrictions being group quantity (1 vs 10) and data export period (30 days vs 12 months).

## Business Logic

### Group Counting Rules

1. **Combined Limit:** Created groups and joined groups count toward the same limit
   - Free: 1 group total (could be 1 created, or 1 joined, not both)
   - Premium: 10 groups total (any combination of created/joined)

2. **What Counts as a Group:**
   - Groups you created (as admin)
   - Groups you joined (as member)
   - Groups where your membership is "active" status

3. **What Doesn't Count:**
   - Groups where your membership is "pending" (waiting admin approval)
   - Groups where your membership is "rejected"
   - Groups you left (membership deleted/archived)
   - Groups that were deleted by admin

### Upgrade Behavior

When a free user upgrades to Premium:
- Immediately gains access to join/create up to 9 additional groups
- Existing group membership remains intact
- Any pending join requests can now be approved without hitting limit
- No loss of data or progress

### Downgrade Behavior

When a Premium user's subscription expires or they cancel:
- User reverts to free tier (1 group limit)
- System does NOT automatically remove them from groups
- User enters "over-limit" state if in more than 1 group
- User must choose which ONE group to keep active
- User cannot join or create new groups until under limit
- User retains read-only access to over-limit groups for 30 days
- After 30 days, user is automatically removed from all but their most recently active group

### Edge Cases

**Case 1: User in 5 groups, subscription expires**
- User immediately flagged as "over_limit"
- Next app open: modal forces user to select 1 group to keep
- Other 4 groups: read-only access for 30 days
- After selection: normal free tier experience

**Case 2: User attempts to join group when at limit**
- Free user in 1 group, clicks "Join Group" button or opens invite link
- UI immediately shows "Upgrade to Premium" dialog before any join request is submitted
- No pending request created until user upgrades or leaves existing group
- Same blocking applies to "Create Group" button when at limit

**Case 3: User creates group while at limit**
- Free user in 1 group attempts to create another: blocked at UI level
- Error message: "Upgrade to Premium to create additional groups"
- Premium user at 10 groups: blocked with message "Maximum groups reached (10)"

**Case 4: Admin removes user from their only group**
- User can immediately join or create a new group (they're back to 0/1)

## Database Schema Changes

### New Tables

```sql
-- User subscription tracking
CREATE TABLE user_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tier VARCHAR(20) NOT NULL CHECK (tier IN ('free', 'premium')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('active', 'cancelled', 'expired', 'grace_period')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    platform VARCHAR(20) CHECK (platform IN ('google_play', 'app_store')),
    platform_subscription_id VARCHAR(255),
    platform_purchase_token TEXT,
    auto_renew BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, started_at)
);

CREATE INDEX idx_user_subscriptions_user_id ON user_subscriptions(user_id);
CREATE INDEX idx_user_subscriptions_status ON user_subscriptions(status);
CREATE INDEX idx_user_subscriptions_expires_at ON user_subscriptions(expires_at) WHERE status = 'active';

-- Track when users were over limit and which groups they kept
CREATE TABLE subscription_downgrade_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    downgrade_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    previous_tier VARCHAR(20) NOT NULL,
    groups_before_downgrade INTEGER NOT NULL,
    kept_group_id UUID REFERENCES groups(id) ON DELETE SET NULL,
    removed_group_ids UUID[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subscription_downgrade_history_user_id ON subscription_downgrade_history(user_id);

-- Platform transaction history for reconciliation
CREATE TABLE subscription_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES user_subscriptions(id) ON DELETE CASCADE,
    transaction_type VARCHAR(50) NOT NULL CHECK (transaction_type IN ('purchase', 'renewal', 'cancellation', 'refund')),
    platform VARCHAR(20) NOT NULL CHECK (platform IN ('google_play', 'app_store')),
    platform_transaction_id VARCHAR(255) NOT NULL,
    amount_cents INTEGER,
    currency VARCHAR(3),
    transaction_date TIMESTAMPTZ NOT NULL,
    raw_receipt TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(platform, platform_transaction_id)
);

CREATE INDEX idx_subscription_transactions_subscription_id ON subscription_transactions(subscription_id);
CREATE INDEX idx_subscription_transactions_platform_transaction_id ON subscription_transactions(platform, platform_transaction_id);
```

### Modified Tables

```sql
-- Add subscription tracking to users table
ALTER TABLE users ADD COLUMN current_subscription_tier VARCHAR(20) NOT NULL DEFAULT 'free' CHECK (current_subscription_tier IN ('free', 'premium'));
ALTER TABLE users ADD COLUMN subscription_status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (subscription_status IN ('active', 'cancelled', 'expired', 'grace_period', 'over_limit'));
ALTER TABLE users ADD COLUMN group_limit INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN current_group_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_users_subscription_tier ON users(current_subscription_tier);
CREATE INDEX idx_users_subscription_status ON users(subscription_status);
```

## API Endpoints

### Get Current Subscription

```
GET /api/v1/users/me/subscription
```

**Response 200:**
```json
{
  "tier": "free",
  "status": "active",
  "group_limit": 1,
  "current_group_count": 1,
  "groups_remaining": 0,
  "is_over_limit": false,
  "subscription_expires_at": null,
  "auto_renew": null,
  "can_create_group": false,
  "can_join_group": false
}
```

### Check Group Join/Create Eligibility

```
GET /api/v1/users/me/subscription/eligibility
```

**Response 200:**
```json
{
  "can_create_group": false,
  "can_join_group": false,
  "reason": "at_group_limit",
  "current_count": 1,
  "limit": 1,
  "upgrade_required": true
}
```

### Initiate Premium Upgrade

```
POST /api/v1/subscriptions/upgrade
```

**Request Body:**
```json
{
  "platform": "google_play",
  "purchase_token": "...",
  "product_id": "pursue_premium_annual"
}
```

**Response 200:**
```json
{
  "subscription_id": "uuid",
  "tier": "premium",
  "status": "active",
  "expires_at": "2027-02-05T12:00:00Z",
  "group_limit": 10
}
```

### Verify Purchase (Internal/Webhook)

```
POST /api/v1/subscriptions/verify
```

**Request Body:**
```json
{
  "platform": "google_play",
  "purchase_token": "...",
  "product_id": "pursue_premium_annual",
  "user_id": "uuid"
}
```

This endpoint verifies with Google Play Billing and updates subscription status.

### Handle Subscription Downgrade

```
POST /api/v1/subscriptions/downgrade/select-group
```

**Request Body:**
```json
{
  "keep_group_id": "uuid"
}
```

**Response 200:**
```json
{
  "status": "success",
  "kept_group": {
    "id": "uuid",
    "name": "Morning Workout Crew"
  },
  "removed_groups": [
    {"id": "uuid", "name": "Book Club"},
    {"id": "uuid", "name": "Meditation Group"}
  ],
  "read_only_access_until": "2026-03-07T12:00:00Z"
}
```

### Cancel Subscription

```
POST /api/v1/subscriptions/cancel
```

**Response 200:**
```json
{
  "status": "cancelled",
  "access_until": "2027-02-05T12:00:00Z",
  "auto_renew": false,
  "message": "Your subscription will remain active until Feb 5, 2027"
}
```

### Validate Export Date Range

```
GET /api/v1/groups/:group_id/export-progress/validate-range
```

**Query Parameters:**
```json
{
  "start_date": "2025-01-01",
  "end_date": "2026-01-01"
}
```

**Response 200:**
```json
{
  "valid": true,
  "max_days_allowed": 365,
  "requested_days": 365,
  "subscription_tier": "premium"
}
```

**Response 400 (Free tier exceeding limit):**
```json
{
  "valid": false,
  "max_days_allowed": 30,
  "requested_days": 365,
  "subscription_tier": "free",
  "error": "date_range_exceeds_tier_limit",
  "message": "Free tier users can export up to 30 days of data. Upgrade to Premium for up to 12 months."
}
```

## Validation Rules

### Group Creation/Join Validation

```typescript
async function canUserJoinOrCreateGroup(userId: string): Promise<{
  allowed: boolean;
  reason?: string;
}> {
  const user = await getUserWithSubscription(userId);
  
  // Check current active group count
  const activeGroupCount = await getActiveGroupCount(userId);
  
  if (activeGroupCount >= user.group_limit) {
    return {
      allowed: false,
      reason: user.current_subscription_tier === 'free' 
        ? 'free_tier_limit_reached' 
        : 'premium_tier_limit_reached'
    };
  }
  
  return { allowed: true };
}
```

### Export Date Range Validation

```typescript
async function validateExportDateRange(
  userId: string,
  startDate: string,
  endDate: string
): Promise<{
  valid: boolean;
  maxDaysAllowed: number;
  requestedDays: number;
  error?: string;
}> {
  const user = await getUserWithSubscription(userId);
  
  // Calculate requested date range in days
  const start = new Date(startDate);
  const end = new Date(endDate);
  const requestedDays = Math.ceil((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24));
  
  // Determine max allowed days based on tier
  const maxDaysAllowed = user.current_subscription_tier === 'premium' 
    ? 365  // 12 months for premium
    : 30;  // 30 days for free
  
  if (requestedDays > maxDaysAllowed) {
    return {
      valid: false,
      maxDaysAllowed,
      requestedDays,
      error: user.current_subscription_tier === 'free'
        ? 'free_tier_export_limit_exceeded'
        : 'premium_tier_export_limit_exceeded'
    };
  }
  
  // Also enforce absolute maximum of 24 months (730 days) for all users
  if (requestedDays > 730) {
    return {
      valid: false,
      maxDaysAllowed: 730,
      requestedDays,
      error: 'absolute_maximum_exceeded'
    };
  }
  
  return {
    valid: true,
    maxDaysAllowed,
    requestedDays
  };
}
```

### Subscription Status Check

```typescript
async function updateSubscriptionStatus(userId: string): Promise<void> {
  const subscription = await getCurrentSubscription(userId);
  
  if (!subscription || subscription.tier === 'free') {
    // Free tier users
    const groupCount = await getActiveGroupCount(userId);
    await updateUser(userId, {
      current_subscription_tier: 'free',
      subscription_status: groupCount > 1 ? 'over_limit' : 'active',
      group_limit: 1,
      current_group_count: groupCount
    });
    return;
  }
  
  // Premium tier users
  const now = new Date();
  const expiresAt = subscription.expires_at;
  
  if (expiresAt && now > expiresAt) {
    // Subscription expired
    const groupCount = await getActiveGroupCount(userId);
    await updateUser(userId, {
      current_subscription_tier: 'free',
      subscription_status: groupCount > 1 ? 'over_limit' : 'expired',
      group_limit: 1,
      current_group_count: groupCount
    });
    
    await updateSubscription(subscription.id, { status: 'expired' });
  } else {
    // Active premium
    const groupCount = await getActiveGroupCount(userId);
    await updateUser(userId, {
      current_subscription_tier: 'premium',
      subscription_status: 'active',
      group_limit: 10,
      current_group_count: groupCount
    });
  }
}
```

## Mobile UI/UX

### Subscription Status Display

**Navigation Drawer / Profile:**
```
┌─────────────────────────┐
│ Shannon                 │
│ Free • 1/1 groups      │
│ [Upgrade to Premium]   │
└─────────────────────────┘
```

```
┌─────────────────────────┐
│ Shannon                 │
│ Premium • 3/10 groups   │
│ Renews Feb 5, 2027     │
└─────────────────────────┘
```

### Group Limit Reached Dialog

```
┌───────────────────────────────┐
│  Upgrade to Premium           │
├───────────────────────────────┤
│                               │
│  You've reached your free     │
│  tier limit of 1 group.       │
│                               │
│  Upgrade to Premium for:      │
│  • Up to 10 groups            │
│  • All features unlocked      │
│  • Only $30/year              │
│                               │
│  [Maybe Later]  [Upgrade]     │
└───────────────────────────────┘
```

### Over-Limit Selection Dialog

```
┌───────────────────────────────┐
│  Choose Your Group            │
├───────────────────────────────┤
│                               │
│  Your subscription expired.   │
│  Select ONE group to keep:    │
│                               │
│  ○ Morning Workout Crew       │
│  ○ Book Club Monthly          │
│  ● Meditation Buddies         │
│  ○ Career Goals 2026          │
│  ○ Running Team               │
│                               │
│  Other groups: read-only      │
│  for 30 days                  │
│                               │
│  [Keep Selected Group]        │
│                               │
│  Or upgrade to keep all:      │
│  [Upgrade to Premium]         │
└───────────────────────────────┘
```

### Upgrade Flow Screen

```
┌───────────────────────────────┐
│  ← Pursue Premium             │
├───────────────────────────────┤
│                               │
│  Join up to 10 groups         │
│  All features included        │
│                               │
│  $30/year                     │
│                               │
│  ✓ Create unlimited habits    │
│  ✓ Track weekly goals         │
│  ✓ Achieve life milestones    │
│  ✓ Group accountability       │
│  ✓ Comments & reactions       │
│  ✓ Real-time notifications    │
│                               │
│  [Start 7-Day Free Trial]     │
│                               │
│  Auto-renews annually         │
│  Cancel anytime               │
│                               │
└───────────────────────────────┘
```

### Export Date Range Limit Dialog

```
┌───────────────────────────────┐
│  Export Limit Reached         │
├───────────────────────────────┤
│                               │
│  Free users can export up to  │
│  30 days of data at a time.   │
│                               │
│  You selected:                │
│  Jan 1, 2025 - Jun 1, 2025    │
│  (151 days)                   │
│                               │
│  Upgrade to Premium for:      │
│  • Export up to 12 months     │
│  • Only $30/year              │
│                               │
│  [Adjust Dates] [Upgrade]     │
└───────────────────────────────┘
```

## Google Play Billing Integration

### Product Configuration

**Product ID:** `pursue_premium_annual`  
**Type:** Auto-renewable subscription  
**Price:** $30 USD/year  
**Free Trial:** 7 days (optional)  
**Grace Period:** 3 days (for payment issues)  

### Implementation Steps

1. **Add Google Play Billing Library**
```gradle
dependencies {
    implementation 'com.android.billingclient:billing-ktx:6.1.0'
}
```

2. **Initialize Billing Client**
```kotlin
class SubscriptionManager(private val context: Context) {
    private lateinit var billingClient: BillingClient
    
    fun initialize(onReady: () -> Unit) {
        billingClient = BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()
            
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    onReady()
                }
            }
            
            override fun onBillingServiceDisconnected() {
                // Retry connection
            }
        })
    }
}
```

3. **Query Subscription Products**
```kotlin
suspend fun getSubscriptionProducts(): List<ProductDetails> {
    val params = QueryProductDetailsParams.newBuilder()
        .setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId("pursue_premium_annual")
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
        )
        .build()
        
    val result = billingClient.queryProductDetails(params)
    return result.productDetailsList ?: emptyList()
}
```

4. **Launch Purchase Flow**
```kotlin
fun launchPurchaseFlow(activity: Activity, productDetails: ProductDetails) {
    val offerToken = productDetails.subscriptionOfferDetails
        ?.firstOrNull()?.offerToken ?: return
        
    val params = BillingFlowParams.newBuilder()
        .setProductDetailsParamsList(
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offerToken)
                    .build()
            )
        )
        .build()
        
    billingClient.launchBillingFlow(activity, params)
}
```

5. **Handle Purchase Updates**
```kotlin
private val purchasesUpdatedListener = PurchasesUpdatedListener { 
    billingResult, purchases ->
    
    if (billingResult.responseCode == BillingResponseCode.OK 
        && purchases != null) {
        
        for (purchase in purchases) {
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                    verifyPurchaseWithBackend(purchase)
                }
            }
        }
    }
}

suspend fun acknowledgePurchase(purchase: Purchase) {
    val params = AcknowledgePurchaseParams.newBuilder()
        .setPurchaseToken(purchase.purchaseToken)
        .build()
        
    billingClient.acknowledgePurchase(params)
}

suspend fun verifyPurchaseWithBackend(purchase: Purchase) {
    // Send to backend for verification
    api.verifySubscription(
        platform = "google_play",
        purchaseToken = purchase.purchaseToken,
        productId = purchase.products.first()
    )
}
```

6. **Check Active Subscriptions**
```kotlin
suspend fun checkActiveSubscriptions(): List<Purchase> {
    val params = QueryPurchasesParams.newBuilder()
        .setProductType(BillingClient.ProductType.SUBS)
        .build()
        
    val result = billingClient.queryPurchasesAsync(params)
    return result.purchasesList
}
```

### Backend Verification

Backend must verify purchases with Google Play Developer API:

```typescript
import { google } from 'googleapis';

async function verifyGooglePlayPurchase(
  packageName: string,
  subscriptionId: string,
  purchaseToken: string
): Promise<SubscriptionPurchase> {
  const androidPublisher = google.androidpublisher('v3');
  
  const auth = new google.auth.GoogleAuth({
    keyFile: 'path/to/service-account.json',
    scopes: ['https://www.googleapis.com/auth/androidpublisher']
  });
  
  const response = await androidPublisher.purchases.subscriptions.get({
    auth,
    packageName,
    subscriptionId,
    token: purchaseToken
  });
  
  return response.data;
}
```

Verification checks:
- Purchase is valid and not expired
- Purchase is not cancelled or refunded
- Purchase matches expected product ID
- Purchase is for the correct user

## Webhook Handling

### Google Play Real-time Developer Notifications

Configure webhook endpoint: `https://api.getpursue.app/webhooks/google-play`

```typescript
interface GooglePlayNotification {
  version: string;
  packageName: string;
  eventTimeMillis: string;
  subscriptionNotification?: {
    version: string;
    notificationType: number;
    purchaseToken: string;
    subscriptionId: string;
  };
}

async function handleGooglePlayWebhook(notification: GooglePlayNotification) {
  const { subscriptionNotification } = notification;
  if (!subscriptionNotification) return;
  
  const { notificationType, purchaseToken } = subscriptionNotification;
  
  switch (notificationType) {
    case 1: // SUBSCRIPTION_RECOVERED
      await handleSubscriptionRecovered(purchaseToken);
      break;
    case 2: // SUBSCRIPTION_RENEWED
      await handleSubscriptionRenewed(purchaseToken);
      break;
    case 3: // SUBSCRIPTION_CANCELED
      await handleSubscriptionCanceled(purchaseToken);
      break;
    case 4: // SUBSCRIPTION_PURCHASED
      await handleSubscriptionPurchased(purchaseToken);
      break;
    case 5: // SUBSCRIPTION_ON_HOLD
      await handleSubscriptionOnHold(purchaseToken);
      break;
    case 6: // SUBSCRIPTION_IN_GRACE_PERIOD
      await handleSubscriptionGracePeriod(purchaseToken);
      break;
    case 7: // SUBSCRIPTION_RESTARTED
      await handleSubscriptionRestarted(purchaseToken);
      break;
    case 8: // SUBSCRIPTION_PRICE_CHANGE_CONFIRMED
      await handlePriceChangeConfirmed(purchaseToken);
      break;
    case 9: // SUBSCRIPTION_DEFERRED
      await handleSubscriptionDeferred(purchaseToken);
      break;
    case 10: // SUBSCRIPTION_PAUSED
      await handleSubscriptionPaused(purchaseToken);
      break;
    case 11: // SUBSCRIPTION_PAUSE_SCHEDULE_CHANGED
      await handlePauseScheduleChanged(purchaseToken);
      break;
    case 12: // SUBSCRIPTION_REVOKED
      await handleSubscriptionRevoked(purchaseToken);
      break;
    case 13: // SUBSCRIPTION_EXPIRED
      await handleSubscriptionExpired(purchaseToken);
      break;
  }
}
```

## Cron Jobs & Background Tasks

### Daily Subscription Status Check

Runs daily at 2 AM UTC to check for expired subscriptions:

```typescript
async function checkExpiredSubscriptions() {
  const expiredSubs = await db
    .selectFrom('user_subscriptions')
    .where('status', '=', 'active')
    .where('expires_at', '<', new Date())
    .select(['id', 'user_id'])
    .execute();
    
  for (const sub of expiredSubs) {
    await updateSubscriptionStatus(sub.user_id);
    
    // Check if user is over limit
    const user = await getUser(sub.user_id);
    if (user.current_group_count > 1) {
      // Send notification to select a group
      await sendOverLimitNotification(sub.user_id);
    }
  }
}
```

### Grace Period Monitoring

Runs every 6 hours to check users in grace period:

```typescript
async function checkGracePeriodUsers() {
  const gracePeriodEnd = new Date();
  gracePeriodEnd.setDate(gracePeriodEnd.getDate() - 3);
  
  const users = await db
    .selectFrom('users')
    .where('subscription_status', '=', 'grace_period')
    .where('updated_at', '<', gracePeriodEnd)
    .select(['id'])
    .execute();
    
  for (const user of users) {
    await expireSubscription(user.id);
  }
}
```

### Over-Limit Cleanup

Runs daily at 3 AM UTC to remove read-only access after 30 days:

```typescript
async function cleanupOverLimitAccess() {
  const thirtyDaysAgo = new Date();
  thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);
  
  const overLimitUsers = await db
    .selectFrom('users')
    .where('subscription_status', '=', 'over_limit')
    .where('updated_at', '<', thirtyDaysAgo)
    .select(['id'])
    .execute();
    
  for (const user of overLimitUsers) {
    await autoSelectMostRecentGroup(user.id);
  }
}
```

## Testing Scenarios

### Test Case 1: Free User Creates Group & Exports Data
1. New user signs up (free tier)
2. User creates first group → Success
3. User adds goals and tracks progress for 60 days
4. User attempts to export 60 days of data → Blocked with message
5. User adjusts date range to 30 days → Export succeeds
6. User attempts to create second group → Blocked
7. UI shows "Upgrade to Premium" message

### Test Case 2: Free User Attempts to Join Group at Limit
1. Free user already in 1 group
2. User receives invite link and clicks it (or clicks "Join Group" button)
3. App checks group eligibility before showing join screen
4. "Upgrade to Premium" dialog shown immediately
5. No join request submitted
6. If user upgrades → can now proceed with join flow
7. If user dismisses dialog → returns to previous screen

### Test Case 3: Upgrade Flow with Export Feature
1. Free user at limit attempts to join group
2. "Upgrade to Premium" dialog shown
3. User clicks upgrade
4. Google Play billing flow launches
5. User completes purchase
6. Backend receives webhook
7. Backend verifies purchase with Google
8. User subscription updated to Premium
9. User can now join the pending group
10. User attempts to export 6 months of data → Success (previously limited to 30 days)

### Test Case 4: Subscription Expiration
1. Premium user in 5 groups
2. Subscription expires (simulate by setting expires_at to past)
3. Cron job detects expiration
4. User marked as "over_limit"
5. Next app open: forced to select 1 group
6. User selects group
7. Other 4 groups become read-only
8. After 30 days: user removed from other groups

### Test Case 5: Subscription Renewal
1. Premium user subscription approaching expiration
2. Google Play auto-renewal triggers
3. Backend receives renewal webhook
4. Subscription extended by 1 year
5. User continues with Premium access

### Test Case 6: Subscription Cancellation
1. Premium user cancels subscription
2. Subscription marked as "cancelled"
3. User retains access until expiration date
4. At expiration: downgrade flow triggers

### Test Case 7: Export Date Range Validation
1. Free user in 1 group with 90 days of progress data
2. User opens export screen, selects start date 90 days ago, end date today
3. Validation check runs before export → Date range exceeds 30 days
4. "Export Limit Reached" dialog shown with upgrade option
5. User adjusts date range to last 30 days → Export proceeds successfully
6. Premium user attempts same 90-day export → Export succeeds immediately

## Security Considerations

1. **Purchase Verification:** All purchases MUST be verified server-side with Google Play API
2. **Token Storage:** Purchase tokens must be stored securely in database
3. **Replay Protection:** Track processed webhook notifications to prevent replay attacks
4. **Signature Verification:** Verify webhook signatures from Google
5. **Rate Limiting:** Limit subscription check API calls to prevent abuse
6. **Encryption:** Encrypt sensitive purchase data at rest

## Analytics & Metrics

### Key Metrics to Track

1. **Conversion Metrics:**
   - Free to Premium conversion rate
   - Time to first upgrade (from signup)
   - Upgrade trigger source (group limit, specific feature)

2. **Retention Metrics:**
   - Subscription renewal rate
   - Cancellation rate by tenure
   - Downgrade rate
   - Re-upgrade rate (users who downgraded then upgraded again)

3. **Usage Metrics:**
   - Average groups per free user (should be close to 1)
   - Average groups per premium user
   - Group creation vs join ratio by tier

4. **Revenue Metrics:**
   - Monthly Recurring Revenue (MRR)
   - Annual Recurring Revenue (ARR)
   - Lifetime Value (LTV) by user cohort
   - Churn rate

### Event Tracking

```typescript
enum SubscriptionEvent {
  UPGRADE_INITIATED = 'subscription.upgrade.initiated',
  UPGRADE_COMPLETED = 'subscription.upgrade.completed',
  UPGRADE_FAILED = 'subscription.upgrade.failed',
  RENEWAL_SUCCEEDED = 'subscription.renewal.succeeded',
  RENEWAL_FAILED = 'subscription.renewal.failed',
  CANCELLATION_INITIATED = 'subscription.cancellation.initiated',
  DOWNGRADE_FORCED = 'subscription.downgrade.forced',
  GROUP_SELECTED_ON_DOWNGRADE = 'subscription.downgrade.group_selected',
  OVER_LIMIT_NOTIFICATION_SENT = 'subscription.over_limit.notification_sent',
  GROUP_LIMIT_REACHED = 'subscription.limit_reached',
}
```

## Error Handling

### Common Error Scenarios

1. **Purchase Verification Failed:**
```json
{
  "error": "purchase_verification_failed",
  "message": "Unable to verify purchase with Google Play",
  "action": "retry_later"
}
```

2. **Group Limit Reached:**
```json
{
  "error": "group_limit_reached",
  "message": "You've reached your group limit. Upgrade to Premium for more.",
  "current_limit": 1,
  "current_count": 1,
  "upgrade_required": true
}
```

3. **Export Date Range Exceeds Tier Limit:**
```json
{
  "error": "export_date_range_exceeds_limit",
  "message": "Free tier users can export up to 30 days of data. Upgrade to Premium for up to 12 months.",
  "max_days_allowed": 30,
  "requested_days": 90,
  "subscription_tier": "free",
  "upgrade_required": true
}
```

4. **Over Limit - Group Selection Required:**
```json
{
  "error": "group_selection_required",
  "message": "Your subscription expired. Please select a group to keep.",
  "groups": [...],
  "deadline": "2026-03-07T12:00:00Z"
}
```

5. **Invalid Purchase Token:**
```json
{
  "error": "invalid_purchase_token",
  "message": "The purchase token is invalid or has already been used"
}
```

## Migration Plan

### Phase 1: Database Setup
1. Run migration to create subscription tables
2. Set all existing users to free tier with group_limit=1
3. Update current_group_count for all users
4. Identify any users currently in multiple groups (handle manually if needed)

### Phase 2: Backend Implementation
1. Implement subscription API endpoints
2. Implement Google Play billing verification
3. Set up webhook endpoint for Real-time Developer Notifications
4. Implement subscription status checking logic
5. Create cron jobs for background tasks

### Phase 3: Mobile Implementation
1. Integrate Google Play Billing library
2. Build subscription UI screens
3. Implement upgrade flow
4. Implement over-limit selection flow
5. Add subscription status indicators throughout app

### Phase 4: Testing
1. Test with Google Play test accounts
2. Test subscription lifecycle (purchase, renewal, cancellation)
3. Test downgrade flows
4. Test group limit enforcement
5. Verify webhook processing

### Phase 5: Soft Launch
1. Deploy to internal testing track
2. Enable subscriptions for beta testers
3. Monitor metrics and error rates
4. Gather user feedback
5. Iterate on UX based on feedback

### Phase 6: Public Launch
1. Deploy to production
2. Enable subscriptions for all users
3. Monitor conversion rates
4. A/B test upgrade messaging
5. Optimize based on data

## Support & Edge Cases

### Customer Support Scenarios

**User: "I paid but still can't join groups"**
- Check subscription status in database
- Verify purchase with Google Play API
- Check webhook processing logs
- Manual subscription activation if needed

**User: "I was removed from my groups"**
- Check downgrade history
- Verify subscription expiration date
- Check if user selected a group or auto-removed
- Restore access if within 30-day grace period

**User: "Charged but subscription not active"**
- Verify transaction in Google Play Console
- Check webhook delivery status
- Manually process purchase if webhook missed
- Issue refund if appropriate

**User: "Want to switch which group I keep"**
- Premium: no action needed, upgrade to keep all
- Free: provide one-time group switch within 30-day window
- Log support action for tracking

### Refund Policy

- 7-day money-back guarantee for new subscriptions
- Pro-rated refunds handled through Google Play
- Manual refunds for billing errors
- Document all refund reasons for product improvement

## Future Enhancements

### Potential Premium Features (Future Tiers)

- **Ultra Premium ($60/year):**
  - Unlimited groups
  - Custom group themes/branding
  - Advanced analytics & insights
  - Priority support

- **Lifetime Premium ($200 one-time):**
  - All Premium features
  - No recurring billing
  - Grandfathered pricing on future features

### Alternative Pricing Models (Future Consideration)

- Monthly subscription option ($3.99/month)
- Family plan (5 users, $50/year)
- Team/Organization pricing (custom)

### Promotional Strategies

- First-time user discount (e.g., $20 for first year)
- Referral rewards (1 month free per referral)
- Seasonal promotions (New Year's resolution timing)
- Win-back offers for churned users

---

## Summary

The Pursue freemium model balances accessibility with monetization by offering full feature access to all users while limiting group participation and data export capabilities. Free users can fully experience the product with one group and 30-day data exports, and power users pursuing multiple goals simultaneously or needing historical analysis are incentivized to upgrade. The $30/year price point is affordable while providing sustainable revenue as the user base grows.

**Tier Comparison:**
- **Free:** 1 group, 30-day data exports, all features
- **Premium:** 10 groups, 12-month data exports, all features

Key success factors:
- Seamless upgrade experience through Google Play
- Clear value proposition (9 more groups + extended export period)
- Respectful handling of subscription expiration
- Robust backend verification and webhook processing
- Comprehensive analytics to optimize conversion

This model positions Pursue for sustainable growth while maintaining an accessible free tier that demonstrates value before asking for payment.
