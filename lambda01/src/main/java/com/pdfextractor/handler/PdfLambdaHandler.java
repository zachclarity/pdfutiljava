package com.pdfextractor.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.pdfextractor.service.PdfExtractorService;
import com.pdfextractor.util.S3Helper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single Lambda handler that routes API Gateway events to the correct action.
 *
 * Route mapping (mirrors the original Spring Boot controller):
 *   POST   /api/upload                        → uploadPdf
 *   GET    /api/uploads                        → listUploads
 *   GET    /api/uploads/{id}/text              → getText
 *   GET    /api/uploads/{id}/images/{name}     → getImage
 *   GET    /api/uploads/{id}/pdf               → getPdf
 *   DELETE /api/uploads/{id}                   → deleteUpload
 */
public class PdfLambdaHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final PdfExtractorService pdfService = new PdfExtractorService();

    // Route patterns
    private static final Pattern UPLOAD_TEXT   = Pattern.compile("^/api/uploads/([^/]+)/text$");
    private static final Pattern UPLOAD_IMAGE  = Pattern.compile("^/api/uploads/([^/]+)/images/([^/]+)$");
    private static final Pattern UPLOAD_PDF    = Pattern.compile("^/api/uploads/([^/]+)/pdf$");
    private static final Pattern UPLOAD_BY_ID  = Pattern.compile("^/api/uploads/([^/]+)$");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        String path   = event.getPath() != null ? event.getPath() : "";
        String method = event.getHttpMethod() != null ? event.getHttpMethod() : "";

        context.getLogger().log("Received " + method + " " + path);

        try {
            // POST /api/upload
            if ("POST".equalsIgnoreCase(method) && "/api/upload".equals(path)) {
                return handleUpload(event);
            }

            // GET /api/uploads
            if ("GET".equalsIgnoreCase(method) && "/api/uploads".equals(path)) {
                return handleListUploads();
            }

            Matcher m;

            // GET /api/uploads/{id}/text
            m = UPLOAD_TEXT.matcher(path);
            if ("GET".equalsIgnoreCase(method) && m.matches()) {
                return handleGetText(m.group(1));
            }

            // GET /api/uploads/{id}/images/{name}
            m = UPLOAD_IMAGE.matcher(path);
            if ("GET".equalsIgnoreCase(method) && m.matches()) {
                return handleGetImage(m.group(1), m.group(2));
            }

            // GET /api/uploads/{id}/pdf
            m = UPLOAD_PDF.matcher(path);
            if ("GET".equalsIgnoreCase(method) && m.matches()) {
                return handleGetPdf(m.group(1));
            }

            // DELETE /api/uploads/{id}
            m = UPLOAD_BY_ID.matcher(path);
            if ("DELETE".equalsIgnoreCase(method) && m.matches()) {
                return handleDelete(m.group(1));
            }

            return jsonResponse(404, Map.of("success", false, "error", "Not found: " + method + " " + path));

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return jsonResponse(500, Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ========================================================================
    //  POST /api/upload
    //  Accepts base64-encoded PDF in the request body (API Gateway binary)
    // ========================================================================
    private APIGatewayProxyResponseEvent handleUpload(APIGatewayProxyRequestEvent event) throws Exception {
        byte[] pdfBytes;

        // API Gateway may send the body as base64 when binary media types are configured
        if (Boolean.TRUE.equals(event.getIsBase64Encoded())) {
            pdfBytes = Base64.getDecoder().decode(event.getBody());
        } else {
            // Try to parse as multipart form data (simplified extraction)
            String body = event.getBody();
            if (body == null || body.isEmpty()) {
                return jsonResponse(400, Map.of("success", false, "error", "Empty request body"));
            }

            // Check if it's a multipart form
            String contentType = getHeader(event, "Content-Type");
            if (contentType != null && contentType.contains("multipart/form-data")) {
                pdfBytes = extractPdfFromMultipart(body, contentType, event.getIsBase64Encoded());
            } else {
                // Assume raw binary PDF
                pdfBytes = Base64.getDecoder().decode(body);
            }
        }

        if (pdfBytes == null || pdfBytes.length == 0) {
            return jsonResponse(400, Map.of("success", false, "error", "No PDF data received"));
        }

        String uniqueId = UUID.randomUUID().toString();
        PdfExtractorService.ProcessingResult result = pdfService.processPdf(pdfBytes, uniqueId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("id", result.getId());
        response.put("extractedText", result.getExtractedText());
        response.put("imageCount", result.getImageNames().size());
        response.put("images", result.getImageNames());

        return jsonResponse(200, response);
    }

    // ========================================================================
    //  GET /api/uploads
    // ========================================================================
    private APIGatewayProxyResponseEvent handleListUploads() {
        List<String> prefixes = S3Helper.listTopLevelPrefixes();
        List<Map<String, Object>> uploads = new ArrayList<>();

        for (String id : prefixes) {
            Map<String, Object> upload = new LinkedHashMap<>();
            upload.put("id", id);
            upload.put("hasText", S3Helper.objectExists(id + "/extracted_text.txt"));
            upload.put("hasPdf", S3Helper.objectExists(id + "/original.pdf"));

            // List images
            List<String> imageKeys = S3Helper.listKeys(id + "/images/");
            List<String> imageNames = imageKeys.stream()
                    .map(k -> k.substring(k.lastIndexOf('/') + 1))
                    .toList();
            upload.put("images", imageNames);
            upload.put("imageCount", imageNames.size());

            uploads.add(upload);
        }

        return jsonResponse(200, uploads);
    }

    // ========================================================================
    //  GET /api/uploads/{id}/text
    // ========================================================================
    private APIGatewayProxyResponseEvent handleGetText(String id) {
        String key = id + "/extracted_text.txt";
        if (!S3Helper.objectExists(key)) {
            return jsonResponse(404, Map.of("success", false, "error", "Text not found"));
        }
        String text = S3Helper.getObjectAsString(key);
        return jsonResponse(200, Map.of("success", true, "text", text));
    }

    // ========================================================================
    //  GET /api/uploads/{id}/images/{imageName}
    //  Returns binary image with base64 encoding for API Gateway
    // ========================================================================
    private APIGatewayProxyResponseEvent handleGetImage(String id, String imageName) {
        String key = id + "/images/" + imageName;
        if (!S3Helper.objectExists(key)) {
            return notFound();
        }
        byte[] data = S3Helper.getObject(key);
        String contentType = S3Helper.getContentType(key);

        return binaryResponse(200, data, contentType);
    }

    // ========================================================================
    //  GET /api/uploads/{id}/pdf
    // ========================================================================
    private APIGatewayProxyResponseEvent handleGetPdf(String id) {
        String key = id + "/original.pdf";
        if (!S3Helper.objectExists(key)) {
            return notFound();
        }
        byte[] data = S3Helper.getObject(key);
        return binaryResponse(200, data, "application/pdf");
    }

    // ========================================================================
    //  DELETE /api/uploads/{id}
    // ========================================================================
    private APIGatewayProxyResponseEvent handleDelete(String id) {
        List<String> keys = S3Helper.listKeys(id + "/");
        if (keys.isEmpty()) {
            return jsonResponse(404, Map.of("success", false, "error", "Upload not found"));
        }
        S3Helper.deletePrefix(id + "/");
        return jsonResponse(200, Map.of("success", true, "message", "Upload deleted successfully"));
    }

    // ========================================================================
    //  Helpers
    // ========================================================================

    private APIGatewayProxyResponseEvent jsonResponse(int statusCode, Object body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(corsHeaders("application/json"))
                .withBody(GSON.toJson(body));
    }

    private APIGatewayProxyResponseEvent binaryResponse(int statusCode, byte[] data, String contentType) {
        Map<String, String> headers = corsHeaders(contentType);
        headers.put("Content-Disposition", "inline");
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(headers)
                .withBody(Base64.getEncoder().encodeToString(data))
                .withIsBase64Encoded(true);
    }

    private APIGatewayProxyResponseEvent notFound() {
        return jsonResponse(404, Map.of("success", false, "error", "Not found"));
    }

    private Map<String, String> corsHeaders(String contentType) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");
        return headers;
    }

    private String getHeader(APIGatewayProxyRequestEvent event, String name) {
        if (event.getHeaders() == null) return null;
        // Headers may be case-insensitive
        for (Map.Entry<String, String> e : event.getHeaders().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    /**
     * Simplified multipart extraction – pulls the first binary part from the body.
     */
    private byte[] extractPdfFromMultipart(String body, String contentType, Boolean isBase64Encoded) {
        try {
            byte[] bodyBytes;
            if (Boolean.TRUE.equals(isBase64Encoded)) {
                bodyBytes = Base64.getDecoder().decode(body);
            } else {
                bodyBytes = body.getBytes();
            }

            // Find the boundary from Content-Type
            String boundary = null;
            for (String part : contentType.split(";")) {
                part = part.trim();
                if (part.startsWith("boundary=")) {
                    boundary = part.substring("boundary=".length()).trim();
                    if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                        boundary = boundary.substring(1, boundary.length() - 1);
                    }
                    break;
                }
            }

            if (boundary == null) {
                // Fall back: treat entire body as PDF bytes
                return bodyBytes;
            }

            // Simple multipart parser: find the PDF content between boundaries
            String bodyStr = new String(bodyBytes);
            String sep = "--" + boundary;
            String[] parts = bodyStr.split(sep);

            for (String part : parts) {
                if (part.contains("application/pdf") || part.contains("filename")) {
                    // Find the double newline that separates headers from body
                    int headerEnd = part.indexOf("\r\n\r\n");
                    if (headerEnd == -1) headerEnd = part.indexOf("\n\n");
                    if (headerEnd != -1) {
                        String fileContent = part.substring(headerEnd + (part.charAt(headerEnd + 1) == '\n' ? 2 : 4));
                        // Remove trailing boundary marker
                        if (fileContent.endsWith("\r\n")) {
                            fileContent = fileContent.substring(0, fileContent.length() - 2);
                        }
                        if (fileContent.endsWith("--\r\n") || fileContent.endsWith("--")) {
                            fileContent = fileContent.replaceAll("--\\s*$", "");
                        }
                        return fileContent.getBytes();
                    }
                }
            }

            return bodyBytes;
        } catch (Exception e) {
            return null;
        }
    }
}
