package in.neuw.kbootar.collection;

public record TlsConfig(
        String format,             // "PEM" or "PKCS12"
        String clientCertPath,     // path to client cert (.pem or .p12/.pfx); null = no mTLS
        String clientKeyPath,      // path to private key (.pem); null for PKCS12 (key is inside)
        String clientKeyPassword,  // key/keystore password; null or blank = unprotected
        String caCertPath          // path to custom CA cert (.pem); null = use system trust store
) {}
