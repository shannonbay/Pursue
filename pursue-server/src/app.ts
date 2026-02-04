import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { db } from './database/index.js';
import { errorHandler } from './middleware/errorHandler.js';
import { apiLimiter } from './middleware/rateLimiter.js';
import { logger } from './utils/logger.js';
import authRoutes from './routes/auth.js';
import deviceRoutes from './routes/devices.js';
import goalRoutes from './routes/goals.js';
import groupRoutes from './routes/groups.js';
import progressRoutes from './routes/progress.js';
import userRoutes from './routes/users.js';
import subscriptionRoutes from './routes/subscriptions.js';

const app = express();

// Trust proxy when running behind reverse proxy (Docker, nginx, etc.) - allow one hop
// Required for express-rate-limit to correctly identify client IPs via X-Forwarded-For
app.set('trust proxy', 1);

// Security middleware
app.use(helmet());
const allowedOrigins = [
  'http://localhost:3000', // For local dev
  'https://getpursue.app',  // Your marketing/web app
  'https://api.getpursue.app'
];

app.use(cors({
  origin: (origin, callback) => {
    // Allow requests with no origin (like mobile apps or curl)
    if (!origin) return callback(null, true);
    if (allowedOrigins.indexOf(origin) !== -1) {
      callback(null, true);
    } else {
      callback(new Error('Not allowed by CORS'));
    }
  },
  credentials: true,
}));

// Body parsing (skip multipart/form-data - multer handles that)
app.use((req, res, next) => {
  const contentType = req.get('Content-Type') || '';
  if (contentType.includes('multipart/form-data')) {
    // Skip body parsing for multipart - multer will handle it
    return next();
  }
  express.json()(req, res, next);
});
app.use((req, res, next) => {
  const contentType = req.get('Content-Type') || '';
  if (contentType.includes('multipart/form-data')) {
    // Skip body parsing for multipart - multer will handle it
    return next();
  }
  express.urlencoded({ extended: true })(req, res, next);
});

// Rate limiting
app.use('/api', apiLimiter);

// Health check endpoint
app.get('/health', async (_req, res) => {
  const start = Date.now();
  try {
    // Lightweight DB ping with short timeout
    await Promise.race([
      db.selectFrom('users').select('id').limit(1).execute(),
      new Promise((_resolve, reject) =>
        setTimeout(() => reject(new Error('DB ping timeout')), 1000)
      ),
    ]);
    const dbResponseTimeMs = Date.now() - start;

    res.status(200).json({
      status: 'healthy',
      timestamp: new Date().toISOString(),
      uptime: process.uptime(),
      db: { status: 'ok', responseTimeMs: dbResponseTimeMs },
    });
  } catch (error) {
    const msg = error instanceof Error ? error.message : 'Unknown error';
    res.status(503).json({
      status: 'unhealthy',
      error: msg,
      db: { status: 'down', error: msg },
    });
  }
});

// Temporary: Log all /api/users/avatar requests (only if DEBUG_AVATAR is enabled)
const DEBUG_AVATAR = process.env.DEBUG_AVATAR === 'true';
if (DEBUG_AVATAR) {
  app.use('/api/users', (req, res, next) => {
    if (req.path?.includes('/avatar')) {
      logger.debug('Avatar request intercepted', {
        method: req.method,
        path: req.path,
        url: req.url,
        contentType: req.get('Content-Type'),
      });
    }
    next();
  });
}

// API routes
app.use('/api/auth', authRoutes);
app.use('/api/devices', deviceRoutes);
app.use('/api/goals', goalRoutes);
app.use('/api/groups', groupRoutes);
app.use('/api/progress', progressRoutes);
app.use('/api/users', userRoutes);
app.use('/api/subscriptions', subscriptionRoutes);

// Error handling
app.use(errorHandler);

// 404 handler
app.use((_req, res) => {
  res.status(404).json({
    error: {
      message: 'Not found',
      code: 'NOT_FOUND',
    },
  });
});

export { app };
