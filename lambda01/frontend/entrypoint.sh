#!/bin/sh
# =============================================================================
#  Frontend entrypoint
#  1. Discovers the API Gateway REST API ID from LocalStack
#  2. Templates the nginx config with the correct proxy URL
#  3. Starts nginx
# =============================================================================

set -e

LOCALSTACK_URL="${LOCALSTACK_URL:-http://localstack:4566}"
REGION="us-east-1"

echo "🔍 Discovering API Gateway ID from LocalStack..."

TRIES=0
API_ID=""
while [ -z "${API_ID}" ] || [ "${API_ID}" = "None" ] || [ "${API_ID}" = "null" ]; do
    API_ID=$(curl -s "${LOCALSTACK_URL}/restapis?region=${REGION}" \
        | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
        | head -1 2>/dev/null || true)

    if [ -z "${API_ID}" ] || [ "${API_ID}" = "None" ]; then
        TRIES=$((TRIES + 1))
        if [ $TRIES -ge 30 ]; then
            echo "⚠️  Could not find API Gateway after 60s. Using fallback proxy."
            # Fallback: proxy directly to LocalStack (won't work perfectly but allows manual setup)
            API_ID="PLACEHOLDER"
            break
        fi
        echo "   Waiting for API Gateway... (attempt ${TRIES})"
        sleep 2
    fi
done

API_PATH="/restapis/${API_ID}/local/_user_request_"
echo "   ✅ API Gateway ID: ${API_ID}"
echo "   Proxy target: ${LOCALSTACK_URL}${API_PATH}"

# Template the nginx config
sed "s|__API_GATEWAY_URL__|${LOCALSTACK_URL}${API_PATH}|g" \
    /etc/nginx/templates/default.conf.template \
    > /etc/nginx/conf.d/default.conf

echo "🚀 Starting nginx..."
exec nginx -g 'daemon off;'
