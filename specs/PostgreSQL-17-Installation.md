# PostgreSQL 17 Installation Guide for Pursue

**Recommended Version:** PostgreSQL 17.x (latest stable)

---

## Windows Installation

### Option 1: Official Installer (Recommended)

1. **Download PostgreSQL 17:**
   - Visit: https://www.postgresql.org/download/windows/
   - Download the latest 17.x installer (e.g., 17.2)
   - Run the installer

2. **Installation Settings:**
   ```
   Installation Directory: C:\Program Files\PostgreSQL\17
   Data Directory: C:\Program Files\PostgreSQL\17\data
   Port: 5432 (default)
   Locale: Default
   Password: [Choose a strong password for 'postgres' user]
   ```

3. **Verify Installation:**
   ```powershell
   psql --version
   # Should output: psql (PostgreSQL) 17.x
   ```

4. **Create Development Database:**
   ```powershell
   # Open SQL Shell (psql) from Start menu
   # Login with postgres user
   CREATE DATABASE pursue_dev;
   \c pursue_dev
   ```

### Option 2: Winget (Command Line)

```powershell
# Install PostgreSQL 17
winget install PostgreSQL.PostgreSQL.17

# Verify
psql --version
```

### Option 3: Chocolatey

```powershell
# Install Chocolatey first (if not installed)
choco install postgresql17

# Verify
psql --version
```

---

## WSL2 (Ubuntu) Installation

### Install PostgreSQL 17:

```bash
# Add PostgreSQL APT repository
sudo apt install -y wget ca-certificates
wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add -
sudo sh -c 'echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list'

# Update and install
sudo apt update
sudo apt install -y postgresql-17 postgresql-contrib-17

# Verify
psql --version
# Should output: psql (PostgreSQL) 17.x
```

### Start PostgreSQL:

```bash
# Start PostgreSQL service
sudo service postgresql start

# Switch to postgres user
sudo -u postgres psql

# Create development database
CREATE DATABASE pursue_dev;
\q
```

### Configure for Development:

```bash
# Allow local connections
sudo nano /etc/postgresql/17/main/pg_hba.conf

# Change this line:
# local   all             postgres                                peer
# To:
# local   all             postgres                                md5

# Restart PostgreSQL
sudo service postgresql restart
```

---

## Docker Installation

### Using Docker Compose (Recommended):

```yaml
# docker-compose.yml
version: '3.8'
services:
  postgres:
    image: postgres:17-alpine
    container_name: pursue-postgres
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: dev_password
      POSTGRES_DB: pursue_dev
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-EXEC", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres_data:
```

### Start Docker PostgreSQL:

```bash
# Start container
docker-compose up -d

# Verify
docker ps
docker exec -it pursue-postgres psql -U postgres -d pursue_dev

# Check version
SELECT version();
# Should show: PostgreSQL 17.x
```

---

## macOS Installation

### Using Homebrew:

```bash
# Install PostgreSQL 17
brew install postgresql@17

# Add to PATH
echo 'export PATH="/opt/homebrew/opt/postgresql@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc

# Start PostgreSQL
brew services start postgresql@17

# Verify
psql --version

# Create database
createdb pursue_dev
```

---

## Connection Strings

### Local Development:

```bash
# Windows / macOS / Linux
DATABASE_URL=postgresql://postgres:your_password@localhost:5432/pursue_dev

# WSL2 (if connecting from Windows)
DATABASE_URL=postgresql://postgres:your_password@localhost:5432/pursue_dev

# Docker
DATABASE_URL=postgresql://postgres:dev_password@localhost:5432/pursue_dev
```

### Cloud SQL (Production):

```bash
# Using Unix socket (Cloud Run)
DATABASE_URL=postgresql://user:password@/dbname?host=/cloudsql/project:region:instance

# Using TCP (external)
DATABASE_URL=postgresql://user:password@instance-ip:5432/dbname
```

---

## PostgreSQL 17 Features Used in Pursue

### 1. Better JSON Performance
```sql
-- 20-30% faster JSONB operations
CREATE TABLE group_activities (
  metadata JSONB  -- Improved performance in PostgreSQL 17
);
```

### 2. Improved VACUUM
```sql
-- Better cleanup for high-update tables
-- Benefits progress_entries table (frequent inserts)
VACUUM ANALYZE progress_entries;
```

### 3. Enhanced Connection Pooling
- Ideal for serverless (Cloud Run)
- Handles concurrent connections better
- Faster connection establishment

### 4. Partial Indexes
```sql
-- More efficient partial indexes
CREATE INDEX idx_goals_active ON goals(group_id) WHERE deleted_at IS NULL;
```

---

## Upgrading from PostgreSQL 15/16

### Backup Data:

```bash
# Dump existing database
pg_dump -U postgres pursue_dev > pursue_backup.sql
```

### Install PostgreSQL 17:

```bash
# Follow installation steps above for your platform
```

### Restore Data:

```bash
# Create new database
createdb pursue_dev

# Restore backup
psql -U postgres -d pursue_dev < pursue_backup.sql
```

### Verify:

```sql
-- Check version
SELECT version();

-- Verify tables
\dt

-- Test queries
SELECT COUNT(*) FROM users;
```

---

## Troubleshooting

### Port Already in Use:

```bash
# Windows: Find process using port 5432
netstat -ano | findstr :5432
taskkill /PID <PID> /F

# Linux/macOS: Find process
lsof -i :5432
kill -9 <PID>
```

### Connection Refused:

```bash
# Check PostgreSQL is running
# Windows:
Get-Service postgresql*

# Linux/macOS:
sudo service postgresql status

# Docker:
docker ps
```

### Password Authentication Failed:

```bash
# Reset postgres user password
# Windows: Use pgAdmin
# Linux/WSL:
sudo -u postgres psql
ALTER USER postgres PASSWORD 'new_password';
```

---

## Recommended Tools

### GUI Clients:

1. **pgAdmin 4** (Free, Official)
   - https://www.pgadmin.org/download/
   - Full-featured PostgreSQL management

2. **DBeaver** (Free, Cross-platform)
   - https://dbeaver.io/download/
   - Supports multiple databases

3. **TablePlus** (Paid, Beautiful UI)
   - https://tableplus.com/
   - macOS/Windows, fast and modern

### Command-Line Tools:

1. **psql** (Built-in)
   - Best for quick queries and admin tasks

2. **pgcli** (Enhanced psql)
   ```bash
   pip install pgcli
   pgcli postgresql://postgres:password@localhost/pursue_dev
   ```
   - Auto-completion, syntax highlighting

---

## Next Steps

After installing PostgreSQL 17:

1. **Create database:**
   ```sql
   CREATE DATABASE pursue_dev;
   ```

2. **Set up environment variables:**
   ```bash
   # .env
   DATABASE_URL=postgresql://postgres:your_password@localhost:5432/pursue_dev
   ```

3. **Run migrations:**
   ```bash
   npm run migrate
   ```

4. **Verify schema:**
   ```bash
   psql -U postgres -d pursue_dev
   \dt  # List tables
   ```

---

**Version:** 1.0  
**PostgreSQL Version:** 17.x  
**Last Updated:** January 2026
