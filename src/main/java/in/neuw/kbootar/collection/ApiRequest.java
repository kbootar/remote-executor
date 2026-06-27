package in.neuw.kbootar.collection;

import java.util.Map;

public record ApiRequest(
        String method,
        String url,
        Map<String, String> headers,
        String body
) {}
