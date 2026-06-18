package in.neuw.kbootar.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialVaultTest {

    // 32 zero-bytes as base64 — test-only
    private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private final CredentialVault vault = new CredentialVault(TEST_KEY);

    @Test
    void encryptAndDecryptRoundTrip() {
        String plaintext = "super-secret-client-id";
        String encrypted = vault.encrypt(plaintext);

        assertThat(encrypted).startsWith("enc:");
        assertThat(vault.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void encryptProducesDifferentCiphertextEachTime() {
        String plaintext = "same-input";
        String enc1 = vault.encrypt(plaintext);
        String enc2 = vault.encrypt(plaintext);

        // different random IVs → different ciphertexts, but both decrypt correctly
        assertThat(enc1).isNotEqualTo(enc2);
        assertThat(vault.decrypt(enc1)).isEqualTo(plaintext);
        assertThat(vault.decrypt(enc2)).isEqualTo(plaintext);
    }

    @Test
    void decryptIfEncrypted_leavesPlaintextAsIs() {
        assertThat(vault.decryptIfEncrypted("plain-value")).isEqualTo("plain-value");
    }

    @Test
    void decryptIfEncrypted_decryptsEncPrefixedValue() {
        String plaintext = "my-secret";
        String encrypted = vault.encrypt(plaintext);

        assertThat(vault.decryptIfEncrypted(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void isEncrypted_detectsPrefix() {
        assertThat(vault.isEncrypted("enc:abc123")).isTrue();
        assertThat(vault.isEncrypted("plaintext")).isFalse();
        assertThat(vault.isEncrypted(null)).isFalse();
    }
}
