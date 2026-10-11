package in.neuw.kbootar.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/mock")
class MockController {

    private static final Logger log = LoggerFactory.getLogger(MockController.class);

    @RequestMapping(
        path = {"", "/**"},
        method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                  RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.HEAD,
                  RequestMethod.OPTIONS}
    )
    Mono<ResponseEntity<Map<String, Object>>> handle(ServerWebExchange exchange) {
        var req    = exchange.getRequest();
        var method = req.getMethod().name();
        var path   = req.getPath().value();
        var params = req.getQueryParams();
        var hdrs   = new LinkedHashMap<String, String>();
        req.getHeaders().forEach((name, values) -> hdrs.put(name, String.join(", ", values)));

        return DataBufferUtils.join(req.getBody())
            .map(buf -> {
                byte[] bytes = new byte[buf.readableByteCount()];
                buf.read(bytes);
                DataBufferUtils.release(buf);
                return new String(bytes, StandardCharsets.UTF_8);
            })
            .defaultIfEmpty("")
            .map(rawBody -> {
                log.info("MOCK {} {} | params={} | headers={} | body={}",
                    method, path, params.toSingleValueMap(),
                    hdrs, rawBody.isEmpty() ? "(empty)" : rawBody);

                Map<String, Object> echo = new LinkedHashMap<>();
                echo.put("method", method);
                echo.put("path", path);
                echo.put("queryParams", params);
                echo.put("headers", hdrs);
                echo.put("body", rawBody.isEmpty() ? null : rawBody);
                echo.put("_kbootar", "mock");

                return ResponseEntity.ok(echo);
            });
    }
}
