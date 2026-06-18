package in.neuw.kbootar.collection;

import java.util.Map;

public record Environment(
        String id,
        String name,
        Map<String, String> variables,  // plain key→value; values resolved before use
        OAuthConfig oauthConfig,        // null when auth type is not OAUTH2_CLIENT_CREDENTIALS
        TlsConfig tlsConfig             // null = no mTLS / no custom CA
) {}
