#!/usr/bin/env bash
set -e

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1

alias awslocal="aws --endpoint-url=http://localstack:4566"

echo "Waiting for LocalStack..."
sleep 10

awslocal s3 mb s3://docstore-bucket || true

awslocal dynamodb create-table   --table-name docstore-metadata   --attribute-definitions AttributeName=docId,AttributeType=S   --key-schema AttributeName=docId,KeyType=HASH   --billing-mode PAY_PER_REQUEST || true

awslocal lambda create-function   --function-name docstore-handler   --package-type Image   --code ImageUri=local/docstore-lambda-ocr:latest   --role arn:aws:iam::000000000000:role/lambda-role || true

echo "Init complete"
