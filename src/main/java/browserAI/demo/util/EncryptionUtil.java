package browserAI.demo.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption utility for credential vault.
 * Uses random IV per encryption for maximum security.
 * Sensitive byte arrays are zeroed after use to minimize memory exposure.
 */
@Component
public class EncryptionUtil {

    private static final Logger log = LoggerFactory.getLogger(EncryptionUtil.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom;

    public EncryptionUtil(@Value("${encryption.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "Encryption secret is required. Set ENCRYPTION_SECRET environment variable (min 32 chars).");
        }
        if (secret.length() < 32) {
            throw new IllegalArgumentException(
                    "Encryption secret must be at least 32 characters. Current length: " + secret.length());
        }
        if (secret.contains("default") || secret.contains("example") || secret.contains("change")) {
            log.warn("⚠ Encryption key looks like a placeholder — set ENCRYPTION_SECRET env var before production deployment!");
        }
        byte[] keyBytes = secret.substring(0, 32).getBytes(StandardCharsets.UTF_8);
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
        Arrays.fill(keyBytes, (byte) 0);
        this.secureRandom = new SecureRandom();
        log.info("EncryptionUtil initialized with AES-256-GCM");
    }

    public String encrypt(String plaintext) {
        byte[] plaintextBytes = null;
        byte[] iv = new byte[IV_LENGTH];
        try {
            secureRandom.nextBytes(iv);
            plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);

            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            ByteBuffer byteBuffer = ByteBuffer.allocate(IV_LENGTH + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            log.error("Encryption failed");
            throw new RuntimeException("Encryption failed", e);
        } finally {
            if (plaintextBytes != null) Arrays.fill(plaintextBytes, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    public String decrypt(String encryptedText) {
        byte[] decoded = null;
        byte[] iv = new byte[IV_LENGTH];
        byte[] ciphertext = null;
        byte[] plaintext = null;
        try {
            decoded = Base64.getDecoder().decode(encryptedText);

            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byteBuffer.get(iv);
            ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);

            plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed");
            throw new RuntimeException("Decryption failed", e);
        } finally {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }
}
