package browserAI.demo.service;

import browserAI.demo.exception.PortalNotSupportedException;
import browserAI.demo.model.entity.Credential;
import browserAI.demo.repository.CredentialRepository;
import browserAI.demo.util.EncryptionUtil;
import browserAI.demo.util.LogMaskingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Credential Vault — stores and retrieves portal credentials securely.
 *
 * Security guarantees:
 * - Passwords are AES-256-GCM encrypted at rest (random IV per encryption)
 * - Decrypted only in memory when needed for portal automation
 * - Credentials NEVER leave the backend
 * - Credentials NEVER sent to Gemini / any LLM
 * - All credential operations are logged with masking
 */
@Service
public class CredentialVaultService {

    private static final Logger log = LoggerFactory.getLogger(CredentialVaultService.class);

    private final CredentialRepository credentialRepository;
    private final EncryptionUtil encryptionUtil;

    public CredentialVaultService(CredentialRepository credentialRepository, EncryptionUtil encryptionUtil) {
        this.credentialRepository = credentialRepository;
        this.encryptionUtil = encryptionUtil;
    }

    /**
     * Stores or updates credentials for a user-portal pair.
     * Password is encrypted before storage.
     */
    public void storeCredential(String userId, String portal, String username, String password) {
        log.info("Storing credential for user={}, portal={}, username={}",
                userId, portal, LogMaskingUtil.maskUsername(username));

        String encryptedPassword = encryptionUtil.encrypt(password);

        Credential credential = credentialRepository
                .findByUserIdAndPortal(userId, portal.toLowerCase())
                .orElse(new Credential());

        credential.setUserId(userId);
        credential.setPortal(portal.toLowerCase());
        credential.setUsername(username);
        credential.setEncryptedPassword(encryptedPassword);

        credentialRepository.save(credential);
        log.info("Credential stored successfully for user={}, portal={}", userId, portal);
    }

    /**
     * Retrieves decrypted credentials for a user-portal pair.
     * Password is decrypted in memory only — never persisted in plain text.
     * The DecryptedCredential record should be used immediately and not stored.
     */
    public DecryptedCredential getCredential(String userId, String portal) {
        log.info("Retrieving credential for user={}, portal={}", userId, portal);

        Credential credential = credentialRepository
                .findByUserIdAndPortal(userId, portal.toLowerCase())
                .orElseThrow(() -> new PortalNotSupportedException(
                        "No credentials found for user=%s, portal=%s. Please store credentials first."
                                .formatted(userId, portal)));

        String decryptedPassword = encryptionUtil.decrypt(credential.getEncryptedPassword());

        log.info("Credential decrypted in-memory for user={}, portal={}, username={}",
                userId, portal, LogMaskingUtil.maskUsername(credential.getUsername()));

        return new DecryptedCredential(credential.getUsername(), decryptedPassword);
    }

    public boolean hasCredential(String userId, String portal) {
        return credentialRepository.existsByUserIdAndPortal(userId, portal.toLowerCase());
    }

    /**
     * In-memory only representation of decrypted credentials.
     * Should be used immediately and not stored in any collection or cache.
     */
    public record DecryptedCredential(String username, String password) {}
}
