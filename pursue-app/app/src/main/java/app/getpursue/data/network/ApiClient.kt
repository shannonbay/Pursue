package app.getpursue.data.network

import android.content.Context
import app.getpursue.models.GroupsResponse
import app.getpursue.models.TodayGoal
import app.getpursue.models.TodayGoalsResponse
import app.getpursue.models.TodayGroup
import app.getpursue.models.GroupGoalResponse
import app.getpursue.models.MyProgressResponse
import app.getpursue.models.GroupDetailResponse
import app.getpursue.models.GroupMembersResponse
import app.getpursue.models.PendingMembersResponse
import app.getpursue.models.GroupActivityResponse
import app.getpursue.models.GroupGoalsResponse
import com.google.gson.Gson
import android.util.Log
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import app.getpursue.BuildConfig
import java.net.URLEncoder
import java.time.ZoneId

/**
 * API client for making HTTP requests to the Pursue backend.
 * Base URL: debug builds use pursue.api.base.url from local.properties (or production);
 * release builds always use https://api.getpursue.app/api.
 */
object ApiClient {
    @Volatile
    private var baseUrl: String = BuildConfig.API_BASE_URL

    /**
     * Returns the current base URL for API requests.
     * Used by app code that needs to build URLs (e.g. Glide for avatars/icons).
     */
    fun getBaseUrl(): String = baseUrl

    /**
     * Override base URL for E2E tests (e.g. localhost). Call from E2ETest @Before.
     */
    fun setBaseUrlForE2E(url: String) {
        baseUrl = url
    }

    /**
     * Restore default base URL after E2E. Optional; call from E2ETest @After.
     */
    fun resetBaseUrlForE2E() {
        baseUrl = BuildConfig.API_BASE_URL
    }

    private var client: OkHttpClient? = null

    /**
     * Build an OkHttpClient with auth interceptor and token authenticator.
     * Use this in Application.onCreate() and pass the result to initialize().
     * Uses context.applicationContext to avoid leaking activity context.
     */
    fun buildClient(context: Context): OkHttpClient {
        val appContext = context.applicationContext
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(appContext))
            .addInterceptor(UserNotFoundSignOutInterceptor(appContext))
            .authenticator(TokenAuthenticator(appContext))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Initialize ApiClient with a pre-built OkHttpClient.
     * Call from Application.onCreate(): initialize(buildClient(this))
     */
    fun initialize(client: OkHttpClient) {
        this.client = client
    }

    /**
     * Get configured OkHttpClient. Requires initialize() to have been called.
     */
    private fun getClient(): OkHttpClient {
        return client ?: throw IllegalStateException(
            "ApiClient not initialized. Call ApiClient.initialize(buildClient(context)) from Application.onCreate()."
        )
    }
    
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Register a new user account.
     * 
     * @param displayName User's display name
     * @param email User's email address
     * @param password User's password (plain text, will be hashed server-side with bcrypt)
     * @return RegistrationResponse with access_token and refresh_token on success
     * @throws ApiException on error
     */
    suspend fun register(
        displayName: String,
        email: String,
        password: String,
        consentAgreed: Boolean,
        consentTermsVersion: String? = null,
        consentPrivacyVersion: String? = null
    ): RegistrationResponse {
        val requestBody = gson.toJson(RegisterRequest(displayName, email, password, consentAgreed, consentTermsVersion, consentPrivacyVersion))
            .toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$baseUrl/auth/register")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, RegistrationResponse::class.java)
        }
    }

    /**
     * Sign in with email and password.
     * 
     * @param email User's email address
     * @param password User's password (plain text, will be verified server-side with bcrypt)
     * @return LoginResponse with access_token and refresh_token on success
     * @throws ApiException on error
     */
    suspend fun login(
        email: String,
        password: String
    ): LoginResponse {
        val requestBody = gson.toJson(LoginRequest(email, password))
            .toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$baseUrl/auth/login")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, LoginResponse::class.java)
        }
    }

    /**
     * Refresh access token using refresh token.
     * 
     * This method uses a separate client without authenticator to avoid infinite loops.
     * 
     * @param refreshToken The refresh token to use for refreshing
     * @return RefreshTokenResponse with new access_token and refresh_token
     * @throws ApiException on error
     */
    suspend fun refreshToken(
        refreshToken: String
    ): RefreshTokenResponse {
        val requestBody = gson.toJson(RefreshTokenRequest(refreshToken))
            .toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$baseUrl/auth/refresh")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()
        
        // Use a separate client without authenticator to avoid infinite loop
        val refreshClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        
        return try {
            refreshClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                
                if (!response.isSuccessful) {
                    Log.e("ApiClient", "Token refresh failed: ${response.code} - ${response.message}")
                    Log.e("ApiClient", "Response body: $responseBody")
                    
                    val errorMessage = try {
                        val error = gson.fromJson(responseBody, ErrorResponse::class.java)
                        error.message ?: "Token refresh failed with code ${response.code}"
                    } catch (e: Exception) {
                        "Token refresh failed with code ${response.code}"
                    }
                    throw ApiException(response.code, errorMessage)
                }
                
                gson.fromJson(responseBody, RefreshTokenResponse::class.java)
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            Log.e("ApiClient", "Token refresh network error", e)
            throw ApiException(0, "Network error: ${e.message}")
        }
    }

    /**
     * Sign in or register with Google OAuth.
     * 
     * @param idToken Google ID token from Google Sign-In SDK
     * @return GoogleSignInResponse with access_token, refresh_token, is_new_user flag, and user data
     * @throws ApiException on error
     */
    suspend fun signInWithGoogle(
        idToken: String,
        consentAgreed: Boolean? = null,
        consentTermsVersion: String? = null,
        consentPrivacyVersion: String? = null
    ): GoogleSignInResponse {
        val requestBody = gson.toJson(GoogleSignInRequest(idToken, consentAgreed, consentTermsVersion, consentPrivacyVersion))
            .toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$baseUrl/auth/google")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()
        
        // Log request details for debugging (ID token truncated for security)
        Log.d("ApiClient", "Calling POST $baseUrl/auth/google")
        Log.d("ApiClient", "ID token length: ${idToken.length}, first 20 chars: ${idToken.take(20)}...")
        
        return executeRequest(request, getClient()) { responseBody ->
            Log.d("ApiClient", "Google sign-in response received")
            gson.fromJson(responseBody, GoogleSignInResponse::class.java)
        }
    }

    /**
     * Register device for push notifications.
     * 
     * @param accessToken JWT access token for authentication
     * @param fcmToken Firebase Cloud Messaging token
     * @param deviceName Human-readable device name (e.g., "Pixel 8 Pro")
     * @param platform Platform identifier (e.g., "android")
     * @return DeviceRegistrationResponse with device information
     * @throws ApiException on error
     */
    suspend fun registerDevice(
        accessToken: String,
        fcmToken: String,
        deviceName: String,
        platform: String = "android"
    ): DeviceRegistrationResponse {
        val requestBody = gson.toJson(DeviceRegistrationRequest(fcmToken, deviceName, platform))
            .toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$baseUrl/devices/register")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, DeviceRegistrationResponse::class.java)
        }
    }

    /**
     * Get current user profile.
     * 
     * @param accessToken JWT access token for authentication
     * @return User object with current user information
     * @throws ApiException on error
     */
    suspend fun getMyUser(
        accessToken: String
    ): User {
        val request = Request.Builder()
            .url("$baseUrl/users/me")
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, User::class.java)
        }
    }

    /**
     * Get user's groups.
     * 
     * @param accessToken JWT access token for authentication
     * @param limit Maximum number of groups to return (default 50, max 100)
     * @param offset Number of groups to skip (default 0)
     * @return GroupsResponse with list of groups and total count
     * @throws ApiException on error
     */
    suspend fun getMyGroups(
        accessToken: String,
        limit: Int = 50,
        offset: Int = 0
    ): GroupsResponse {
        val url = "$baseUrl/users/me/groups?limit=$limit&offset=$offset"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, GroupsResponse::class.java)
        }
    }

    /**
     * Get current subscription state. GET /api/users/me/subscription
     */
    suspend fun getSubscription(accessToken: String): SubscriptionState {
        val request = Request.Builder()
            .url("$baseUrl/users/me/subscription")
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, SubscriptionState::class.java)
        }
    }

    /**
     * Get group create/join eligibility. GET /api/users/me/subscription/eligibility
     */
    suspend fun getSubscriptionEligibility(accessToken: String): SubscriptionEligibility {
        val request = Request.Builder()
            .url("$baseUrl/users/me/subscription/eligibility")
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, SubscriptionEligibility::class.java)
        }
    }

    /**
     * Validate export date range against subscription tier. GET /api/groups/:group_id/export-progress/validate-range
     * Returns same DTO for 200 (valid=true) and 400 (valid=false); check response.valid. Other errors throw ApiException.
     */
    suspend fun validateExportRange(
        accessToken: String,
        groupId: String,
        startDate: String,
        endDate: String
    ): ValidateExportRangeResponse {
        val url = "$baseUrl/groups/$groupId/export-progress/validate-range?start_date=$startDate&end_date=$endDate"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        val response = getClient().newCall(request).execute()
        val body = response.body?.string() ?: throw ApiException(response.code, "Empty response")
        val parsed = gson.fromJson(body, ValidateExportRangeResponse::class.java)
        if (response.isSuccessful || response.code == 400) return parsed
        throw ApiException(response.code, parsed.message ?: "Validation failed", parsed.error)
    }

    /**
     * Upgrade to premium. POST /api/subscriptions/upgrade
     */
    suspend fun upgradeSubscription(
        accessToken: String,
        platform: String,
        purchaseToken: String,
        productId: String
    ): UpgradeSubscriptionResponse {
        val body = gson.toJson(UpgradeSubscriptionRequest(platform = platform, purchase_token = purchaseToken, product_id = productId))
        val request = Request.Builder()
            .url("$baseUrl/subscriptions/upgrade")
            .post(body.toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .build()
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, UpgradeSubscriptionResponse::class.java)
        }
    }

    /**
     * Select group to keep on downgrade (over_limit). POST /api/subscriptions/downgrade/select-group
     */
    suspend fun downgradeSelectGroup(accessToken: String, keepGroupId: String): DowngradeSelectGroupResponse {
        val body = gson.toJson(mapOf("keep_group_id" to keepGroupId))
        val request = Request.Builder()
            .url("$baseUrl/subscriptions/downgrade/select-group")
            .post(body.toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .build()
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, DowngradeSelectGroupResponse::class.java)
        }
    }

    /**
     * Get today's goals for the user via client-side aggregation.
     * Fetches user's groups, then each group's daily goals with progress, and builds
     * TodayGoalsResponse. Use this when the backend has no GET /users/me/today-goals.
     *
     * @param accessToken JWT access token for authentication
     * @return TodayGoalsResponse with today's daily goals grouped by group
     * @throws ApiException on error
     */
    suspend fun getTodayGoals(
        accessToken: String
    ): TodayGoalsResponse {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        val groupsResponse = getMyGroups(accessToken)
        val todayGroups = mutableListOf<TodayGroup>()
        var totalCompleted = 0
        var totalGoals = 0

        for (group in groupsResponse.groups) {
            val goalsResponse = try {
                getGroupGoals(
                    accessToken = accessToken,
                    groupId = group.id,
                    cadence = "daily",
                    archived = false,
                    includeProgress = true,
                    userTimezone = ZoneId.systemDefault().id
                )
            } catch (e: ApiException) {
                if (e.code == 403 || e.code == 404) continue
                throw e
            }
            if (goalsResponse.goals.isEmpty()) continue

            val todayGoals = goalsResponse.goals.map { mapGroupGoalResponseToTodayGoal(it) }
            val completed = todayGoals.count { it.completed }
            val total = todayGoals.size
            totalCompleted += completed
            totalGoals += total

            todayGroups.add(
                TodayGroup(
                    group_id = group.id,
                    group_name = group.name,
                    has_icon = group.has_icon,
                    icon_emoji = group.icon_emoji,
                    completed_count = completed,
                    total_count = total,
                    goals = todayGoals
                )
            )
        }

        val overallPercent = if (totalGoals > 0) (totalCompleted * 100 / totalGoals) else 0
        return TodayGoalsResponse(
            date = today,
            overall_completion_percent = overallPercent,
            groups = todayGroups
        )
    }

    private fun mapGroupGoalResponseToTodayGoal(r: GroupGoalResponse): TodayGoal {
        val progress = r.current_period_progress
        val completed: Boolean
        val progressValue: Int?
        val targetValue = r.target_value?.toInt()

        if (progress != null) {
            when (r.metric_type) {
                "binary" -> {
                    completed = progress.user_progress.completed > 0
                    progressValue = null
                }
                "numeric", "duration" -> {
                    completed = progress.user_progress.completed >= (r.target_value ?: 0.0)
                    progressValue = progress.user_progress.completed.toInt()
                }
                else -> {
                    completed = false
                    progressValue = null
                }
            }
        } else {
            completed = false
            progressValue = null
        }

        return TodayGoal(
            goal_id = r.id,
            title = r.title,
            completed = completed,
            progress_value = progressValue,
            target_value = targetValue,
            metric_type = r.metric_type,
            unit = r.unit,
            icon_emoji = null
        )
    }

    /**
     * Get user's progress data.
     * 
     * @param accessToken JWT access token for authentication
     * @param startDate Optional start date (ISO format YYYY-MM-DD)
     * @param endDate Optional end date (ISO format YYYY-MM-DD)
     * @return MyProgressResponse with streak, weekly activity, heatmap, and goal breakdown
     * @throws ApiException on error
     */
    suspend fun getMyProgress(
        accessToken: String,
        startDate: String? = null,
        endDate: String? = null
    ): MyProgressResponse {
        val urlBuilder = StringBuilder("$baseUrl/users/me/progress")
        val params = mutableListOf<String>()
        if (startDate != null) params.add("start_date=$startDate")
        if (endDate != null) params.add("end_date=$endDate")
        if (params.isNotEmpty()) {
            urlBuilder.append("?").append(params.joinToString("&"))
        }
        
        val request = Request.Builder()
            .url(urlBuilder.toString())
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, MyProgressResponse::class.java)
        }
    }

    /**
     * Upload user avatar image.
     * 
     * @param accessToken JWT access token for authentication
     * @param imageFile File containing the image to upload
     * @return UploadAvatarResponse with has_avatar flag
     * @throws ApiException on error
     */
    suspend fun uploadAvatar(
        accessToken: String,
        imageFile: File
    ): UploadAvatarResponse {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "avatar",
                "avatar.jpg",
                imageFile.asRequestBody("image/jpeg".toMediaType())
            )
            .build()
        
        val request = Request.Builder()
            .url("$baseUrl/users/me/avatar")
            .post(requestBody)
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, UploadAvatarResponse::class.java)
        }
    }

    /**
     * Get user avatar image as binary data.
     * 
     * @param accessToken JWT access token for authentication
     * @param userId User ID to fetch avatar for
     * @return ResponseBody containing binary image data, or null if 404
     * @throws ApiException on error (except 404)
     */
    suspend fun getAvatar(
        accessToken: String,
        userId: String
    ): ResponseBody? {
        val request = Request.Builder()
            .url("$baseUrl/users/$userId/avatar")
            .get()
            .build()
        
        return try {
            val response = getClient().newCall(request).execute()
            if (response.code == 404) {
                response.close()
                null
            } else if (!response.isSuccessful) {
                response.close()
                throw ApiException(response.code, "Failed to fetch avatar: ${response.message}")
            } else {
                response.body
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(0, "Network error: ${e.message}")
        }
    }

    /**
     * Delete user avatar.
     * 
     * @param accessToken JWT access token for authentication
     * @return DeleteAvatarResponse with has_avatar = false
     * @throws ApiException on error
     */
    suspend fun deleteAvatar(
        accessToken: String
    ): DeleteAvatarResponse {
        val request = Request.Builder()
            .url("$baseUrl/users/me/avatar")
            .delete()
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, DeleteAvatarResponse::class.java)
        }
    }

    /**
     * Get group icon image as binary data.
     * 
     * @param accessToken JWT access token for authentication
     * @param groupId Group ID to fetch icon for
     * @return ResponseBody containing binary image data, or null if 404
     * @throws ApiException on error (except 404)
     */
    suspend fun getGroupIcon(
        accessToken: String,
        groupId: String
    ): ResponseBody? {
        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId/icon")
            .get()
            .build()
        
        return try {
            val response = getClient().newCall(request).execute()
            if (response.code == 404) {
                response.close()
                null
            } else if (!response.isSuccessful) {
                response.close()
                throw ApiException(response.code, "Failed to fetch group icon: ${response.message}")
            } else {
                response.body
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(0, "Network error: ${e.message}")
        }
    }

    /**
     * Upload group icon image (admin or creator only).
     *
     * PATCH /api/groups/:group_id/icon with multipart form field "icon".
     * Server clears icon_emoji and icon_color when storing image.
     *
     * @param accessToken JWT access token for authentication
     * @param groupId Group ID to upload icon for
     * @param imageFile Image file (PNG, JPG, or WebP; max 5 MB)
     * @return PatchGroupIconResponse with has_icon true, icon_emoji/icon_color null
     * @throws ApiException on error (403 if not admin/creator, 400 if invalid file)
     */
    suspend fun uploadGroupIcon(
        accessToken: String,
        groupId: String,
        imageFile: File
    ): PatchGroupIconResponse {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "icon",
                "icon.jpg",
                imageFile.asRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId/icon")
            .patch(requestBody)
            .build()

        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, PatchGroupIconResponse::class.java)
        }
    }

    /**
     * Upload a photo attachment to a progress entry.
     *
     * POST /api/progress/:progress_entry_id/photo
     * Server constraints: 800 KB max, JPEG/WebP only, 15-min edit window, one photo per entry.
     *
     * @param accessToken JWT access token for authentication
     * @param progressEntryId Progress entry ID to attach photo to
     * @param imageFile Image file (JPEG or WebP, max 800 KB)
     * @param width Image width in pixels (metadata)
     * @param height Image height in pixels (metadata)
     * @return UploadProgressPhotoResponse with photo_id and expires_at
     * @throws ApiException on error (400 file too large, 404 entry not found, 409 photo exists)
     */
    suspend fun uploadProgressPhoto(
        accessToken: String,
        progressEntryId: String,
        imageFile: File,
        width: Int,
        height: Int
    ): UploadProgressPhotoResponse {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "photo",
                "photo.jpg",
                imageFile.asRequestBody("image/jpeg".toMediaType())
            )
            .addFormDataPart("width", width.toString())
            .addFormDataPart("height", height.toString())
            .build()

        val request = Request.Builder()
            .url("$baseUrl/progress/$progressEntryId/photo")
            .post(requestBody)
            .build()

        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, UploadProgressPhotoResponse::class.java)
        }
    }

    /**
     * Get progress entry photo signed URL.
     *
     * GET /api/progress/:progress_entry_id/photo
     * User must be a member of the goal's group.
     *
     * @param accessToken JWT access token for authentication
     * @param progressEntryId Progress entry ID
     * @return GetProgressPhotoResponse with signed URL and dimensions
     * @throws ApiException on error (403 not member, 404 no photo)
     */
    suspend fun getProgressPhoto(
        accessToken: String,
        progressEntryId: String
    ): GetProgressPhotoResponse {
        val request = Request.Builder()
            .url("$baseUrl/progress/$progressEntryId/photo")
            .get()
            .build()

        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, GetProgressPhotoResponse::class.java)
        }
    }

    /**
     * Create a new group.
     * 
     * @param accessToken JWT access token for authentication
     * @param name Group name (required, 1-100 characters)
     * @param description Optional group description (max 500 characters)
     * @param iconEmoji Optional emoji icon (single emoji character)
     * @param iconColor Optional hex color code for emoji background (e.g., "#1976D2")
     * @return CreateGroupResponse with created group data
     * @throws ApiException on error
     */
    suspend fun createGroup(
        accessToken: String,
        name: String,
        description: String? = null,
        iconEmoji: String? = null,
        iconColor: String? = null
    ): CreateGroupResponse {
        val requestBody = gson.toJson(
            CreateGroupRequest(
                name = name,
                description = description,
                icon_emoji = iconEmoji,
                icon_color = iconColor
            )
        ).toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$baseUrl/groups")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, CreateGroupResponse::class.java)
        }
    }

    /**
     * Get group details.
     * 
     * @param accessToken JWT access token for authentication
     * @param groupId Group ID to fetch details for
     * @return GroupDetailResponse with group details
     * @throws ApiException on error
     */
    suspend fun getGroupDetails(
        accessToken: String,
        groupId: String
    ): GroupDetailResponse {
        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId")
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, GroupDetailResponse::class.java)
        }
    }

    /**
     * Update group metadata (admin or creator only). PATCH /api/groups/:group_id.
     * Only non-null fields are sent.
     */
    suspend fun patchGroup(
        accessToken: String,
        groupId: String,
        name: String? = null,
        description: String? = null,
        iconEmoji: String? = null,
        iconColor: String? = null
    ): PatchGroupResponse {
        val toSend = JsonObject()
        name?.let { toSend.addProperty("name", it) }
        description?.let { toSend.addProperty("description", it) }
        iconEmoji?.let { toSend.addProperty("icon_emoji", it) }
        iconColor?.let { toSend.addProperty("icon_color", it) }
        val requestBody = toSend.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId")
            .patch(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, PatchGroupResponse::class.java)
        }
    }

    /**
     * Delete group (creator only). DELETE /api/groups/:group_id. Returns 204 No Content.
     */
    suspend fun deleteGroup(accessToken: String, groupId: String) {
        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId")
            .delete()
            .addHeader("Content-Type", "application/json")
            .build()

        executeRequest<Unit>(request, getClient()) { Unit }
    }

    /**
     * Leave group (self-removal). DELETE /api/groups/:group_id/members/me. Returns 204 No Content.
     */
    suspend fun leaveGroup(accessToken: String, groupId: String) {
        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId/members/me")
            .delete()
            .addHeader("Content-Type", "application/json")
            .build()

        executeRequest<Unit>(request, getClient()) { Unit }
    }

    /**
     * Get the current active invite code for a group.
     * GET /api/groups/:group_id/invite
     * User must be a member of the group.
     */
    suspend fun getGroupInviteCode(accessToken: String, groupId: String): GetInviteCodeResponse {
        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId/invite")
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, GetInviteCodeResponse::class.java)
        }
    }

    /**
     * Regenerate invite code for a group (admin or creator only).
     * POST /api/groups/:group_id/invite/regenerate
     * Revokes old code and creates a new one.
     */
    suspend fun regenerateInviteCode(accessToken: String, groupId: String): RegenerateInviteResponse {
        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId/invite/regenerate")
            .post("{}".toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .build()
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, RegenerateInviteResponse::class.java)
        }
    }

    /**
     * Join group via invite code. POST /api/groups/join
     */
    suspend fun joinGroup(accessToken: String, inviteCode: String): JoinGroupResponse {
        val body = gson.toJson(JoinGroupRequest(invite_code = inviteCode))
        val request = Request.Builder()
            .url("$baseUrl/groups/join")
            .post(body.toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .build()
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, JoinGroupResponse::class.java)
        }
    }

    /**
     * Returns the app invite base URL for join links (e.g. https://getpursue.app/join/{code}).
     * Use share_url from API responses when available.
     */
    fun getInviteBaseUrl(): String = "https://getpursue.app"

    /**
     * Get group members.
     * 
     * @param accessToken JWT access token for authentication
     * @param groupId Group ID to fetch members for
     * @return GroupMembersResponse with list of members
     * @throws ApiException on error
     */
    suspend fun getGroupMembers(
        accessToken: String,
        groupId: String
    ): GroupMembersResponse {
        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId/members")
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, GroupMembersResponse::class.java)
        }
    }

    /**
     * Get pending join requests (admin only).
     *
     * @param accessToken JWT access token for authentication
     * @param groupId Group ID to fetch pending members for
     * @return PendingMembersResponse with list of pending members
     * @throws ApiException on error (e.g. 403 if not admin)
     */
    suspend fun getPendingMembers(
        accessToken: String,
        groupId: String
    ): PendingMembersResponse {
        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId/members/pending")
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, PendingMembersResponse::class.java)
        }
    }

    /**
     * Approve a pending member (admin only).
     *
     * @param accessToken JWT access token for authentication
     * @param groupId Group ID
     * @param userId User ID of the pending member to approve
     * @throws ApiException on error (e.g. 404 if no pending request)
     */
    suspend fun approveMember(
        accessToken: String,
        groupId: String,
        userId: String
    ) {
        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId/members/$userId/approve")
            .post("".toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .build()
        executeRequest<Unit>(request, getClient()) { Unit }
    }

    /**
     * Decline a pending member (admin only).
     *
     * @param accessToken JWT access token for authentication
     * @param groupId Group ID
     * @param userId User ID of the pending member to decline
     * @throws ApiException on error (e.g. 404 if no pending request)
     */
    suspend fun declineMember(
        accessToken: String,
        groupId: String,
        userId: String
    ) {
        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId/members/$userId/decline")
            .post("".toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .build()
        executeRequest<Unit>(request, getClient()) { Unit }
    }

    /**
     * Update member role (admin or creator only). PATCH /api/groups/:group_id/members/:user_id.
     * Client use: Promote to Admin by sending role "admin".
     *
     * @param accessToken JWT access token
     * @param groupId Group ID
     * @param userId User ID of the member to update
     * @param role New role (e.g. "admin")
     * @throws ApiException on error (400, 403, 404)
     */
    suspend fun updateMemberRole(
        accessToken: String,
        groupId: String,
        userId: String,
        role: String
    ) {
        val body = """{"role":"$role"}""".trimIndent().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId/members/$userId")
            .patch(body)
            .addHeader("Content-Type", "application/json")
            .build()
        executeRequest<Unit>(request, getClient()) { Unit }
    }

    /**
     * Remove member from group (admin or creator only). DELETE /api/groups/:group_id/members/:user_id.
     * Returns 204 No Content.
     *
     * @param accessToken JWT access token
     * @param groupId Group ID
     * @param userId User ID of the member to remove
     * @throws ApiException on error (400, 403, 404)
     */
    suspend fun removeMember(
        accessToken: String,
        groupId: String,
        userId: String
    ) {
        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId/members/$userId")
            .delete()
            .addHeader("Content-Type", "application/json")
            .build()
        executeRequest<Unit>(request, getClient()) { Unit }
    }

    /**
     * Get group activity feed.
     * 
     * @param accessToken JWT access token for authentication
     * @param groupId Group ID to fetch activity for
     * @param limit Maximum number of activities to return (default 50, max 100)
     * @param offset Number of activities to skip (default 0)
     * @return GroupActivityResponse with list of activities
     * @throws ApiException on error
     */
    suspend fun getGroupActivity(
        accessToken: String,
        groupId: String,
        limit: Int = 50,
        offset: Int = 0
    ): GroupActivityResponse {
        val url = "$baseUrl/groups/$groupId/activity?limit=$limit&offset=$offset"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, GroupActivityResponse::class.java)
        }
    }

    // --- Reaction Endpoints ---

    /**
     * Add or replace a reaction on an activity.
     *
     * PUT /api/activities/:activity_id/reactions
     * Replaces any existing reaction from the current user with the new emoji.
     *
     * @param accessToken JWT access token for authentication
     * @param activityId Activity ID to react to
     * @param emoji Reaction emoji (one of: 🔥, 💪, ❤️, 👏, 🤩, 😂)
     * @return AddReactionResponse with reaction details and replaced flag
     * @throws ApiException on error (400 invalid emoji, 403 not member, 404 activity not found)
     */
    suspend fun addOrReplaceReaction(
        accessToken: String,
        activityId: String,
        emoji: String
    ): AddReactionResponse {
        val body = mapOf("emoji" to emoji)
        val request = Request.Builder()
            .url("$baseUrl/activities/$activityId/reactions")
            .put(gson.toJson(body).toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .build()
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, AddReactionResponse::class.java)
        }
    }

    /**
     * Remove the current user's reaction from an activity.
     *
     * DELETE /api/activities/:activity_id/reactions
     * Returns 204 No Content on success.
     *
     * @param accessToken JWT access token for authentication
     * @param activityId Activity ID to remove reaction from
     * @throws ApiException on error (403 not member, 404 no reaction or activity not found)
     */
    suspend fun removeReaction(accessToken: String, activityId: String) {
        val request = Request.Builder()
            .url("$baseUrl/activities/$activityId/reactions")
            .delete()
            .addHeader("Content-Type", "application/json")
            .build()
        executeRequest<Unit>(request, getClient()) { Unit }
    }

    /**
     * Get all reactions for an activity.
     *
     * GET /api/activities/:activity_id/reactions
     *
     * @param accessToken JWT access token for authentication
     * @param activityId Activity ID to get reactions for
     * @return GetReactionsResponse with list of all reactors
     * @throws ApiException on error (403 not member, 404 activity not found)
     */
    suspend fun getReactions(
        accessToken: String,
        activityId: String
    ): GetReactionsResponse {
        val request = Request.Builder()
            .url("$baseUrl/activities/$activityId/reactions")
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, GetReactionsResponse::class.java)
        }
    }

    /**
     * Export group progress as Excel workbook.
     * GET /api/groups/:group_id/export-progress?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD&user_timezone=IANA
     * User must be an approved (active) member. Max date range 24 months.
     *
     * @param accessToken JWT access token for authentication
     * @param groupId Group ID to export progress for
     * @param startDate Start date (YYYY-MM-DD)
     * @param endDate End date (YYYY-MM-DD)
     * @param userTimezone IANA timezone (e.g. America/New_York)
     * @return ByteArray containing the xlsx file
     * @throws ApiException on error (400 validation, 403, 404, 429 rate limit)
     */
    suspend fun exportGroupProgress(
        accessToken: String,
        groupId: String,
        startDate: String,
        endDate: String,
        userTimezone: String
    ): ByteArray {
        val encodedParams = listOf(
            "start_date=$startDate",
            "end_date=$endDate",
            "user_timezone=${URLEncoder.encode(userTimezone, "UTF-8")}"
        ).joinToString("&")
        val url = "$baseUrl/groups/$groupId/export-progress?$encodedParams"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        return try {
            getClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.e("ApiClient", "Export progress failed: ${response.code} - ${response.message}")
                    val (errorMessage, errorCode) = try {
                        val wrapper = gson.fromJson(responseBody, ErrorWrapper::class.java)
                        val msg = wrapper.error?.message ?: "Export failed with code ${response.code}"
                        val code = wrapper.error?.code
                        Pair(msg, code)
                    } catch (e: Exception) {
                        try {
                            val flat = gson.fromJson(responseBody, ErrorResponse::class.java)
                            Pair(flat.message ?: "Export failed with code ${response.code}", null)
                        } catch (e2: Exception) {
                            Pair("Export failed with code ${response.code}", null)
                        }
                    }
                    throw ApiException(response.code, errorMessage, errorCode)
                }
                response.body?.bytes() ?: throw ApiException(0, "Empty response body")
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            Log.e("ApiClient", "Export progress network error", e)
            throw ApiException(0, "Network error: ${e.message}")
        }
    }

    /**
     * Get all goals for a group, optionally with current period progress.
     *
     * @param accessToken JWT access token for authentication
     * @param groupId Group ID to fetch goals for
     * @param cadence Optional filter by cadence ('daily', 'weekly', 'monthly', 'yearly')
     * @param archived Include archived goals (default: false, only active goals)
     * @param includeProgress Include current period progress data (default: true)
     * @param userTimezone IANA timezone (e.g., "America/New_York") for accurate period calculation
     * @return GroupGoalsResponse with list of goals and progress information
     * @throws ApiException on error
     */
    suspend fun getGroupGoals(
        accessToken: String,
        groupId: String,
        cadence: String? = null,
        archived: Boolean = false,
        includeProgress: Boolean = true,
        userTimezone: String? = null
    ): GroupGoalsResponse {
        val urlBuilder = StringBuilder("$baseUrl/groups/$groupId/goals")
        val params = mutableListOf<String>()
        if (cadence != null) params.add("cadence=$cadence")
        if (archived) params.add("archived=true")
        if (includeProgress) params.add("include_progress=true")
        if (userTimezone != null) params.add("user_timezone=${URLEncoder.encode(userTimezone, "UTF-8")}")
        if (params.isNotEmpty()) {
            urlBuilder.append("?").append(params.joinToString("&"))
        }
        
        val request = Request.Builder()
            .url(urlBuilder.toString())
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        
        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, GroupGoalsResponse::class.java)
        }
    }

    // --- Goal Endpoints (3.4) ---

    /**
     * Create a goal in a group (admin or creator only).
     *
     * @param accessToken JWT access token for authentication
     * @param groupId Group ID to create the goal in
     * @param title Goal title (1-200 characters, required)
     * @param description Optional description (max 1000 characters)
     * @param cadence One of: 'daily', 'weekly', 'monthly', 'yearly'
     * @param metricType One of: 'binary', 'numeric', 'duration'
     * @param targetValue Required for numeric, optional for binary/duration
     * @param unit Optional, max 50 characters (e.g. "pages")
     * @return CreateGoalResponse (201 Created)
     * @throws ApiException on error (400 validation, 401, 403)
     */
    suspend fun createGoal(
        accessToken: String,
        groupId: String,
        title: String,
        description: String? = null,
        cadence: String,
        metricType: String,
        targetValue: Double? = null,
        unit: String? = null
    ): CreateGoalResponse {
        val requestBody = gson.toJson(
            CreateGoalRequest(
                title = title,
                description = description,
                cadence = cadence,
                metric_type = metricType,
                target_value = targetValue,
                unit = unit
            )
        ).toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/groups/$groupId/goals")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, CreateGoalResponse::class.java)
        }
    }

    /**
     * Get goal details. User must be a member of the goal's group.
     *
     * @param accessToken JWT access token for authentication
     * @param goalId Goal ID
     * @return GetGoalResponse (200 OK)
     * @throws ApiException on error (401, 403, 404)
     */
    suspend fun getGoal(accessToken: String, goalId: String): GetGoalResponse {
        val request = Request.Builder()
            .url("$baseUrl/goals/$goalId")
            .get()
            .addHeader("Content-Type", "application/json")
            .build()

        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, GetGoalResponse::class.java)
        }
    }

    /**
     * Update a goal (admin or creator only). Partial update: only non-null fields are sent.
     *
     * @param accessToken JWT access token for authentication
     * @param goalId Goal ID
     * @param title New title (optional)
     * @param description New description (optional)
     * @return UpdateGoalResponse (200 OK)
     * @throws ApiException on error (401, 403, 404)
     */
    suspend fun updateGoal(
        accessToken: String,
        goalId: String,
        title: String? = null,
        description: String? = null
    ): UpdateGoalResponse {
        val toSend = JsonObject()
        title?.let { toSend.addProperty("title", it) }
        description?.let { toSend.addProperty("description", it) }
        val requestBody = toSend.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/goals/$goalId")
            .patch(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, UpdateGoalResponse::class.java)
        }
    }

    /**
     * Soft-delete a goal (admin or creator only). Returns 204 No Content.
     *
     * @param accessToken JWT access token for authentication
     * @param goalId Goal ID
     * @throws ApiException on error (401, 403, 404)
     */
    suspend fun deleteGoal(accessToken: String, goalId: String) {
        val request = Request.Builder()
            .url("$baseUrl/goals/$goalId")
            .delete()
            .addHeader("Content-Type", "application/json")
            .build()

        executeRequest<Unit>(request, getClient()) { Unit }
    }

    /**
     * Get all users' progress for a goal.
     *
     * @param accessToken JWT access token for authentication
     * @param goalId Goal ID
     * @param startDate Optional ISO date (YYYY-MM-DD)
     * @param endDate Optional ISO date (YYYY-MM-DD)
     * @return GoalProgressResponse (200 OK)
     * @throws ApiException on error (401, 403, 404)
     */
    suspend fun getGoalProgress(
        accessToken: String,
        goalId: String,
        startDate: String? = null,
        endDate: String? = null
    ): GoalProgressResponse {
        val params = mutableListOf<String>()
        startDate?.let { params.add("start_date=$it") }
        endDate?.let { params.add("end_date=$it") }
        val q = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val request = Request.Builder()
            .url("$baseUrl/goals/$goalId/progress$q")
            .get()
            .addHeader("Content-Type", "application/json")
            .build()

        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, GoalProgressResponse::class.java)
        }
    }

    /**
     * Get current user's progress for a goal.
     *
     * @param accessToken JWT access token for authentication
     * @param goalId Goal ID
     * @param startDate Optional ISO date (YYYY-MM-DD)
     * @param endDate Optional ISO date (YYYY-MM-DD)
     * @return GoalProgressMeResponse (200 OK)
     * @throws ApiException on error (401, 403, 404)
     */
    suspend fun getGoalProgressMe(
        accessToken: String,
        goalId: String,
        startDate: String? = null,
        endDate: String? = null
    ): GoalProgressMeResponse {
        val params = mutableListOf<String>()
        startDate?.let { params.add("start_date=$it") }
        endDate?.let { params.add("end_date=$it") }
        val q = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val request = Request.Builder()
            .url("$baseUrl/goals/$goalId/progress/me$q")
            .get()
            .addHeader("Content-Type", "application/json")
            .build()

        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, GoalProgressMeResponse::class.java)
        }
    }

    /**
     * Log progress entry.
     *
     * @param accessToken JWT access token for authentication
     * @param goalId Goal ID to log progress for
     * @param value Progress value (0 or 1 for binary, numeric value for numeric/duration)
     * @param note Optional note (max 500 characters)
     * @param userDate User's local date in ISO format (YYYY-MM-DD)
     * @param userTimezone IANA timezone string (e.g., "America/New_York")
     * @return LogProgressResponse with created entry details
     * @throws ApiException on error (400 validation, 401, 403, 404)
     */
    suspend fun logProgress(
        accessToken: String,
        goalId: String,
        value: Double,
        note: String? = null,
        userDate: String,
        userTimezone: String
    ): LogProgressResponse {
        val requestBody = gson.toJson(
            LogProgressRequest(
                goal_id = goalId,
                value = value,
                note = note,
                user_date = userDate,
                user_timezone = userTimezone
            )
        ).toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/progress")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, LogProgressResponse::class.java)
        }
    }

    /**
     * Delete a progress entry (own entries only).
     *
     * @param accessToken JWT access token for authentication
     * @param entryId Progress entry ID
     * @throws ApiException on error (401, 403, 404)
     */
    suspend fun deleteProgressEntry(accessToken: String, entryId: String) {
        val request = Request.Builder()
            .url("$baseUrl/progress/$entryId")
            .delete()
            .addHeader("Content-Type", "application/json")
            .build()

        executeRequest<Unit>(request, getClient()) { Unit }
    }

    /**
     * Delete the current user's account (hard delete).
     *
     * @param accessToken JWT access token for authentication
     * @param confirmation Must be "delete" (case-insensitive)
     * @throws ApiException on error (400, 401)
     */
    suspend fun deleteAccount(accessToken: String, confirmation: String) {
        val json = """{"confirmation":"$confirmation"}"""
        val body = json.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/users/me")
            .delete(body)
            .addHeader("Content-Type", "application/json")
            .build()

        executeRequest<Unit>(request, getClient()) { Unit }
    }

    suspend fun getMyConsents(accessToken: String): UserConsentsResponse {
        val request = Request.Builder()
            .url("$baseUrl/users/me/consents")
            .get()
            .build()

        return executeRequest(request, getClient()) { responseBody ->
            gson.fromJson(responseBody, UserConsentsResponse::class.java)
        }
    }

    suspend fun recordConsents(accessToken: String, consentTypes: List<String>) {
        val requestBody = gson.toJson(RecordConsentsRequest(consentTypes))
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/users/me/consents")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        executeRequest<Unit>(request, getClient()) { Unit }
    }

    /**
     * Execute an HTTP request and parse the response.
     */
    private suspend fun <T> executeRequest(
        request: Request,
        client: OkHttpClient,
        parseResponse: (String) -> T
    ): T {
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                
                if (!response.isSuccessful) {
                    // Log error response for debugging
                    Log.e("ApiClient", "Request failed: ${response.code} - ${response.message}")
                    Log.e("ApiClient", "Response body: $responseBody")

                    // Backend returns nested { "error": { "message": "...", "code": "PENDING_APPROVAL" } }
                    val (errorMessage, errorCode) = try {
                        val wrapper = gson.fromJson(responseBody, ErrorWrapper::class.java)
                        val msg = wrapper.error?.message ?: "Request failed with code ${response.code}"
                        val code = wrapper.error?.code
                        Pair(msg, code)
                    } catch (e: Exception) {
                        try {
                            val flat = gson.fromJson(responseBody, ErrorResponse::class.java)
                            Pair(flat.message ?: "Request failed with code ${response.code}", null)
                        } catch (e2: Exception) {
                            Pair("Request failed with code ${response.code}", null)
                        }
                    }
                    throw ApiException(response.code, errorMessage, errorCode)
                }
                
                parseResponse(responseBody)
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            Log.e("ApiClient", "Network error", e)
            throw ApiException(0, "Network error: ${e.message}")
        }
    }
}

/**
 * Request model for registration.
 */
data class RegisterRequest(
    val display_name: String,
    val email: String,
    val password: String,
    val consent_agreed: Boolean,
    val consent_terms_version: String? = null,
    val consent_privacy_version: String? = null
)

/**
 * Request model for login.
 */
data class LoginRequest(
    val email: String,
    val password: String
)

/**
 * Response model for registration.
 */
data class RegistrationResponse(
    val access_token: String,
    val refresh_token: String,
    val user: User?
)

/**
 * Response model for login (same structure as registration).
 */
data class LoginResponse(
    val access_token: String,
    val refresh_token: String,
    val user: User?
)

/**
 * Request model for Google Sign-In.
 */
data class GoogleSignInRequest(
    val id_token: String,
    val consent_agreed: Boolean? = null,
    val consent_terms_version: String? = null,
    val consent_privacy_version: String? = null
)

/**
 * Response model for Google Sign-In.
 */
data class GoogleSignInResponse(
    val access_token: String,
    val refresh_token: String,
    val is_new_user: Boolean,
    val user: User?
)

/**
 * Request model for device registration.
 */
data class DeviceRegistrationRequest(
    val fcm_token: String,
    val device_name: String,
    val platform: String
)

/**
 * Response model for device registration.
 */
data class DeviceRegistrationResponse(
    val id: String,
    val device_name: String,
    val platform: String
)

data class User(
    val id: String,
    val email: String,
    val display_name: String,
    val has_avatar: Boolean, // true if user has an avatar image (BYTEA), false otherwise
    val updated_at: String? // ISO 8601 timestamp for cache invalidation
)

/**
 * Error response model (flat, legacy).
 */
data class ErrorResponse(
    val message: String?,
    val error: String?
)

/**
 * Nested error format from backend: { "error": { "message": "...", "code": "PENDING_APPROVAL" } }
 */
data class ErrorWrapper(val error: ErrorDetail?)

data class ErrorDetail(
    val message: String?,
    val code: String?
)

/**
 * Response model for avatar upload.
 */
data class UploadAvatarResponse(
    val has_avatar: Boolean
)

/**
 * Response model for avatar delete.
 */
data class DeleteAvatarResponse(
    val has_avatar: Boolean
)

// --- Subscription (pursue-subscription-spec) ---

/** GET /api/users/me/subscription */
data class SubscriptionState(
    val tier: String,
    val status: String,
    val group_limit: Int,
    val current_group_count: Int,
    val groups_remaining: Int,
    val is_over_limit: Boolean,
    val subscription_expires_at: String?,
    val auto_renew: Boolean?,
    val can_create_group: Boolean,
    val can_join_group: Boolean
)

/** GET /api/users/me/subscription/eligibility */
data class SubscriptionEligibility(
    val can_create_group: Boolean,
    val can_join_group: Boolean,
    val reason: String?,
    val current_count: Int,
    val limit: Int,
    val upgrade_required: Boolean
)

/** GET /api/groups/:group_id/export-progress/validate-range (200 or 400) */
data class ValidateExportRangeResponse(
    val valid: Boolean,
    val max_days_allowed: Int,
    val requested_days: Int,
    val subscription_tier: String,
    val error: String? = null,
    val message: String? = null
)

/** POST /api/subscriptions/downgrade/select-group */
data class DowngradeSelectGroupResponse(
    val status: String,
    val kept_group: DowngradeGroupInfo,
    val removed_groups: List<DowngradeGroupInfo>,
    val read_only_access_until: String
)
data class DowngradeGroupInfo(val id: String, val name: String)

/** POST /api/subscriptions/upgrade request */
data class UpgradeSubscriptionRequest(
    val platform: String,
    val purchase_token: String,
    val product_id: String
)

/** POST /api/subscriptions/upgrade response */
data class UpgradeSubscriptionResponse(
    val subscription_id: String,
    val tier: String,
    val status: String,
    val expires_at: String,
    val group_limit: Int
)

// --- Consents ---

data class UserConsentsResponse(val consents: List<UserConsentEntry>)
data class UserConsentEntry(val consent_type: String, val agreed_at: String)
data class RecordConsentsRequest(val consent_types: List<String>)

/**
 * Request model for token refresh.
 */
data class RefreshTokenRequest(
    val refresh_token: String
)

/**
 * Response model for token refresh.
 */
data class RefreshTokenResponse(
    val access_token: String,
    val refresh_token: String?  // Nullable since backend may not return it (refresh token is reused, not rotated)
)

/**
 * Request model for creating a group.
 */
data class CreateGroupRequest(
    val name: String,
    val description: String? = null,
    val icon_emoji: String? = null,
    val icon_color: String? = null
)

/**
 * Response model for creating a group.
 */
data class CreateGroupResponse(
    val id: String,
    val name: String,
    val description: String?,
    val icon_emoji: String?,
    val icon_color: String?,
    val has_icon: Boolean,
    val creator_user_id: String,
    val member_count: Int,
    val created_at: String
)

/**
 * Response from PATCH /api/groups/:group_id (subset of group fields).
 */
data class PatchGroupResponse(
    val id: String,
    val name: String,
    val description: String?,
    val icon_emoji: String?,
    val icon_color: String?,
    val has_icon: Boolean,
    val updated_at: String
)

/**
 * Response from PATCH /api/groups/:group_id/icon (upload group icon image).
 */
data class PatchGroupIconResponse(
    val id: String,
    val has_icon: Boolean,
    val icon_emoji: String?,
    val icon_color: String?,
    val updated_at: String
)

/** Response from GET /api/groups/:group_id/invite */
data class GetInviteCodeResponse(
    val invite_code: String,
    val share_url: String,
    val created_at: String
)

/** Response from POST /api/groups/:group_id/invite/regenerate */
data class RegenerateInviteResponse(
    val invite_code: String,
    val share_url: String,
    val created_at: String,
    val previous_code_revoked: String? = null
)

data class JoinGroupRequest(
    val invite_code: String
)

data class JoinGroupResponse(
    val status: String,
    val message: String,
    val group: JoinGroupResponseGroup
)

data class JoinGroupResponseGroup(
    val id: String,
    val name: String,
    val member_count: Int
)

// --- Goal DTOs (3.4) ---

data class CreateGoalRequest(
    val title: String,
    val description: String? = null,
    val cadence: String,
    val metric_type: String,
    val target_value: Double? = null,
    val unit: String? = null
)

data class CreateGoalResponse(
    val id: String,
    val group_id: String,
    val title: String,
    val description: String?,
    val cadence: String,
    val metric_type: String,
    val target_value: Double?,
    val unit: String?,
    val created_by_user_id: String,
    val created_at: String,
    val archived_at: String?
)

data class GetGoalResponse(
    val id: String,
    val group_id: String,
    val title: String,
    val description: String?,
    val cadence: String,
    val metric_type: String,
    val created_at: String
)

data class UpdateGoalResponse(
    val id: String,
    val title: String,
    val description: String?
)

data class GoalProgressResponse(
    val goal: GoalProgressGoal,
    val progress: List<GoalUserProgress>
)

data class GoalProgressGoal(
    val id: String,
    val title: String,
    val cadence: String
)

data class GoalUserProgress(
    val user_id: String,
    val display_name: String,
    val entries: List<GoalProgressEntry>
)

data class GoalProgressEntry(
    val id: String,
    val value: Double,
    val note: String?,
    val period_start: String,
    val logged_at: String,
    val photo: GoalProgressEntryPhoto? = null
)

data class GoalProgressEntryPhoto(
    val id: String,
    val url: String,
    val width: Int,
    val height: Int,
    val expires_at: String
)

data class GoalProgressMeResponse(
    val goal_id: String,
    val entries: List<GoalProgressEntry>
)

data class LogProgressRequest(
    val goal_id: String,
    val value: Double,
    val note: String? = null,
    val user_date: String, // ISO date YYYY-MM-DD
    val user_timezone: String // IANA timezone
)

data class LogProgressResponse(
    val id: String,
    val goal_id: String,
    val user_id: String,
    val value: Double,
    val note: String?,
    val period_start: String, // ISO date YYYY-MM-DD
    val logged_at: String // ISO 8601 timestamp
)

// --- Progress Photo DTOs ---

data class UploadProgressPhotoResponse(
    val photo_id: String,
    val expires_at: String
)

data class GetProgressPhotoResponse(
    val photo_id: String,
    val url: String,
    val width: Int,
    val height: Int,
    val expires_at: String
)

// --- Reaction DTOs ---

data class AddReactionResponse(
    val reaction: ReactionDetail,
    val replaced: Boolean
)

data class ReactionDetail(
    val activity_id: String,
    val user_id: String,
    val emoji: String,
    val created_at: String
)

data class GetReactionsResponse(
    val activity_id: String,
    val reactions: List<ReactorEntry>,
    val total: Int
)

data class ReactorEntry(
    val emoji: String,
    val user: ReactorUser,
    val created_at: String
)

data class ReactorUser(
    val id: String,
    val display_name: String,
    val avatar_url: String?
)

/**
 * Custom exception for API errors.
 * @param errorCode Backend error code when present (e.g. "PENDING_APPROVAL", "FORBIDDEN").
 */
class ApiException(val code: Int, message: String, val errorCode: String? = null) : Exception(message)
