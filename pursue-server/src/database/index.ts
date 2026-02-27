import { Kysely, PostgresDialect } from 'kysely';
import pg from 'pg';
import type { Database } from './types.js';

// Return DATE columns as raw "YYYY-MM-DD" strings instead of Date objects
pg.types.setTypeParser(1082, (val: string) => val);

const { Pool } = pg;

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  max: 10,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 2000,
});

export const db = new Kysely<Database>({
  dialect: new PostgresDialect({ pool }),
});

export * from './types.js';
