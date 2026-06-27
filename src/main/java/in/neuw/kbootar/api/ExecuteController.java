package in.neuw.kbootar.api;

import in.neuw.kbootar.proxy.ProxyResponse;
import in.neuw.kbootar.proxy.RequestExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
class ExecuteController {

    private final RequestExecutor requestExecutor;

    ExecuteController(RequestExecutor requestExecutor) {
        this.requestExecutor = requestExecutor;
    }

    @PostMapping("/execute")
    Mono<ProxyResponse> execute(@RequestBody ExecuteRequest executeRequest) {
        return requestExecutor.execute(
                executeRequest.request(),
                executeRequest.environment(),
                executeRequest.settings());
    }
}
