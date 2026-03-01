#!/bin/bash

##################################
# Rate Limit Bypass Test Script
# Tests for rate limiting circumvention
##################################

API_BASE="${1:-https://api.getpursue.app}"
OUTPUT="results/rate-limit-$(date +%Y%m%d-%H%M%S).csv"

echo "Testing rate limiting on: $API_BASE"
echo "Saving results to: $OUTPUT"
echo ""

mkdir -p results

# CSV header
echo "Endpoint,Request_Number,Status,Timestamp" > "$OUTPUT"

##################################
# Test 1: /api/auth/refresh rate limiting
##################################

echo "Testing /api/auth/refresh rate limiting..."
echo "Request,Status" > "$OUTPUT.refresh"

for i in $(seq 1 20); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$API_BASE/api/auth/refresh" \
    -H "Content-Type: application/json" \
    -d '{"refresh_token":"fake-token-'$i'"}')

  echo "$i,$STATUS" >> "$OUTPUT.refresh"
  echo "  Request $i: Status=$STATUS"

  if [ "$STATUS" == "429" ]; then
    echo "✓ Rate limit enforced at request $i"
    break
  fi

  sleep 0.1 # Very short delay
done

##################################
# Test 2: /api/auth/login rate limiting
##################################

echo ""
echo "Testing /api/auth/login rate limiting..."
echo "Request,Status" > "$OUTPUT.login"

for i in $(seq 1 20); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$API_BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"test'$i'@example.com","password":"password"}')

  echo "$i,$STATUS" >> "$OUTPUT.login"
  echo "  Request $i: Status=$STATUS"

  if [ "$STATUS" == "429" ]; then
    echo "✓ Rate limit enforced at request $i"
    break
  fi

  sleep 0.1
done

##################################
# Test 3: IP spoofing attempt (X-Forwarded-For)
##################################

echo ""
echo "Testing IP spoofing via X-Forwarded-For header..."

for i in $(seq 1 10); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$API_BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -H "X-Forwarded-For: 192.168.1.$i" \
    -d '{"email":"spoof'$i'@example.com","password":"password"}')

  echo "  Request $i (Spoofed IP 192.168.1.$i): Status=$STATUS"

  if [ "$STATUS" == "429" ]; then
    echo "  IP spoofing may bypass rate limits"
    break
  fi
done

##################################
# Test 4: User-Agent variation
##################################

echo ""
echo "Testing rate limit bypass with User-Agent variation..."

USER_AGENTS=(
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"
  "Mozilla/5.0 (X11; Linux x86_64)"
  "curl/7.68.0"
  "PostmanRuntime/7.26.8"
)

for i in $(seq 1 20); do
  UA="${USER_AGENTS[$((i % ${#USER_AGENTS[@]}))]}"
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$API_BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -H "User-Agent: $UA" \
    -d '{"email":"ua'$i'@example.com","password":"password"}')

  echo "  Request $i (UA variation): Status=$STATUS"

  if [ "$STATUS" == "429" ]; then
    echo "✓ Rate limit respects User-Agent variation"
    break
  fi
done

echo ""
echo "✓ Rate limiting tests completed"
echo "Results saved to:"
echo "  - $OUTPUT.refresh"
echo "  - $OUTPUT.login"
