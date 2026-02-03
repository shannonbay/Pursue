import 'dotenv/config';
import { app } from './app.js';
import { logger } from './utils/logger.js';

// Validate required environment variables
const requiredEnvVars = [
  'JWT_SECRET',
  'JWT_REFRESH_SECRET',
  'DATABASE_URL',
];

// Google Client IDs for OAuth
// - GOOGLE_CLIENT_ID: Web Application Client ID (for web clients)
// - GOOGLE_ANDROID_CLIENT_ID: Android Client ID (for Android app)
// At least one must be set for Google OAuth to work
if (!process.env.GOOGLE_CLIENT_ID && !process.env.GOOGLE_ANDROID_CLIENT_ID) {
  logger.warn('No Google Client ID configured. Google OAuth will not work.', {
    hint: 'Set GOOGLE_CLIENT_ID (Web Application) or GOOGLE_ANDROID_CLIENT_ID (Android) from Google Cloud Console',
  });
}

const missingVars = requiredEnvVars.filter((varName) => !process.env[varName]);

if (missingVars.length > 0) {
  logger.error('Missing required environment variables', {
    missing: missingVars,
    hint: 'Please set these variables in your .env file or environment',
  });
  process.exit(1);
}

// Use the port provided by the environment (Cloud Run sets PORT), default 8080
const PORT = process.env.PORT || 8080;

const server = app.listen(Number(PORT), '0.0.0.0', () => {
  const nodeEnv = process.env.NODE_ENV || 'development';
  const debugAvatar = process.env.DEBUG_AVATAR === 'true';
  const webClientId = process.env.GOOGLE_CLIENT_ID;
  const androidClientId = process.env.GOOGLE_ANDROID_CLIENT_ID;
  const hasWebClient = !!webClientId;
  const hasAndroidClient = !!androidClientId;

  logger.info('Server started', {
    port: PORT,
    environment: nodeEnv,
    debugAvatar,
    googleOAuth: hasWebClient || hasAndroidClient,
    webClientId: hasWebClient && webClientId ? `${webClientId.substring(0, 30)}...` : undefined,
    androidClientId: hasAndroidClient && androidClientId ? `${androidClientId.substring(0, 30)}...` : undefined,
  });
});

// Handle server startup errors
server.on('error', (error: NodeJS.ErrnoException) => {
  if (error.code === 'EADDRINUSE') {
    logger.error('Port already in use', {
      port: PORT,
      code: error.code,
      fix: {
        windows: `netstat -ano | findstr :${PORT} then taskkill /PID <PID> /F`,
        linuxMac: `lsof -ti:${PORT} | xargs kill -9`,
        alternative: `Set PORT environment variable: PORT=3001 npm run dev`,
      },
      commonCauses: [
        'Another instance of this server is already running',
        'A previous server process did not shut down cleanly',
        'Another application is using this port',
      ],
    });
  } else if (error.code === 'EACCES') {
    logger.error('Permission denied - cannot bind to port', {
      port: PORT,
      code: error.code,
      hint: 'Try using a port number above 1024, or run with administrator privileges',
    });
  } else {
    logger.error('Server startup error', {
      code: error.code || 'UNKNOWN',
      message: error.message,
      stack: error.stack,
    });
  }
  process.exit(1);
});
