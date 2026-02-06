package com.pdfextractor.util;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * S3 helper configured for LocalStack. Reads endpoint and bucket name from
 * environment variables.
 */
public class S3Helper {

    private static final String BUCKET_NAME = System.getenv("S3_BUCKET") != null
            ? System.getenv("S3_BUCKET") : "pdf-uploads";

    private static final S3Client s3;

    static {
        String endpoint = System.getenv("AWS_ENDPOINT_URL") != null
                ? System.getenv("AWS_ENDPOINT_URL") : "http://localstack:4566";
        String region = System.getenv("AWS_REGION") != null
                ? System.getenv("AWS_REGION") : "us-east-1";

        s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true) // Required for LocalStack
                .build();
    }

    public static S3Client client() {
        return s3;
    }

    public static String bucket() {
        return BUCKET_NAME;
    }

    // ---- convenience wrappers ----
    public static void putObject(String key, byte[] data, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(data));
    }

    public static byte[] getObject(String key) {
        ResponseBytes<GetObjectResponse> resp = s3.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(key)
                        .build());
        return resp.asByteArray();
    }

    public static String getObjectAsString(String key) {
        return new String(getObject(key));
    }

    public static boolean objectExists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build());
            return true;
        } catch (S3Exception e) {
            // Removed NoSuchKeyException from the multi-catch because it is a subclass of S3Exception
            return false;
        }
    }

    public static List<String> listKeys(String prefix) {
        ListObjectsV2Response resp = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_NAME)
                        .prefix(prefix)
                        .build());
        return resp.contents().stream()
                .map(S3Object::key)
                .collect(Collectors.toList());
    }

    public static List<String> listTopLevelPrefixes() {
        ListObjectsV2Response resp = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_NAME)
                        .delimiter("/")
                        .build());
        return resp.commonPrefixes().stream()
                .map(CommonPrefix::prefix)
                .map(p -> p.endsWith("/") ? p.substring(0, p.length() - 1) : p)
                .collect(Collectors.toList());
    }

    public static void deletePrefix(String prefix) {
        List<String> keys = listKeys(prefix);
        for (String key : keys) {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build());
        }
    }

    public static String getContentType(String key) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build());
            return head.contentType();
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }
}
