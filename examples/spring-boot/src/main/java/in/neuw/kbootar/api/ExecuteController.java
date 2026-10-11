package in.neuw.kbootar.api;

import in.neuw.kbootar.proxy.ProxyResponse;
import in.neuw.kbootar.proxy.RequestExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import javax.net.ssl.SSLHandshakeException;

@RestController
@RequestMapping("/api")
public class ExecuteController {

    private final RequestExecutor requestExecutor;

    ExecuteController(RequestExecutor requestExecutor) {
        this.requestExecutor = requestExecutor;
    }

    @PostMapping("/execute")
    Mono<ResponseEntity<?>> execute(@RequestBody ExecuteRequest executeRequest) {
        return requestExecutor.execute(executeRequest.request(), executeRequest.settings())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .onErrorResume(e -> Mono.just(ResponseEntity.status(502).body(toError(e))));
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
