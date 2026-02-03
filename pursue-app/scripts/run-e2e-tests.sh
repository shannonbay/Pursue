#!/bin/bash

set -e

echo "======================================"
echo "Pursue E2E Test Runner"
echo "======================================"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if backend directory exists
if [ ! -d "../pursue-backend" ]; then
    echo -e "${RED}❌ Backend directory not found at ../pursue-backend${NC}"
    exit 1
fi

# Start backend server
echo -e "${YELLOW}📦 Starting backend server...${NC}"
cd ../pursue-backend

# Check if npm dependencies are installed
if [ ! -d "node_modules" ]; then
    echo -e "${YELLOW}📥 Installing backend dependencies...${NC}"
    npm install
fi

# Start server in background
npm run dev &
BACKEND_PID=$!

echo -e "${YELLOW}⏳ Waiting for backend to be ready...${NC}"
sleep 5

# Check if server is running
if curl -s http://localhost:3000/health > /dev/null; then
    echo -e "${GREEN}✅ Backend server is ready${NC}"
else
    echo -e "${RED}❌ Backend server failed to start${NC}"
    kill $BACKEND_PID 2>/dev/null
    exit 1
fi

# Run E2E tests
echo -e "${YELLOW}🧪 Running E2E tests...${NC}"
cd ../pursue-android

if ./gradlew testE2e; then
    echo -e "${GREEN}✅ E2E tests passed!${NC}"
    TEST_RESULT=0
else
    echo -e "${RED}❌ E2E tests failed${NC}"
    TEST_RESULT=1
fi

# Cleanup - Stop backend server
echo -e "${YELLOW}🧹 Stopping backend server...${NC}"
kill $BACKEND_PID 2>/dev/null || true

echo "======================================"
if [ $TEST_RESULT -eq 0 ]; then
    echo -e "${GREEN}✅ E2E Test Suite: PASSED${NC}"
else
    echo -e "${RED}❌ E2E Test Suite: FAILED${NC}"
fi
echo "======================================"

exit $TEST_RESULT
