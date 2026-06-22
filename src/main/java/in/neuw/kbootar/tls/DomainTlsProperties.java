package in.neuw.kbootar.tls;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("kbootar.tls")
public record DomainTlsProperties(List<DomainEntry> domains) {

    public DomainTlsProperties {
        domains = domains != null ? domains : List.of();
    }

    public enum Kind { FILE, BASE64 }

    /**
     * TLS configuration for a single hostname pattern.
     *
     * hostname : exact ("api.example.com") or wildcard ("*.example.com")
     * kind     : FILE (default) — use *Path fields pointing to proxy-host filesystem
     *            BASE64         — use *Base64 fields containing base64-encoded content
     *                            (useful when certs are injected via env vars or secrets managers)
     * format   : PEM (default) or PKCS12
     *
     * FILE fields  : clientCertPath, clientKeyPath (PEM only), clientKeyPassword, caCertPath
     * BASE64 fields: clientCertBase64, clientKeyBase64 (PEM only), clientKeyPassword, caCertBase64
     */
    public record DomainEntry(
            String hostname,
            Kind   kind,              // FILE (default) or BASE64

            String format,            // "PEM" (default) or "PKCS12"

            // FILE kind
            String clientCertPath,
            String clientKeyPath,
            String caCertPath,

            // BASE64 kind
            String clientCertBase64,
            String clientKeyBase64,
            String caCertBase64,

            // shared
            String clientKeyPassword
    ) {
        public Kind kindOrDefault() {
            return kind != null ? kind : Kind.FILE;
        }

        public String formatOrDefault() {
            return format != null ? format : "PEM";
        }

        public boolean hasMtls() {
            return switch (kindOrDefault()) {
                case FILE   -> clientCertPath   != null && !clientCertPath.isBlank();
                case BASE64 -> clientCertBase64 != null && !clientCertBase64.isBlank();
            };
        }

        public boolean hasCustomCa() {
            return switch (kindOrDefault()) {
                case FILE   -> caCertPath   != null && !caCertPath.isBlank();
                case BASE64 -> caCertBase64 != null && !caCertBase64.isBlank();
            };
        }
    }
}
