package in.neuw.kbootar.api;

import in.neuw.kbootar.collection.ApiRequest;
import in.neuw.kbootar.proxy.RequestExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import javax.net.ssl.SSLHandshakeException;
import java.net.URI;

@RestController
@RequestMapping("/api")
public class ExecuteController {

    private static final Logger log = LoggerFactory.getLogger(ExecuteController.class);

    private final RequestExecutor requestExecutor;

    ExecuteController(RequestExecutor requestExecutor) {
        this.requestExecutor = requestExecutor;
    }

    @PostMapping("/execute")
    Mono<ResponseEntity<?>> execute(@RequestBody ExecuteRequest executeRequest, ServerHttpRequest http) {
        long start = System.nanoTime();
        String id = http.getId();
        String target = describe(executeRequest.request());
        return requestExecutor.execute(executeRequest.request(), executeRequest.settings())
                .doOnNext(response -> log.info("execute id={} {} -> {} in {}ms",
                        id, target, response.status(), response.durationMs()))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    ProxyError error = toError(e);
                    log.warn("execute id={} {} failed: {} - {} ({}ms)",
                            id, target, error.error(), error.message(), (System.nanoTime() - start) / 1_000_000);
                    return Mono.just(ResponseEntity.status(502).body(error));
                });
    }

    // Method, host and path only: query strings and userinfo routinely carry credentials.
    private static String describe(ApiRequest request) {
        if (request == null) {
            return "<no request>";
        }
        try {
            URI uri = URI.create(request.url());
            String port = uri.getPort() != -1 ? ":" + uri.getPort() : "";
            String query = uri.getRawQuery() != null ? "?..." : "";
            return request.method() + " " + uri.getScheme() + "://" + uri.getHost() + port + uri.getRawPath() + query;
        } catch (RuntimeException e) {
            return request.method() + " <unparseable url>";
        }
    }

    private static ProxyError toError(Throwable e) {
        Throwable root = rootCause(e);
        String error;
        String message;

        if (root instanceof SSLHandshakeException || causeChainContains(e, "PKIX") || causeChainContains(e, "SSLHandshake")) {
            error = "SSL_HANDSHAKE_FAILED";
            message = "TLS handshake failed connecting to the target: " + root.getMessage()
                    + " — if the server uses a self-signed or private CA certificate, set sslVerification: false in request settings.";
        } else if (causeChainContains(e, "Connection refused") || causeChainContains(e, "ConnectException")) {
            error = "CONNECTION_REFUSED";
            message = "Could not connect to target: " + root.getMessage();
        } else if (causeChainContains(e, "timed out") || causeChainContains(e, "TimeoutException")) {
            error = "TIMEOUT";
            message = "Request to target timed out: " + root.getMessage();
        } else {
            error = "PROXY_ERROR";
            message = root.getMessage() != null ? root.getMessage() : e.getMessage();
        }

        return new ProxyError(error, message);
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t;
    }

    private static boolean causeChainContains(Throwable e, String fragment) {
        Throwable t = e;
        while (t != null) {
            if (t.getClass().getSimpleName().contains(fragment)) return true;
            if (t.getMessage() != null && t.getMessage().contains(fragment)) return true;
            t = t.getCause();
        }
        return false;
    }
}
