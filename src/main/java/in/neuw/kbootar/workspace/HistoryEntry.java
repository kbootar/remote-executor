package in.neuw.kbootar.workspace;

import in.neuw.kbootar.proxy.ProxyResponse;

public record HistoryEntry(
        String id,
        String method,
        String url,
        int status,
        long durationMs,
        long timestamp,
        ProxyResponse response
) {}
