# PDF Extractor – Lambda + LocalStack

A PDF text/image extraction service running as **AWS Lambda functions** on **LocalStack**, with S3 storage and API Gateway routing. Converted from the original Spring Boot REST API.

## Architecture

```
┌──────────┐       ┌──────────────┐       ┌────────────────────────────────────────────┐
│  Browser  │──────▶│ Nginx (:3000)│──────▶│            LocalStack (:4566)              │
│           │       │   Frontend   │  /api  │                                            │
└──────────┘       └──────────────┘       │  ┌─────────────┐   ┌───────────────────┐   │
                                           │  │ API Gateway  │──▶│  Lambda (Java 17) │   │
                                           │  │ (REST, proxy)│   │  PdfLambdaHandler │   │
                                           │  └─────────────┘   └────────┬──────────┘   │
                                           │                             │              │
                                           │                    ┌────────▼──────────┐   │
                                           │                    │    S3 Bucket       │   │
                                           │                    │  "pdf-uploads"     │   │
                                           │                    └───────────────────┘   │
                                           └────────────────────────────────────────────┘
```

## What Changed from the Original

| Aspect | Before (Spring Boot) | After (Lambda + LocalStack) |
|---|---|---|
| Runtime | Spring Boot embedded Tomcat | AWS Lambda (Java 17) |
| Routing | `@RestController` / `@RequestMapping` | Single handler with regex routing |
| Storage | Local filesystem (`/app/uploads`) | S3 bucket via AWS SDK v2 |
| Packaging | Spring Boot fat JAR | Maven Shade fat JAR |
| Infrastructure | Docker container | LocalStack (S3 + Lambda + API Gateway) |
| Dependencies | `spring-boot-starter-web` | `aws-lambda-java-core` + `aws-sdk-s3` |

## API Endpoints (unchanged)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/upload` | Upload and process a PDF |
| `GET` | `/api/uploads` | List all processed uploads |
| `GET` | `/api/uploads/{id}/text` | Get extracted text |
| `GET` | `/api/uploads/{id}/images/{name}` | Get an extracted image |
| `GET` | `/api/uploads/{id}/pdf` | Download original PDF |
| `DELETE` | `/api/uploads/{id}` | Delete an upload |

## Quick Start

### Prerequisites

- Docker & Docker Compose
- (Optional) Maven 3.9+ and Java 17 for local builds
- (Optional) AWS CLI for manual interaction

### Option 1: Docker Compose (fully automated)

```bash
docker-compose up --build
```

This will:
1. Build the Lambda JAR in a Maven container
2. Start LocalStack with S3, Lambda, and API Gateway
3. Auto-deploy the Lambda function and API Gateway routes
4. Start the Nginx frontend that proxies to the Lambda API

Then open **http://localhost:3000** in your browser.

### Option 2: Local build + deploy script

```bash
# 1. Start LocalStack only
docker-compose up localstack -d

# 2. Build and deploy
./scripts/deploy.sh

# 3. Start the frontend
docker-compose up frontend -d
```

## Project Structure

```
pdfutiljava-lambda/
├── pom.xml                          # Maven config (Lambda + S3 deps)
├── Dockerfile                       # Multi-stage build for Lambda JAR
├── docker-compose.yml               # LocalStack + builder + frontend
├── src/main/java/com/pdfextractor/
│   ├── handler/
│   │   └── PdfLambdaHandler.java    # Lambda entry point (routes all API calls)
│   ├── service/
│   │   └── PdfExtractorService.java # PDF processing (PDFBox → S3)
│   └── util/
│       └── S3Helper.java            # S3 client configured for LocalStack
├── localstack/
│   └── init-resources.sh            # Auto-creates S3 + Lambda + API GW
├── scripts/
│   └── deploy.sh                    # Manual build & deploy to LocalStack
└── frontend/
    ├── Dockerfile                   # Nginx + API GW discovery
    ├── entrypoint.sh                # Discovers API GW ID at startup
    ├── nginx.conf.template          # Nginx proxy config (templated)
    └── index.html                   # Frontend UI (unchanged)
```

## Testing with cURL

```bash
# Get the API Gateway URL (after deployment)
API_URL="http://localhost:4566/restapis/$(aws --endpoint-url=http://localhost:4566 \
  apigateway get-rest-apis --query 'items[0].id' --output text)/local/_user_request_"

# List uploads (empty initially)
curl -s "$API_URL/api/uploads" | jq .

# Upload a PDF
curl -X POST "$API_URL/api/upload" \
  -H "Content-Type: application/pdf" \
  --data-binary @sample.pdf

# Get extracted text
curl -s "$API_URL/api/uploads/<id>/text" | jq .

# Delete an upload
curl -X DELETE "$API_URL/api/uploads/<id>"
```

## S3 Bucket Layout

Each upload creates this structure in the `pdf-uploads` bucket:

```
{uuid}/
  ├── original.pdf
  ├── extracted_text.txt
  └── images/
      ├── page1_image1.png
      ├── page1_image2.jpg
      └── ...
```

Browse the bucket:

```bash
aws --endpoint-url=http://localhost:4566 s3 ls s3://pdf-uploads/ --recursive
```

## Configuration

Environment variables used by the Lambda function:

| Variable | Default | Description |
|---|---|---|
| `S3_BUCKET` | `pdf-uploads` | S3 bucket name |
| `AWS_ENDPOINT_URL` | `http://localstack:4566` | LocalStack endpoint |
| `AWS_REGION` | `us-east-1` | AWS region |

## Moving to Real AWS

To deploy to actual AWS Lambda:

1. Remove the `AWS_ENDPOINT_URL` env var and the `forcePathStyle(true)` from `S3Helper.java`
2. Create a real S3 bucket and IAM role with S3 permissions
3. Deploy the fat JAR using `aws lambda create-function` or SAM/CDK
4. Set up API Gateway with a `{proxy+}` resource pointing to the Lambda
5. Configure binary media types in API Gateway for image/PDF responses

## License

Same license as the original project.
