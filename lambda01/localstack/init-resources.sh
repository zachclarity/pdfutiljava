#!/bin/bash
# =============================================================================
#  LocalStack init script
#  Runs automatically when LocalStack starts (mounted to /etc/localstack/init/ready.d/)
#
#  Creates:  S3 bucket  →  Lambda function  →  API Gateway (REST)
# =============================================================================

set -euo pipefail

REGION="us-east-1"
BUCKET="pdf-uploads"
FUNCTION="pdf-extractor"
LAMBDA_JAR="/opt/code/localstack/lambda.jar"

echo "========================================="
echo "  Initializing LocalStack resources"
echo "========================================="

# ---- S3 Bucket ----
echo "[1/4] Creating S3 bucket: ${BUCKET}"
awslocal s3 mb "s3://${BUCKET}" --region "${REGION}" 2>/dev/null || true

# ---- Wait for Lambda JAR to be available ----
echo "[2/4] Waiting for Lambda JAR..."
TRIES=0
while [ ! -f "${LAMBDA_JAR}" ] && [ $TRIES -lt 60 ]; do
    sleep 2
    TRIES=$((TRIES + 1))
done

if [ ! -f "${LAMBDA_JAR}" ]; then
    echo "ERROR: Lambda JAR not found at ${LAMBDA_JAR} after 120 seconds"
    echo "The Maven build may still be running. Resources will be created by deploy.sh instead."
    exit 0
fi

# ---- Lambda Function ----
echo "[3/4] Creating Lambda function: ${FUNCTION}"
awslocal lambda create-function \
    --function-name "${FUNCTION}" \
    --runtime java21 \
    --handler com.pdfextractor.handler.PdfLambdaHandler \
    --role arn:aws:iam::000000000000:role/lambda-role \
    --zip-file "fileb://${LAMBDA_JAR}" \
    --timeout 120 \
    --memory-size 1024 \
    --environment "Variables={S3_BUCKET=${BUCKET},AWS_ENDPOINT_URL=http://localhost.localstack.cloud:4566,AWS_REGION=${REGION}}" \
    --region "${REGION}" 2>/dev/null || \
awslocal lambda update-function-code \
    --function-name "${FUNCTION}" \
    --zip-file "fileb://${LAMBDA_JAR}" \
    --region "${REGION}"

# ---- API Gateway ----
echo "[4/4] Creating API Gateway"

# Create REST API
API_ID=$(awslocal apigateway create-rest-api \
    --name "PDF Extractor API" \
    --region "${REGION}" \
    --query 'id' --output text)

ROOT_ID=$(awslocal apigateway get-resources \
    --rest-api-id "${API_ID}" \
    --region "${REGION}" \
    --query 'items[0].id' --output text)

# Create {proxy+} resource to catch all /api/* routes
PROXY_ID=$(awslocal apigateway create-resource \
    --rest-api-id "${API_ID}" \
    --parent-id "${ROOT_ID}" \
    --path-part "{proxy+}" \
    --region "${REGION}" \
    --query 'id' --output text)

# ANY method on proxy resource
awslocal apigateway put-method \
    --rest-api-id "${API_ID}" \
    --resource-id "${PROXY_ID}" \
    --http-method ANY \
    --authorization-type NONE \
    --region "${REGION}"

# Lambda integration
LAMBDA_ARN="arn:aws:lambda:${REGION}:000000000000:function:${FUNCTION}"

awslocal apigateway put-integration \
    --rest-api-id "${API_ID}" \
    --resource-id "${PROXY_ID}" \
    --http-method ANY \
    --type AWS_PROXY \
    --integration-http-method POST \
    --uri "arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/${LAMBDA_ARN}/invocations" \
    --region "${REGION}"

# Deploy
awslocal apigateway create-deployment \
    --rest-api-id "${API_ID}" \
    --stage-name local \
    --region "${REGION}"

echo ""
echo "========================================="
echo "  LocalStack setup complete!"
echo "========================================="
echo "  S3 Bucket : ${BUCKET}"
echo "  Lambda    : ${FUNCTION}"
echo "  API GW    : http://localhost:4566/restapis/${API_ID}/local/_user_request_"
echo "========================================="

# Save the API ID so the deploy script and nginx can find it
echo "${API_ID}" > /tmp/api_gateway_id
