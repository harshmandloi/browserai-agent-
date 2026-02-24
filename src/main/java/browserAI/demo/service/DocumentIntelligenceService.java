package browserAI.demo.service;

import browserAI.demo.model.entity.Document;
import browserAI.demo.model.entity.ExtractedDocumentData;
import browserAI.demo.repository.DocumentRepository;
import browserAI.demo.repository.ExtractedDocumentDataRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

/**
 * Document Intelligence Service — extracts structured data from downloaded PDFs.
 * Uses Apache PDFBox for text extraction and Gemini for intelligent data parsing.
 * Stores extracted data in DB linked to the document and user.
 */
@Service
public class DocumentIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIntelligenceService.class);

    private final DocumentRepository documentRepository;
    private final ExtractedDocumentDataRepository extractedDataRepository;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;

    public DocumentIntelligenceService(DocumentRepository documentRepository,
                                        ExtractedDocumentDataRepository extractedDataRepository,
                                        GeminiService geminiService,
                                        ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.extractedDataRepository = extractedDataRepository;
        this.geminiService = geminiService;
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts text from a PDF file, sends it to Gemini for analysis,
     * and stores the structured result in the database.
     */
    public ExtractedDocumentData extractAndStore(Document document) {
        if (document == null) return null;

        if (extractedDataRepository.existsByDocumentId(document.getId())) {
            log.info("[DocIntel] Data already extracted for document {}", document.getId());
            return extractedDataRepository.findByDocumentId(document.getId()).orElse(null);
        }

        String filePath = document.getFilePath();
        if (filePath == null || !filePath.toLowerCase().endsWith(".pdf")) {
            log.info("[DocIntel] Skipping non-PDF file: {}", filePath);
            return null;
        }

        File pdfFile = new File(filePath);
        if (!pdfFile.exists()) {
            log.warn("[DocIntel] PDF file not found: {}", filePath);
            return null;
        }

        try {
            String rawText = extractTextFromPdf(pdfFile);
            if (rawText == null || rawText.isBlank()) {
                log.warn("[DocIntel] No text extracted from PDF: {}", filePath);
                return null;
            }

            log.info("[DocIntel] Extracted {} chars from PDF: {}", rawText.length(), document.getFileName());

            String analysisJson = geminiService.analyzeDocumentText(
                    rawText, document.getDocumentType(), document.getPortal());

            ExtractedDocumentData data = new ExtractedDocumentData();
            data.setDocumentId(document.getId());
            data.setUserId(document.getUserId());
            data.setPortal(document.getPortal());
            data.setDocumentType(document.getDocumentType());
            data.setReference(document.getReference());
            data.setExtractedJson(analysisJson);
            data.setRawText(rawText.length() > 10000 ? rawText.substring(0, 10000) : rawText);

            try {
                JsonNode parsed = objectMapper.readTree(analysisJson);
                data.setTotalAmount(getJsonField(parsed, "totalAmount"));
                data.setInvoiceDate(getJsonField(parsed, "invoiceDate"));
                data.setInvoiceNumber(getJsonField(parsed, "invoiceNumber"));
                data.setVendorName(getJsonField(parsed, "vendorName"));
                data.setTaxAmount(getJsonField(parsed, "taxAmount"));
                data.setCurrency(getJsonField(parsed, "currency"));
            } catch (Exception e) {
                log.warn("[DocIntel] Could not parse structured fields from analysis JSON");
            }

            ExtractedDocumentData saved = extractedDataRepository.save(data);
            log.info("[DocIntel] Saved extracted data for document {} — amount={}, vendor={}",
                    document.getId(), data.getTotalAmount(), data.getVendorName());
            return saved;

        } catch (Exception e) {
            log.error("[DocIntel] Failed to extract data from document {}: {}", document.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Answers a natural language query about previously downloaded documents.
     */
    public Map<String, Object> queryDocuments(String userId, String portal,
                                               String documentType, String query) {
        log.info("[DocIntel] Query: userId={}, portal={}, type={}, query='{}'",
                userId, portal, documentType, query);

        List<ExtractedDocumentData> dataList;
        if (portal != null && documentType != null) {
            dataList = extractedDataRepository.findByUserIdAndPortalAndDocumentType(userId, portal, documentType);
        } else if (portal != null) {
            dataList = extractedDataRepository.findByUserIdAndPortal(userId, portal);
        } else {
            dataList = extractedDataRepository.findByUserIdOrderByCreatedAtDesc(userId);
        }

        if (dataList.isEmpty()) {
            return Map.of(
                "answer", "No extracted document data found. Please download a document first.",
                "documentsSearched", 0
            );
        }

        // Build context from extracted data
        StringBuilder context = new StringBuilder();
        for (ExtractedDocumentData data : dataList) {
            context.append("--- Document: %s %s (ref: %s) ---\n".formatted(
                    data.getPortal(), data.getDocumentType(), data.getReference()));
            context.append(data.getExtractedJson()).append("\n\n");
        }

        String answerJson = geminiService.analyzeDocumentText(
                "CONTEXT (extracted document data):\n" + context +
                "\n\nQUESTION: " + query +
                "\n\nAnswer the question based on the document data above. Return JSON: {\"answer\": \"<your answer>\", \"source\": \"<document reference>\"}",
                documentType != null ? documentType : "document",
                portal != null ? portal : "multiple"
        );

        try {
            Map<String, Object> result = objectMapper.readValue(answerJson, Map.class);
            result.put("documentsSearched", dataList.size());
            return result;
        } catch (Exception e) {
            return Map.of(
                "answer", answerJson,
                "documentsSearched", dataList.size()
            );
        }
    }

    /**
     * Get extracted data for a specific document.
     */
    public Optional<ExtractedDocumentData> getExtractedData(Long documentId) {
        return extractedDataRepository.findByDocumentId(documentId);
    }

    /**
     * Get all extracted data for a user.
     */
    public List<ExtractedDocumentData> getUserExtractedData(String userId) {
        return extractedDataRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private String extractTextFromPdf(File pdfFile) {
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            log.info("[DocIntel] PDF pages: {}, text length: {}", doc.getNumberOfPages(), text.length());
            return text;
        } catch (Exception e) {
            log.error("[DocIntel] PDF text extraction failed: {}", e.getMessage());
            return null;
        }
    }

    private String getJsonField(JsonNode node, String field) {
        JsonNode val = node.path(field);
        return val.isNull() || val.isMissingNode() ? null : val.asText();
    }
}
