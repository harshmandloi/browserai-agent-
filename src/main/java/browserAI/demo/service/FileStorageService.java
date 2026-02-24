package browserAI.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * File Storage Service.
 * POC: Local filesystem at /storage/{userId}/{fileName}
 * Production: Swap with S3 implementation.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    @Value("${storage.base-path}")
    private String basePath;

    /**
     * Moves a file from temp location to permanent storage.
     * Directory structure: {basePath}/{userId}/{fileName}
     */
    public Path moveToStorage(File sourceFile, String userId, String fileName) throws IOException {
        Path userDir = Paths.get(basePath, userId);
        Files.createDirectories(userDir);

        Path destination = userDir.resolve(fileName);

        // Handle filename conflicts
        if (Files.exists(destination)) {
            String name = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            String ext = fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf('.'))
                    : "";
            destination = userDir.resolve(name + "_" + System.currentTimeMillis() + ext);
        }

        Files.move(sourceFile.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
        log.info("File moved to permanent storage: {}", destination);

        return destination;
    }

    /**
     * Copies the document to the user's local Downloads folder so it's
     * immediately accessible from Finder / file manager.
     * Returns the local path if copy succeeded, null otherwise.
     */
    public String copyToLocalDownloads(String storedFilePath, String portal, String documentType, String reference) {
        try {
            String userHome = System.getProperty("user.home");
            Path downloadsDir = Paths.get(userHome, "Downloads");
            if (!Files.isDirectory(downloadsDir)) {
                log.warn("Downloads folder not found: {}", downloadsDir);
                return null;
            }

            File source = new File(storedFilePath);
            if (!source.exists()) {
                log.warn("Source file doesn't exist: {}", storedFilePath);
                return null;
            }

            String ext = storedFilePath.contains(".")
                    ? storedFilePath.substring(storedFilePath.lastIndexOf('.'))
                    : ".pdf";
            String cleanPortal = portal.substring(0, 1).toUpperCase() + portal.substring(1);
            String cleanType = documentType.replace("_", " ");
            cleanType = cleanType.substring(0, 1).toUpperCase() + cleanType.substring(1);
            String ref = (reference != null && !reference.isBlank()) ? "_" + reference : "";

            String localFileName = "%s_%s%s%s".formatted(cleanPortal, cleanType, ref, ext);
            Path destination = downloadsDir.resolve(localFileName);

            // Handle conflicts
            if (Files.exists(destination)) {
                String baseName = localFileName.contains(".")
                        ? localFileName.substring(0, localFileName.lastIndexOf('.'))
                        : localFileName;
                destination = downloadsDir.resolve(baseName + "_" + System.currentTimeMillis() + ext);
            }

            Files.copy(source.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
            log.info("Invoice copied to local Downloads: {}", destination);
            return destination.toString();
        } catch (Exception e) {
            log.warn("Failed to copy to local Downloads: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Retrieves a file from storage.
     */
    public File getFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new RuntimeException("File not found at path: " + filePath);
        }
        return file;
    }

    /**
     * Deletes a file from storage.
     */
    public boolean deleteFile(String filePath) {
        try {
            return Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            log.error("Failed to delete file: {}", filePath, e);
            return false;
        }
    }
}
