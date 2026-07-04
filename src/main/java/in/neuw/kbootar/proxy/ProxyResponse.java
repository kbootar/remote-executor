package in.neuw.kbootar.proxy;

import java.util.List;
import java.util.Map;

public record ProxyResponse(
        int status,
        Map<String, String> headers,
        List<String> setCookies,
        String body,
        long durationMs,
        String resolvedUrl,
        String httpVersion
) {}
