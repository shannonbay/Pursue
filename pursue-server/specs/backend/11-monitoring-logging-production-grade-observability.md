## 11. Monitoring & Logging (Production-Grade Observability)

### 11.1 Structured Logging with Winston

```typescript
import winston from 'winston';

export const logger = winston.createLogger({
  level: process.env.LOG_LEVEL || 'info',
  format: winston.format.combine(
    winston.format.timestamp(),
    winston.format.errors({ stack: true }),
    winston.format.json()
  ),
  transports: [
    new winston.transports.Console()
  ]
});

// Usage with structured data
logger.info('User registered', {
  user_id: userId,
  email: email,
  method: 'email',
  duration_ms: Date.now() - startTime
});

logger.error('Database error', {
  error: error.message,
  stack: error.stack,
  query: queryName,
  user_id: userId
});

logger.warn('Rate limit exceeded', {
  user_id: userId,
  endpoint: req.path,
  ip: req.ip
});
```

### 11.2 Request Logging Middleware with Metrics

```typescript
import morgan from 'morgan';

// Custom token for response time
morgan.token('response-time-ms', (req, res) => {
  return res.responseTime ? res.responseTime.toFixed(2) : '0';
});

// Track response times
app.use((req, res, next) => {
  const start = Date.now();
  res.on('finish', () => {
    res.responseTime = Date.now() - start;
    
    // Log slow queries (> 1 second)
    if (res.responseTime > 1000) {
      logger.warn('Slow request', {
        method: req.method,
        path: req.path,
        duration_ms: res.responseTime,
        status: res.statusCode,
        user_id: req.user?.user_id
      });
    }
  });
  next();
});

// JSON format for Cloud Logging
app.use(morgan(':method :url :status :response-time-ms ms', {
  stream: {
    write: (message) => {
      logger.info(message.trim(), {
        category: 'http_request'
      });
    }
  }
}));
```

### 11.3 Health Check Endpoint (Production-Ready)

```typescript
app.get('/health', async (req, res) => {
  const healthcheck = {
    status: 'healthy',
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
    checks: {
      database: 'unknown',
      memory: 'unknown'
    }
  };
  
  try {
    // Check database connection with timeout
    const dbCheckStart = Date.now();
    await Promise.race([
      db.selectFrom('users').select('id').limit(1).execute(),
      new Promise((_, reject) => 
        setTimeout(() => reject(new Error('DB timeout')), 5000)
      )
    ]);
    healthcheck.checks.database = 'healthy';
    healthcheck.checks.database_latency_ms = Date.now() - dbCheckStart;
    
    // Check memory usage
    const memUsage = process.memoryUsage();
    const memUsageMB = Math.round(memUsage.heapUsed / 1024 / 1024);
    healthcheck.checks.memory = memUsageMB < 900 ? 'healthy' : 'warning';
    healthcheck.checks.memory_usage_mb = memUsageMB;
    
    res.status(200).json(healthcheck);
  } catch (error) {
    healthcheck.status = 'unhealthy';
    healthcheck.checks.database = 'unhealthy';
    healthcheck.error = error.message;
    
    logger.error('Health check failed', {
      error: error.message,
      checks: healthcheck.checks
    });
    
    res.status(503).json(healthcheck);
  }
});

// Readiness probe (for zero-downtime deployments)
app.get('/ready', async (req, res) => {
  try {
    await db.selectFrom('users').select('id').limit(1).execute();
    res.status(200).send('OK');
  } catch (error) {
    res.status(503).send('NOT READY');
  }
});
```

### 11.4 Cloud Monitoring Metrics

**Key metrics to monitor in Google Cloud Console:**

```typescript
// Custom metric export (optional - Cloud Run auto-tracks these)
interface Metrics {
  request_count: number;
  request_duration_ms: number[];
  error_count: number;
  db_query_count: number;
  db_query_duration_ms: number[];
  active_users: Set<string>;
}

// Track in middleware
app.use((req, res, next) => {
  metrics.request_count++;
  
  const start = Date.now();
  res.on('finish', () => {
    const duration = Date.now() - start;
    metrics.request_duration_ms.push(duration);
    
    if (res.statusCode >= 500) {
      metrics.error_count++;
    }
    
    if (req.user?.user_id) {
      metrics.active_users.add(req.user.user_id);
    }
  });
  
  next();
});
```

**Auto-tracked metrics (Cloud Run):**
- `request_count` - Total requests
- `request_latencies` - p50, p95, p99 latency
- `billable_instance_time` - Container uptime
- `container_cpu_utilization` - CPU usage %
- `container_memory_utilization` - Memory usage %
- `container_instance_count` - Active instances

### 11.5 Alerting Policies (Google Cloud Monitoring)

**Critical Alerts:**

```yaml
# High Error Rate Alert
- name: "High Error Rate"
  condition: error_rate > 1%
  duration: 5 minutes
  notification: PagerDuty, Email
  
# Slow Response Time Alert  
- name: "Slow Response Time"
  condition: p95_latency > 1000ms
  duration: 10 minutes
  notification: Slack, Email
  
# Database Connection Issues
- name: "Database Connection Failures"
  condition: db_connection_errors > 5
  duration: 1 minute
  notification: PagerDuty, Email
  
# High Memory Usage
- name: "Memory Usage Warning"
  condition: memory_usage > 80%
  duration: 15 minutes
  notification: Slack

# Instance Scaling Issues
- name: "Max Instances Reached"
  condition: instance_count >= 95
  duration: 5 minutes
  notification: PagerDuty, Email
```

### 11.6 Graceful Shutdown

```typescript
// Handle SIGTERM for zero-downtime deployments
process.on('SIGTERM', async () => {
  logger.info('SIGTERM received, shutting down gracefully');
  
  // Stop accepting new requests
  server.close(async () => {
    logger.info('HTTP server closed');
    
    // Close database connections
    try {
      await db.destroy();
      logger.info('Database connections closed');
    } catch (error) {
      logger.error('Error closing database', { error: error.message });
    }
    
    process.exit(0);
  });
  
  // Force shutdown after 30 seconds (Cloud Run timeout)
  setTimeout(() => {
    logger.error('Forced shutdown after timeout');
    process.exit(1);
  }, 30000);
});
```

---

