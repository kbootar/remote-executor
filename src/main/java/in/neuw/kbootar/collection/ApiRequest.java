package in.neuw.kbootar.collection;

import java.util.Map;

public record ApiRequest(
        String id,
        String collectionId,
        String name,
        String method,                  // GET, POST, PUT, PATCH, DELETE, etc.
        String url,                     // may contain {{var}} placeholders
        Map<String, String> headers,
        String body,
        AuthConfig authConfig
) {}
