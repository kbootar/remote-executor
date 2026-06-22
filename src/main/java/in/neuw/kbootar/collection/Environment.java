package in.neuw.kbootar.collection;

import java.util.Map;

public record Environment(
        String id,
        String name,
        Map<String, String> variables,  // plain key→value; values resolved before use
        OAuthConfig oauthConfig         // null when auth type is not OAUTH2_CLIENT_CREDENTIALS
        // TLS config is NOT accepted from callers — resolved server-side via DomainTlsRegistry
) {}
