import {
  buildObjectPath,
  _resetStorageClient,
} from '../../../src/services/gcs.service';

// Reset storage client before each test to ensure clean state
beforeEach(() => {
  _resetStorageClient();
});

describe('GCS Service', () => {
  describe('buildObjectPath', () => {
    it('should build correct object path', () => {
      const userId = '123e4567-e89b-12d3-a456-426614174000';
      const entryId = 'abcd1234-5678-90ab-cdef-123456789abc';

      const path = buildObjectPath(userId, entryId);

      // Should be in format: {userId}/{year}/{month}/{entryId}.jpg
      expect(path).toMatch(new RegExp(`^${userId}/\\d{4}/\\d{2}/${entryId}\\.jpg$`));

      // Verify it uses current year/month
      const now = new Date();
      const expectedYear = now.getFullYear();
      const expectedMonth = String(now.getMonth() + 1).padStart(2, '0');

      expect(path).toBe(`${userId}/${expectedYear}/${expectedMonth}/${entryId}.jpg`);
    });

    it('should pad month with leading zero', () => {
      const userId = 'user-123';
      const entryId = 'entry-456';

      const path = buildObjectPath(userId, entryId);

      // Extract month from path
      const parts = path.split('/');
      const month = parts[2];

      // Month should always be 2 digits
      expect(month.length).toBe(2);
    });
  });
});
