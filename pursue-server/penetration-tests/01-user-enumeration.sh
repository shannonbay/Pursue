#!/bin/bash

##################################
# User Enumeration Test Script
# Tests for user enumeration vulnerabilities
##################################

API_BASE="${1:-https://api.getpursue.app}"
OUTPUT="results/user-enumeration-$(date +%Y%m%d-%H%M%S).csv"

echo "Testing user enumeration on: $API_BASE"
echo "Saving results to: $OUTPUT"
echo ""

# Create output directory
mkdir -p results

# CSV header
echo "UUID,HTTP_Status,Response_Time_Ms,Username_Guessed" > "$OUTPUT"

echo "Testing login endpoint with random UUIDs as emails..."

# Test with various patterns
for i in $(seq 1 20); do
  # Test 1: UUID format
  TEST_EMAIL="user-$i@example.com"
  START=$(date +%s%N)
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$API_BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"'"$TEST_EMAIL"'","password":"wrongpassword"}')
  END=$(date +%s%N)
  DURATION=$(( (END - START) / 1000000 ))

  echo "$TEST_EMAIL,$STATUS,$DURATION,false" >> "$OUTPUT"
  echo "Request $i: Email=$TEST_EMAIL Status=$STATUS Time=${DURATION}ms"

  sleep 0.5 # Rate limit friendly
done

echo ""
echo "Testing avatar endpoint for enumeration..."

# Test avatar endpoint
for i in $(seq 1 10); do
  TEST_UUID="00000000-0000-0000-0000-$(printf '%012d' $i)"

  START=$(date +%s%N)
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    "$API_BASE/api/users/$TEST_UUID/avatar")
  END=$(date +%s%N)
  DURATION=$(( (END - START) / 1000000 ))

  echo "$TEST_UUID,$STATUS,$DURATION,false" >> "$OUTPUT"
  echo "Avatar Request $i: UUID=$TEST_UUID Status=$STATUS Time=${DURATION}ms"

  sleep 0.5
done

echo ""
echo "✓ User enumeration test completed"
echo "Results saved to: $OUTPUT"
echo ""
echo "Analysis:"
echo "- If all 404 or 401 responses have similar timing, user enumeration is mitigated"
echo "- If response times vary significantly, timing attack may be possible"
