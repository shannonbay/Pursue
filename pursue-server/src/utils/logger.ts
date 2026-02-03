import winston from 'winston';
import fs from 'fs';
import path from 'path';

// Ensure logs directory exists
const logsDir = path.join(process.cwd(), 'logs');
if (!fs.existsSync(logsDir)) {
  fs.mkdirSync(logsDir, { recursive: true });
}

// Determine log level based on environment
const nodeEnv = process.env.NODE_ENV || 'development';
const logLevel = process.env.LOG_LEVEL || (nodeEnv === 'production' || nodeEnv === 'test' ? 'warn' : 'debug');

// Determine format based on environment
const isProduction = nodeEnv === 'production';
const format = isProduction
  ? winston.format.combine(
      winston.format.timestamp(),
      winston.format.errors({ stack: true }),
      winston.format.json()
    )
  : winston.format.combine(
      winston.format.timestamp({ format: 'YYYY-MM-DD HH:mm:ss' }),
      winston.format.colorize(),
      winston.format.printf(({ timestamp, level, message, ...meta }) => {
        const metaStr = Object.keys(meta).length ? JSON.stringify(meta, null, 2) : '';
        return `${timestamp} [${level}]: ${message}${metaStr ? '\n' + metaStr : ''}`;
      })
    );

// Create logger instance
export const logger = winston.createLogger({
  level: logLevel,
  format,
  transports: [
    // Console transport (always enabled)
    new winston.transports.Console(),
    // File transport for errors (always enabled)
    new winston.transports.File({
      filename: path.join(logsDir, 'error.log'),
      level: 'error',
      format: winston.format.combine(
        winston.format.timestamp(),
        winston.format.errors({ stack: true }),
        winston.format.json()
      ),
    }),
  ],
});

// Log startup configuration (only in development)
if (!isProduction) {
  logger.debug('Logger initialized', {
    nodeEnv,
    logLevel,
    format: isProduction ? 'json' : 'pretty',
    errorLogFile: path.join(logsDir, 'error.log'),
  });
}
