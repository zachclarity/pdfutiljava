#!/bin/bash
# =============================================================================
#  deploy.sh  –  Build the Lambda JAR and deploy to LocalStack
#
#  Usage:
#    ./scripts/deploy.sh          # Full build + deploy
#    ./scripts/deploy.sh --skip-build   # Deploy existing JAR only
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "${SCRIPT_DIR}")"
LOCALSTACK_URL="${LOCALSTACK_URL:-http://localhost:4566}"
REGION="us-east-1"
BUCKET="pdf-uploads"
FUNCTION="pdf-extractor"

cd "${PROJECT_DIR}"

# ---- Build ----
if [ "${1:-}" != "--skip-build" ]; then
    echo "📦 Building Lambda JAR with Maven..."
    mvn clean package -DskipTests -q
    echo "   ✅ Build complete"
else
    echo "⏭️  Skipping build"
fi

JAR_PATH=$(ls target/pdfbox-lambda-*.jar 2>/dev/null | head -1)
if [ -z "${JAR_PATH}" ]; then
    echo "❌ No JAR found in target/. Run without --skip-build."
    exit 1
fi
echo "   JAR: ${JAR_PATH}"

# ---- Wait for LocalStack ----
echo "⏳ Waiting for LocalStack..."
TRIES=0
until curl -s "${LOCALSTACK_URL}/_localstack/health" | grep -q '"s3"'; do
    sleep 2
    TRIES=$((TRIES + 1))
    if [ $TRIES -ge 30 ]; then
        echo "❌ LocalStack not ready after 60 seconds"
        exit 1
    fi
done
echo "   ✅ LocalStack is healthy"

# ---- S3 Bucket ----
echo "🪣 Ensuring S3 bucket exists..."
aws --endpoint-url="${LOCALSTACK_URL}" s3 mb "s3://${BUCKET}" --region "${REGION}" 2>/dev/null || true

# ---- Lambda ----
echo "λ  Deploying Lambda function..."
EXISTING=$(aws --endpoint-url="${LOCALSTACK_URL}" lambda get-function \
    --function-name "${FUNCTION}" --region "${REGION}" 2>/dev/null || true)

if [ -z "${EXISTING}" ]; then
    aws --endpoint-url="${LOCALSTACK_URL}" lambda create-function \
        --function-name "${FUNCTION}" \
        --runtime java21 \
        --handler com.pdfextractor.handler.PdfLambdaHandler \
        --role arn:aws:iam::000000000000:role/lambda-role \
        --zip-file "fileb://${JAR_PATH}" \
        --timeout 120 \
        --memory-size 1024 \
        --environment "Variables={S3_BUCKET=${BUCKET},AWS_ENDPOINT_URL=http://localhost.localstack.cloud:4566,AWS_REGION=${REGION}}" \
        --region "${REGION}" > /dev/null
    echo "   ✅ Lambda created"
else
    aws --endpoint-url="${LOCALSTACK_URL}" lambda update-function-code \
        --function-name "${FUNCTION}" \
        --zip-file "fileb://${JAR_PATH}" \
        --region "${REGION}" > /dev/null
    echo "   ✅ Lambda updated"
fi

# ---- API Gateway ----
echo "🌐 Setting up API Gateway..."

# Check if API already exists
API_ID=$(aws --endpoint-url="${LOCALSTACK_URL}" apigateway get-rest-apis \
    --region "${REGION}" \
    --query "items[?name=='PDF Extractor API'].id" --output text 2>/dev/null || true)

if [ -z "${API_ID}" ] || [ "${API_ID}" = "None" ]; then
    API_ID=$(aws --endpoint-url="${LOCALSTACK_URL}" apigateway create-rest-api \
        --name "PDF Extractor API" \
        --region "${REGION}" \
        --query 'id' --output text)

    ROOT_ID=$(aws --endpoint-url="${LOCALSTACK_URL}" apigateway get-resources \
        --rest-api-id "${API_ID}" --region "${REGION}" \
        --query 'items[0].id' --output text)

    PROXY_ID=$(aws --endpoint-url="${LOCALSTACK_URL}" apigateway create-resource \
        --rest-api-id "${API_ID}" \
        --parent-id "${ROOT_ID}" \
        --path-part "{proxy+}" \
        --region "${REGION}" \
        --query 'id' --output text)

    aws --endpoint-url="${LOCALSTACK_URL}" apigateway put-method \
        --rest-api-id "${API_ID}" \
        --resource-id "${PROXY_ID}" \
        --http-method ANY \
        --authorization-type NONE \
        --region "${REGION}" > /dev/null

    LAMBDA_ARN="arn:aws:lambda:${REGION}:000000000000:function:${FUNCTION}"

    aws --endpoint-url="${LOCALSTACK_URL}" apigateway put-integration \
        --rest-api-id "${API_ID}" \
        --resource-id "${PROXY_ID}" \
        --http-method ANY \
        --type AWS_PROXY \
        --integration-http-method POST \
        --uri "arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/${LAMBDA_ARN}/invocations" \
        --region "${REGION}" > /dev/null

    aws --endpoint-url="${LOCALSTACK_URL}" apigateway create-deployment \
        --rest-api-id "${API_ID}" \
        --stage-name local \
        --region "${REGION}" > /dev/null

    echo "   ✅ API Gateway created"
else
    # Redeploy existing API
    aws --endpoint-url="${LOCALSTACK_URL}" apigateway create-deployment \
        --rest-api-id "${API_ID}" \
        --stage-name local \
        --region "${REGION}" > /dev/null
    echo "   ✅ API Gateway redeployed"
fi

API_URL="${LOCALSTACK_URL}/restapis/${API_ID}/local/_user_request_"

echo ""
echo "========================================="
echo "  🚀 Deployment complete!"
echo "========================================="
echo ""
echo "  API Gateway : ${API_URL}"
echo "  Lambda      : ${FUNCTION}"
echo "  S3 Bucket   : ${BUCKET}"
echo ""
echo "  Test it:"
echo "    curl ${API_URL}/api/uploads"
echo ""
echo "    curl -X POST ${API_URL}/api/upload \\"
echo "      -F 'file=@sample.pdf'"
echo ""
echo "========================================="
