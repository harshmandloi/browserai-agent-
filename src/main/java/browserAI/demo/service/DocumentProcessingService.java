package browserAI.demo.service;

import browserAI.demo.model.dto.AgentResponse;
import browserAI.demo.model.entity.Document;
import browserAI.demo.repository.DocumentRepository;
import browserAI.demo.util.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Document Processing Service — handles hashing, deduplication, and metadata storage.
 * Core flow: Hash -> Dedup Check -> Store (or return existing).
 */
@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;

    public DocumentProcessingService(DocumentRepository documentRepository, FileStorageService fileStorageService) {
        this.documentRepository = documentRepository;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Processes a downloaded file:
     * 1. Generates SHA-256 hash
     * 2. Checks for duplicates
     * 3. Moves to permanent storage or returns existing
     */
    public ProcessingResult processDocument(File downloadedFile, String userId, String portal,
                                            String documentType, String reference) throws IOException {
        log.info("Processing document: user={}, portal={}, type={}, ref={}", userId, portal, documentType, reference);

        // Step 1: Generate SHA-256 hash
        String hash = HashUtil.sha256(downloadedFile.toPath());
        log.info("Generated hash: {}", hash);

        // Step 2: Dedup check
        Optional<Document> existing = documentRepository.findByHash(hash);
        if (existing.isPresent()) {
            log.info("Duplicate detected! Existing document id={}", existing.get().getId());

            // Delete the temp downloaded file
            if (downloadedFile.exists()) {
                downloadedFile.delete();
            }

            return new ProcessingResult(existing.get(), true);
        }

        // Step 3: Move to permanent storage
        Path permanentPath = fileStorageService.moveToStorage(downloadedFile, userId, downloadedFile.getName());
        log.info("File stored at: {}", permanentPath);

        // Step 4: Save metadata to DB
        Document document = new Document();
        document.setUserId(userId);
        document.setPortal(portal.toLowerCase());
        document.setDocumentType(documentType.toLowerCase());
        document.setReference(reference);
        document.setHash(hash);
        document.setFilePath(permanentPath.toString());
        document.setFileName(downloadedFile.getName());
        document.setFileSizeBytes(permanentPath.toFile().length());

        Document saved = documentRepository.save(document);
        log.info("Document saved to DB with id={}", saved.getId());

        return new ProcessingResult(saved, false);
    }

    /**
     * Checks if a document already exists by reference match (before downloading).
     */
    public Optional<Document> findExistingByReference(String userId, String portal, String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        return documentRepository.findByUserIdAndPortalAndReference(userId, portal.toLowerCase(), reference);
    }

    public Optional<Document> findExistingByReferenceAndType(String userId, String portal,
                                                              String documentType, String reference) {
        if (reference == null || reference.isBlank() || documentType == null) {
            return Optional.empty();
        }
        return documentRepository.findByUserIdAndPortalAndDocumentTypeAndReference(
                userId, portal.toLowerCase(), documentType.toLowerCase(), reference);
    }

    /**
     * Converts a Document entity to an AgentResponse.DocumentInfo DTO.
     */
    public AgentResponse.DocumentInfo toDocumentInfo(Document document, boolean isDuplicate) {
        AgentResponse.DocumentInfo info = new AgentResponse.DocumentInfo();
        info.setId(document.getId());
        info.setPortal(document.getPortal());
        info.setDocumentType(document.getDocumentType());
        info.setReference(document.getReference());
        info.setHash(document.getHash());
        info.setFileName(document.getFileName());
        info.setFilePath(document.getFilePath());
        info.setDownloadUrl("/api/download/" + document.getId());
        info.setFileSizeBytes(document.getFileSizeBytes());
        info.setDuplicate(isDuplicate);
        return info;
    }

    /**
     * Result of document processing — includes entity and dedup flag.
     */
    public record ProcessingResult(Document document, boolean duplicate) {}
}
