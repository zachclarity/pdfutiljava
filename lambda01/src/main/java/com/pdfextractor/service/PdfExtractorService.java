package com.pdfextractor.service;

import com.pdfextractor.util.S3Helper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF processing service that stores results in S3 (via LocalStack).
 * Mirrors the original PdfExtractorService but replaces filesystem I/O with S3 operations.
 */
public class PdfExtractorService {

    /**
     * Extracts all text content from a PDF file.
     */
    public String extractText(File pdfFile) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFTextStripper textStripper = new PDFTextStripper();
            textStripper.setSortByPosition(true);
            return textStripper.getText(document);
        }
    }

    /**
     * Extracts all images from a PDF file and uploads them to S3.
     *
     * @param pdfFile   the local PDF file
     * @param s3Prefix  S3 key prefix, e.g. "{uniqueId}/images/"
     * @return list of image file names (not full keys)
     */
    public List<String> extractImages(File pdfFile, String s3Prefix) throws IOException {
        List<String> extractedImages = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            int pageNumber = 0;
            int imageCount = 0;

            for (PDPage page : document.getPages()) {
                pageNumber++;
                PDResources resources = page.getResources();
                if (resources == null) continue;

                for (COSName xObjectName : resources.getXObjectNames()) {
                    PDXObject xObject = resources.getXObject(xObjectName);

                    if (xObject instanceof PDImageXObject image) {
                        imageCount++;
                        BufferedImage bufferedImage = image.getImage();
                        String suffix = image.getSuffix();
                        if (suffix == null || suffix.isEmpty()) {
                            suffix = "png";
                        }

                        String imageName = String.format("page%d_image%d.%s", pageNumber, imageCount, suffix);

                        // Write to byte array instead of filesystem
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(bufferedImage, suffix, baos);
                        byte[] imageBytes = baos.toByteArray();

                        String contentType = "image/" + (suffix.equals("jpg") ? "jpeg" : suffix);
                        S3Helper.putObject(s3Prefix + imageName, imageBytes, contentType);

                        extractedImages.add(imageName);
                    }
                }
            }
        }
        return extractedImages;
    }

    /**
     * Full pipeline: extract text + images, store everything in S3.
     *
     * S3 layout for each upload:
     *   {uniqueId}/original.pdf
     *   {uniqueId}/extracted_text.txt
     *   {uniqueId}/images/page1_image1.png
     *   {uniqueId}/images/page1_image2.jpg
     *   ...
     */
    public ProcessingResult processPdf(byte[] pdfBytes, String uniqueId) throws IOException {
        // Write PDF bytes to a temp file so PDFBox can load it
        File tempFile = File.createTempFile("pdf_upload_", ".pdf");
        try {
            Files.write(tempFile.toPath(), pdfBytes);

            // Store original PDF in S3
            S3Helper.putObject(uniqueId + "/original.pdf", pdfBytes, "application/pdf");

            // Extract text
            String extractedText = extractText(tempFile);
            S3Helper.putObject(
                    uniqueId + "/extracted_text.txt",
                    extractedText.getBytes(),
                    "text/plain");

            // Extract images
            List<String> imageNames = extractImages(tempFile, uniqueId + "/images/");

            return new ProcessingResult(uniqueId, extractedText, imageNames);
        } finally {
            tempFile.delete();
        }
    }

    // ---- Result DTO ----

    public static class ProcessingResult {
        private final String id;
        private final String extractedText;
        private final List<String> imageNames;

        public ProcessingResult(String id, String extractedText, List<String> imageNames) {
            this.id = id;
            this.extractedText = extractedText;
            this.imageNames = imageNames;
        }

        public String getId() { return id; }
        public String getExtractedText() { return extractedText; }
        public List<String> getImageNames() { return imageNames; }
    }
}
