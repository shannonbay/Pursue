import request from 'supertest';
import sharp from 'sharp';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  setUserPremium,
  recordPhotoUpload,
  createTestPhoto,
  randomEmail,
  addMemberToGroup,
} from '../../helpers';

// Mock GCS service
jest.mock('../../../src/services/gcs.service.js', () => ({
  buildObjectPath: jest.fn((userId: string, entryId: string) => `${userId}/2026/02/${entryId}.jpg`),
  uploadPhoto: jest.fn().mockResolvedValue(undefined),
  getSignedUrl: jest.fn().mockResolvedValue('https://storage.googleapis.com/signed-url-mock'),
  deleteUserPhotos: jest.fn().mockResolvedValue(undefined),
  deletePhoto: jest.fn().mockResolvedValue(undefined),
}));

// Helper function to create test images
async function createTestImage(
  format: 'jpeg' | 'webp' = 'jpeg',
  width: number = 100,
  height: number = 100
): Promise<Buffer> {
  return await sharp({
    create: {
      width,
      height,
      channels: 3,
      background: { r: 255, g: 0, b: 0 },
    },
  })
    [format]()
    .toBuffer();
}

// Helper to create a progress entry via API
async function createProgressEntry(
  accessToken: string,
  goalId: string,
  value: number = 1
): Promise<string> {
  const today = new Date().toISOString().slice(0, 10);
  const response = await request(app)
    .post('/api/progress')
    .set('Authorization', `Bearer ${accessToken}`)
    .send({
      goal_id: goalId!,
      value,
      user_date: today,
      user_timezone: 'Australia/Sydney',
    });

  if (response.status !== 201) {
    throw new Error(`Failed to create progress: ${JSON.stringify(response.body)}`);
  }
  return response.body.id;
}

describe('POST /api/progress/:progress_entry_id/photo', () => {
  describe('Happy Path', () => {
    it('should upload JPEG photo successfully', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);
      const entryId = await createProgressEntry(accessToken, goalId!);
      const testImage = await createTestImage('jpeg', 100, 75);

      const response = await request(app)
        .post(`/api/progress/${entryId}/photo`)
        .set('Authorization', `Bearer ${accessToken}`)
        .field('width', '100')
        .field('height', '75')
        .attach('photo', testImage, 'photo.jpg');

      expect(response.status).toBe(201);
      expect(response.body.photo_id).toBeDefined();
      expect(response.body.expires_at).toBeDefined();

      // Verify database state
      const photo = await testDb
        .selectFrom('progress_photos')
        .select(['id', 'width_px', 'height_px', 'gcs_object_path'])
        .where('progress_entry_id', '=', entryId)
        .executeTakeFirst();

      expect(photo).toBeDefined();
      expect(photo?.width_px).toBe(100);
      expect(photo?.height_px).toBe(75);

      // Verify upload log
      const logs = await testDb
        .selectFrom('photo_upload_log')
        .select('id')
        .where('user_id', '=', userId)
        .execute();

      expect(logs.length).toBe(1);
    });

    it('should upload WebP photo successfully', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);
      const entryId = await createProgressEntry(accessToken, goalId!);
      const testImage = await createTestImage('webp', 200, 150);

      const response = await request(app)
        .post(`/api/progress/${entryId}/photo`)
        .set('Authorization', `Bearer ${accessToken}`)
        .field('width', '200')
        .field('height', '150')
        .attach('photo', testImage, 'photo.webp');

      expect(response.status).toBe(201);
      expect(response.body.photo_id).toBeDefined();
    });
  });

  describe('Validation Errors', () => {
    it('should reject PNG files', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);
      const entryId = await createProgressEntry(accessToken, goalId!);

      // Create a PNG
      const pngImage = await sharp({
        create: { width: 10, height: 10, channels: 3, background: { r: 0, g: 0, b: 255 } },
      }).png().toBuffer();

      const response = await request(app)
        .post(`/api/progress/${entryId}/photo`)
        .set('Authorization', `Bearer ${accessToken}`)
        .field('width', '10')
        .field('height', '10')
        .attach('photo', pngImage, 'photo.png');

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('INVALID_FILE_TYPE');
    });

    it('should reject request without file', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);
      const entryId = await createProgressEntry(accessToken, goalId!);

      const response = await request(app)
        .post(`/api/progress/${entryId}/photo`)
        .set('Authorization', `Bearer ${accessToken}`)
        .field('width', '100')
        .field('height', '100');

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('MISSING_FILE');
    });

    it('should reject file over 500 KB', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);
      const entryId = await createProgressEntry(accessToken, goalId!);

      // Create a valid JPEG image first, then pad it to be over 500KB
      const baseImage = await sharp({
        create: { width: 100, height: 100, channels: 3, background: { r: 128, g: 64, b: 32 } },
      }).jpeg().toBuffer();

      // Create a buffer that's definitely over 500KB by padding
      // Multer checks size before processing, so this will trigger FILE_TOO_LARGE
      const largeImage = Buffer.concat([
        baseImage,
        Buffer.alloc(550 * 1024 - baseImage.length),
      ]);

      const response = await request(app)
        .post(`/api/progress/${entryId}/photo`)
        .set('Authorization', `Bearer ${accessToken}`)
        .field('width', '100')
        .field('height', '100')
        .attach('photo', largeImage, 'large.jpg');

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('FILE_TOO_LARGE');
    });
  });

  describe('Authorization', () => {
    it('should reject non-existent entry', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const testImage = await createTestImage('jpeg');
      const fakeEntryId = '00000000-0000-0000-0000-000000000000';

      const response = await request(app)
        .post(`/api/progress/${fakeEntryId}/photo`)
        .set('Authorization', `Bearer ${accessToken}`)
        .field('width', '100')
        .field('height', '100')
        .attach('photo', testImage, 'photo.jpg');

      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('NOT_FOUND');
    });

    it('should reject another user\'s entry', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const other = await createAuthenticatedUser(randomEmail());
      const { goalId, groupId } = await createGroupWithGoal(creator.accessToken);

      // Add other user to group
      await addMemberToGroup(creator.accessToken, groupId);

      // Create entry as creator
      const entryId = await createProgressEntry(creator.accessToken, goalId!);
      const testImage = await createTestImage('jpeg');

      // Try to upload photo as other user
      const response = await request(app)
        .post(`/api/progress/${entryId}/photo`)
        .set('Authorization', `Bearer ${other.accessToken}`)
        .field('width', '100')
        .field('height', '100')
        .attach('photo', testImage, 'photo.jpg');

      expect(response.status).toBe(403);
      expect(response.body.error.code).toBe('FORBIDDEN');
    });
  });

  describe('Edit Window', () => {
    it('should reject after edit window (15 minutes)', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);

      // Create entry directly in DB with old logged_at time
      const twentyMinutesAgo = new Date(Date.now() - 20 * 60 * 1000);
      const today = new Date().toISOString().slice(0, 10);

      const entry = await testDb
        .insertInto('progress_entries')
        .values({
          goal_id: goalId!,
          user_id: userId,
          value: 1,
          period_start: today,
          logged_at: twentyMinutesAgo.toISOString(),
        })
        .returning('id')
        .executeTakeFirstOrThrow();

      const testImage = await createTestImage('jpeg');

      const response = await request(app)
        .post(`/api/progress/${entry.id}/photo`)
        .set('Authorization', `Bearer ${accessToken}`)
        .field('width', '100')
        .field('height', '100')
        .attach('photo', testImage, 'photo.jpg');

      expect(response.status).toBe(403);
      expect(response.body.error.code).toBe('EDIT_WINDOW_EXPIRED');
    });
  });

  describe('Photo Already Exists', () => {
    it('should reject if entry already has photo', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);
      const entryId = await createProgressEntry(accessToken, goalId!);

      // Upload first photo
      const testImage1 = await createTestImage('jpeg');
      const response1 = await request(app)
        .post(`/api/progress/${entryId}/photo`)
        .set('Authorization', `Bearer ${accessToken}`)
        .field('width', '100')
        .field('height', '100')
        .attach('photo', testImage1, 'photo1.jpg');

      expect(response1.status).toBe(201);

      // Try to upload second photo
      const testImage2 = await createTestImage('jpeg');
      const response2 = await request(app)
        .post(`/api/progress/${entryId}/photo`)
        .set('Authorization', `Bearer ${accessToken}`)
        .field('width', '100')
        .field('height', '100')
        .attach('photo', testImage2, 'photo2.jpg');

      expect(response2.status).toBe(409);
      expect(response2.body.error.code).toBe('PHOTO_ALREADY_EXISTS');
    });
  });

  describe('Quota Enforcement', () => {
    it('should allow free user 3 uploads per week', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);

      // Upload 3 photos (need 3 different entries with unique period_start)
      for (let i = 0; i < 3; i++) {
        // Create entry with unique period start
        const date = new Date();
        date.setDate(date.getDate() - i - 7); // Use dates from last week to avoid duplicate period
        const periodStart = date.toISOString().slice(0, 10);

        const entry = await testDb
          .insertInto('progress_entries')
          .values({
            goal_id: goalId!,
            user_id: userId,
            value: 1,
            period_start: periodStart,
          })
          .returning('id')
          .executeTakeFirstOrThrow();

        const testImage = await createTestImage('jpeg');
        const response = await request(app)
          .post(`/api/progress/${entry.id}/photo`)
          .set('Authorization', `Bearer ${accessToken}`)
          .field('width', '100')
          .field('height', '100')
          .attach('photo', testImage, 'photo.jpg');

        expect(response.status).toBe(201);
      }

      // Verify 3 uploads logged
      const logs = await testDb
        .selectFrom('photo_upload_log')
        .select('id')
        .where('user_id', '=', userId)
        .execute();

      expect(logs.length).toBe(3);
    });

    it('should reject free user at quota with upgrade_required', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);

      // Pre-record 3 uploads in the last 7 days
      for (let i = 0; i < 3; i++) {
        await recordPhotoUpload(userId);
      }

      // Try to upload a 4th photo
      const entryId = await createProgressEntry(accessToken, goalId!);
      const testImage = await createTestImage('jpeg');

      const response = await request(app)
        .post(`/api/progress/${entryId}/photo`)
        .set('Authorization', `Bearer ${accessToken}`)
        .field('width', '100')
        .field('height', '100')
        .attach('photo', testImage, 'photo.jpg');

      expect(response.status).toBe(422);
      expect(response.body.error.code).toBe('QUOTA_EXCEEDED');
      expect(response.body.error.upgrade_required).toBe(true);
      expect(response.body.error.remaining).toBe(0);
      expect(response.body.error.limit).toBe(3);
    });

    it('should allow premium user more than 3 uploads', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      await setUserPremium(userId);
      const { goalId } = await createGroupWithGoal(accessToken);

      // Pre-record 5 uploads
      for (let i = 0; i < 5; i++) {
        await recordPhotoUpload(userId);
      }

      // Upload a 6th photo (should still be allowed for premium)
      const entryId = await createProgressEntry(accessToken, goalId!);
      const testImage = await createTestImage('jpeg');

      const response = await request(app)
        .post(`/api/progress/${entryId}/photo`)
        .set('Authorization', `Bearer ${accessToken}`)
        .field('width', '100')
        .field('height', '100')
        .attach('photo', testImage, 'photo.jpg');

      expect(response.status).toBe(201);
    });
  });
});

describe('GET /api/progress/:progress_entry_id/photo', () => {
  it('should return signed URL for photo', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(accessToken);
    const entryId = await createProgressEntry(accessToken, goalId!);

    // Create photo directly in DB
    await createTestPhoto(entryId, userId, { width: 1080, height: 810 });

    const response = await request(app)
      .get(`/api/progress/${entryId}/photo`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.photo_id).toBeDefined();
    expect(response.body.url).toBe('https://storage.googleapis.com/signed-url-mock');
    expect(response.body.width).toBe(1080);
    expect(response.body.height).toBe(810);
    expect(response.body.expires_at).toBeDefined();
  });

  it('should return 404 for entry without photo', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(accessToken);
    const entryId = await createProgressEntry(accessToken, goalId!);

    const response = await request(app)
      .get(`/api/progress/${entryId}/photo`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NO_PHOTO');
  });

  it('should return 410 for expired photo', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(accessToken);
    const entryId = await createProgressEntry(accessToken, goalId!);

    // Create expired photo
    const expiredDate = new Date(Date.now() - 24 * 60 * 60 * 1000); // Yesterday
    await createTestPhoto(entryId, userId, { expiresAt: expiredDate });

    const response = await request(app)
      .get(`/api/progress/${entryId}/photo`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(410);
    expect(response.body.error.code).toBe('PHOTO_EXPIRED');
  });

  it('should allow group member to view photo', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const { goalId, groupId } = await createGroupWithGoal(creator.accessToken);
    const entryId = await createProgressEntry(creator.accessToken, goalId!);

    // Create photo
    await createTestPhoto(entryId, creator.userId);

    // Add member to group
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);

    // Member should be able to view the photo
    const response = await request(app)
      .get(`/api/progress/${entryId}/photo`)
      .set('Authorization', `Bearer ${memberAccessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.url).toBeDefined();
  });

  it('should reject non-member from viewing photo', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const nonMember = await createAuthenticatedUser(randomEmail());
    const { goalId } = await createGroupWithGoal(creator.accessToken);
    const entryId = await createProgressEntry(creator.accessToken, goalId!);

    // Create photo
    await createTestPhoto(entryId, creator.userId);

    // Non-member should not be able to view
    const response = await request(app)
      .get(`/api/progress/${entryId}/photo`)
      .set('Authorization', `Bearer ${nonMember.accessToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('FORBIDDEN');
  });
});

describe('Activity Feed with Photos', () => {
  it('should include photo in activity feed', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(accessToken);

    // Create progress entry via API (which creates activity)
    const entryId = await createProgressEntry(accessToken, goalId!);

    // Create photo
    await createTestPhoto(entryId, userId);

    // Get activity feed
    const response = await request(app)
      .get(`/api/groups/${groupId}/activity`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.activities.length).toBeGreaterThan(0);

    // Find the progress_logged activity
    const progressActivity = response.body.activities.find(
      (a: any) => a.activity_type === 'progress_logged'
    );

    expect(progressActivity).toBeDefined();
    expect(progressActivity.photo).toBeDefined();
    expect(progressActivity.photo.url).toBe('https://storage.googleapis.com/signed-url-mock');
    expect(progressActivity.photo.width).toBe(1080);
    expect(progressActivity.photo.height).toBe(810);
  });

  it('should return null for expired photo in activity feed', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(accessToken);

    // Create progress entry
    const entryId = await createProgressEntry(accessToken, goalId!);

    // Create expired photo
    const expiredDate = new Date(Date.now() - 24 * 60 * 60 * 1000);
    await createTestPhoto(entryId, userId, { expiresAt: expiredDate });

    // Get activity feed
    const response = await request(app)
      .get(`/api/groups/${groupId}/activity`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);

    const progressActivity = response.body.activities.find(
      (a: any) => a.activity_type === 'progress_logged'
    );

    expect(progressActivity).toBeDefined();
    expect(progressActivity.photo).toBeNull();
  });

  it('should return null for activity without photo', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(accessToken);

    // Create progress entry without photo
    await createProgressEntry(accessToken, goalId!);

    // Get activity feed
    const response = await request(app)
      .get(`/api/groups/${groupId}/activity`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);

    const progressActivity = response.body.activities.find(
      (a: any) => a.activity_type === 'progress_logged'
    );

    expect(progressActivity).toBeDefined();
    expect(progressActivity.photo).toBeNull();
  });
});
