package in.neuw.kbootar.auth;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;

@Service
public class CredentialVault {

    private static final Logger log = LoggerFactory.getLogger(CredentialVault.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String ENC_PREFIX = "enc:";
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    static {
        // idempotent; -1 returned if already registered
        Security.addProvider(new BouncyCastleProvider());
    }

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public CredentialVault(@Value("${kbootar.encryption.key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "KBOOTAR_ENCRYPTION_KEY must be a base64-encoded 32-byte (256-bit) key");
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
        log.info("CredentialVault initialised");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM, "BC");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes());

            byte[] combined = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, combined, IV_BYTES, ciphertext.length);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new VaultException("Encryption failed", e);
        }
    }

    public String decrypt(String encryptedValue) {
        try {
            String base64 = encryptedValue.startsWith(ENC_PREFIX)
                    ? encryptedValue.substring(ENC_PREFIX.length())
                    : encryptedValue;

            byte[] combined = Base64.getDecoder().decode(base64);
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM, "BC");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));

            return new String(cipher.doFinal(ciphertext));
        } catch (Exception e) {
            throw new VaultException("Decryption failed", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }

    public String decryptIfEncrypted(String value) {
        return isEncrypted(value) ? decrypt(value) : value;
    }
}
