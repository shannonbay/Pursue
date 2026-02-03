import request from 'supertest';
import { app } from '../../src/app';

describe('App endpoints', () => {
  describe('GET /health', () => {
    it('should return healthy status when database is connected', async () => {
      const response = await request(app).get('/health');

      expect(response.status).toBe(200);
      expect(response.body.status).toBe('healthy');
      expect(response.body).toHaveProperty('timestamp');
      expect(response.body).toHaveProperty('uptime');
      expect(typeof response.body.uptime).toBe('number');
    });
  });

  describe('404 handler', () => {
    it('should return 404 for unknown routes', async () => {
      const response = await request(app).get('/unknown-route');

      expect(response.status).toBe(404);
      expect(response.body.error.message).toBe('Not found');
      expect(response.body.error.code).toBe('NOT_FOUND');
    });

    it('should return 404 for unknown API routes', async () => {
      const response = await request(app).get('/api/unknown');

      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('NOT_FOUND');
    });

    it('should return 404 for POST to unknown routes', async () => {
      const response = await request(app)
        .post('/unknown-route')
        .send({ data: 'test' });

      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('NOT_FOUND');
    });
  });
});
