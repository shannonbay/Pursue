# Pursue Backend Server

A Node.js/Express.js backend server for the Pursue goal accountability app.

## Tech Stack

- **Runtime**: Node.js 20.x
- **Language**: TypeScript 5.x
- **Framework**: Express.js
- **Database**: PostgreSQL 17.x
- **ORM**: Kysely (type-safe query builder)
- **Authentication**: JWT + Google OAuth
- **Validation**: Zod
- **Password Hashing**: bcrypt

## Prerequisites

- Node.js 20.x or higher
- PostgreSQL 17.x (16.x also compatible)
- npm or yarn

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/yourusername/pursue-server.git
cd pursue-server
```

### 2. Install dependencies

```bash
npm install
```

### 3. Set up PostgreSQL database

Connect to PostgreSQL as the postgres user:

```bash
psql -U postgres
```

Create the database:

```sql
CREATE DATABASE pursue_db;
```

Exit psql (`\q`) and apply the schema:

```bash
psql -U postgres -d pursue_db -f schema.sql
```

To view the database with psql:
```powershell
# Set client encoding to UTF-8
$env:PGCLIENTENCODING="UTF8"
```

**Or in psql:**
```sql
SET client_encoding = 'UTF8';
\i schema.sql
```
### 4. Configure environment variables

Copy the example environment file:

```bash
cp .env.example .env
```

Edit `.env` with your configuration:

```env
# Database
DATABASE_URL=postgresql://postgres:yourpassword@localhost:5432/pursue_db

# JWT (generate secure random strings for these)
JWT_SECRET=your-super-secret-jwt-key-min-32-chars
JWT_REFRESH_SECRET=your-super-secret-refresh-key-min-32-chars

# Google OAuth (get from Google Cloud Console)
# Web Application Client ID (for web clients)
GOOGLE_CLIENT_ID=your-web-client-id.apps.googleusercontent.com
# Android Client ID (for Android app) - required for Android authentication
GOOGLE_ANDROID_CLIENT_ID=your-android-client-id.apps.googleusercontent.com

# App
NODE_ENV=development
PORT=3000
FRONTEND_URL=http://localhost:3000
```

**Note:** For Google OAuth, you need to configure both client IDs:
- **GOOGLE_CLIENT_ID**: Web Application Client ID from Google Cloud Console
- **GOOGLE_ANDROID_CLIENT_ID**: Android Client ID from Google Cloud Console (required for Android app authentication)

The backend supports tokens from both client IDs, allowing authentication from web and Android clients.

### 5. Run the server

Development mode (with hot reload):

```bash
npm run dev
```

Test mode for E2E tests (ratelimiter disabled):

```
$env:NODE_ENV='test'; npm run dev
```

Production mode:

```bash
npm run build
npm start
```

Docker:

```bash
docker-compose up --build
```

or 

```bash
npm run build # Dockerfile doesn't build
docker build -t pursue-backend .
docker run -p 3000:3000 --env-file .env pursue-backend
```

## API Endpoints

### Authentication (`/api/auth`)

| Method | Endpoint | Description | Auth Required |
|--------|----------|-------------|---------------|
| POST | `/register` | Register with email/password | No |
| POST | `/login` | Sign in with email/password | No |
| POST | `/google` | Sign in/register with Google OAuth | No |
| POST | `/refresh` | Refresh access token | No |
| POST | `/logout` | Revoke refresh token | Yes |
| POST | `/forgot-password` | Request password reset email | No |
| POST | `/reset-password` | Reset password with token | No |
| POST | `/link/google` | Link Google account | Yes |
| DELETE | `/unlink/:provider` | Unlink auth provider | Yes |

### Health Check

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | Check server and database status |

## Project Structure

```
src/
├── app.ts                    # Express app configuration
├── server.ts                 # Server entry point
├── controllers/
│   └── auth.ts               # Auth controller functions
├── database/
│   ├── index.ts              # Kysely database connection
│   └── types.ts              # TypeScript types for all tables
├── middleware/
│   ├── authenticate.ts       # JWT authentication middleware
│   ├── errorHandler.ts       # Global error handler
│   └── rateLimiter.ts        # Rate limiting middleware
├── routes/
│   └── auth.ts               # Auth routes
├── services/
│   └── googleAuth.ts         # Google OAuth verification
├── types/
│   └── express.ts            # Custom Express types
├── utils/
│   ├── jwt.ts                # JWT token utilities
│   └── password.ts           # Password hashing utilities
└── validations/
    └── auth.ts               # Zod validation schemas
```

## Testing

### Set up test database

```bash
psql -U postgres
CREATE DATABASE pursue_test;
\q
```

### Run tests

```bash
# Run all tests
npm test

# Run with coverage
npm run test:coverage

# Run in watch mode
npm run test:watch

# Run integration tests only
npm run test:integration
```

### Test Structure

```
tests/
├── setup.ts                    # Global test setup
├── helpers.ts                  # Test utilities
└── integration/
    └── auth/
        ├── register.test.ts
        ├── login.test.ts
        ├── google.test.ts
        ├── refresh.test.ts
        ├── logout.test.ts
        ├── forgot-password.test.ts
        ├── reset-password.test.ts
        ├── link-google.test.ts
        └── unlink.test.ts
```

## Scripts

| Script | Description |
|--------|-------------|
| `npm run dev` | Start development server with hot reload |
| `npm run build` | Compile TypeScript to JavaScript |
| `npm start` | Start production server |
| `npm test` | Run all tests |
| `npm run test:watch` | Run tests in watch mode |
| `npm run test:coverage` | Run tests with coverage report |
| `npm run test:integration` | Run integration tests only |
| `npm run test:ci` | Run tests in CI mode |

## License

ISC
