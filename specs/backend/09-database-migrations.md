## 9. Database Migrations

### 9.1 Migration Tool

Use **node-pg-migrate** for database migrations.

**Install:**
```bash
npm install node-pg-migrate
```

**Create Migration:**
```bash
npx node-pg-migrate create initial-schema
```

**Migration File (migrations/1705334400000_initial-schema.js):**
```javascript
exports.up = (pgm) => {
  // Create users table
  pgm.createTable('users', {
    id: {
      type: 'uuid',
      primaryKey: true,
      default: pgm.func('uuid_generate_v4()')
    },
    email: {
      type: 'varchar(255)',
      notNull: true,
      unique: true
    },
    display_name: {
      type: 'varchar(100)',
      notNull: true
    },
    avatar_data: 'bytea',         // Stored image data (WebP, 256x256)
    avatar_mime_type: 'varchar(50)',  // 'image/webp', 'image/jpeg'
    password_hash: 'varchar(255)',
    created_at: {
      type: 'timestamp with time zone',
      notNull: true,
      default: pgm.func('NOW()')
    },
    updated_at: {
      type: 'timestamp with time zone',
      notNull: true,
      default: pgm.func('NOW()')
    }
  });
  
  // Add more tables...
};

exports.down = (pgm) => {
  pgm.dropTable('users');
  // Drop other tables...
};
```

**Run Migrations:**
```bash
DATABASE_URL=postgresql://... npx node-pg-migrate up
```

---

