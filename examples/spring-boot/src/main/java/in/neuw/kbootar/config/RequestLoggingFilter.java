package in.neuw.kbootar.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * One access-log line per request, including the ones rejected before reaching a controller
 * (malformed JSON, CORS). Headers and bodies are never logged: they carry credentials.
 */
@Component
class RequestLoggingFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long start = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest();
        return chain.filter(exchange).doFinally(signal -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            String origin = request.getHeaders().getFirst("Origin");
            log.info("http id={} {} {} -> {} {}ms caller={}{}",
                    request.getId(),
                    request.getMethod(),
                    request.getURI().getRawPath(),
                    status != null ? status.value() : "-",
                    (System.nanoTime() - start) / 1_000_000,
                    caller(request),
                    origin != null ? " origin=" + origin : "");
        });
    }

    // Behind a reverse proxy the socket address is the proxy; the first X-Forwarded-For hop is the client.
    private static String caller(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote != null ? remote.getHostString() : "unknown";
    }
}
