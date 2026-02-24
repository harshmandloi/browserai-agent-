package browserAI.demo.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores structured data extracted from downloaded PDFs via Gemini.
 * Links to the Document entity and the user who owns it.
 */
@Entity
@Table(name = "extracted_document_data")
public class ExtractedDocumentData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(length = 100)
    private String portal;

    @Column(name = "document_type", length = 100)
    private String documentType;

    @Column(length = 500)
    private String reference;

    @Column(name = "extracted_json", nullable = false, columnDefinition = "TEXT")
    private String extractedJson;

    @Column(name = "raw_text", columnDefinition = "TEXT")
    private String rawText;

    @Column(name = "total_amount", length = 100)
    private String totalAmount;

    @Column(name = "invoice_date", length = 100)
    private String invoiceDate;

    @Column(name = "invoice_number", length = 200)
    private String invoiceNumber;

    @Column(name = "vendor_name", length = 200)
    private String vendorName;

    @Column(name = "tax_amount", length = 100)
    private String taxAmount;

    @Column(length = 100)
    private String currency;

    @Column(name = "extraction_tokens")
    private Integer extractionTokens;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPortal() { return portal; }
    public void setPortal(String portal) { this.portal = portal; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getExtractedJson() { return extractedJson; }
    public void setExtractedJson(String extractedJson) { this.extractedJson = extractedJson; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public String getTotalAmount() { return totalAmount; }
    public void setTotalAmount(String totalAmount) { this.totalAmount = totalAmount; }

    public String getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(String invoiceDate) { this.invoiceDate = invoiceDate; }

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public String getVendorName() { return vendorName; }
    public void setVendorName(String vendorName) { this.vendorName = vendorName; }

    public String getTaxAmount() { return taxAmount; }
    public void setTaxAmount(String taxAmount) { this.taxAmount = taxAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Integer getExtractionTokens() { return extractionTokens; }
    public void setExtractionTokens(Integer extractionTokens) { this.extractionTokens = extractionTokens; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
