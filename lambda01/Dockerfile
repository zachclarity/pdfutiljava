# =============================================================================
#  Multi-stage Dockerfile
#  Stage 1: Maven build → fat JAR
#  Stage 2: Minimal image that just holds the JAR for LocalStack volume mount
# =============================================================================

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# ---- Output stage ----
# Tiny image; the JAR is volume-mounted into LocalStack
FROM alpine:3.19 AS output

WORKDIR /lambda
COPY --from=builder /build/target/pdfbox-lambda-*.jar lambda.jar

# Keep container alive so docker-compose can volume-mount the JAR
CMD ["sh", "-c", "cp /lambda/lambda.jar /opt/lambda-output/lambda.jar && echo 'JAR copied' && tail -f /dev/null"]
