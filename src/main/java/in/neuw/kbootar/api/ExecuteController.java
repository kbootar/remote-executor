package in.neuw.kbootar.api;

import in.neuw.kbootar.proxy.ProxyResponse;
import in.neuw.kbootar.proxy.RequestExecutor;
import in.neuw.kbootar.workspace.HistoryEntry;
import in.neuw.kbootar.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api")
class ExecuteController {

    private static final Logger log = LoggerFactory.getLogger(ExecuteController.class);

    private final RequestExecutor requestExecutor;
    private final WorkspaceService workspaceService;

    ExecuteController(RequestExecutor requestExecutor, WorkspaceService workspaceService) {
        this.requestExecutor = requestExecutor;
        this.workspaceService = workspaceService;
    }

    @PostMapping("/execute")
    Mono<ProxyResponse> execute(@RequestBody ExecuteRequest executeRequest) {
        return requestExecutor.execute(
                executeRequest.request(),
                executeRequest.environment(),
                executeRequest.settings())
            .flatMap(response -> workspaceService.appendHistory(new HistoryEntry(
                    UUID.randomUUID().toString(),
                    executeRequest.request().method(),
                    response.resolvedUrl(),
                    response.status(),
                    response.durationMs(),
                    System.currentTimeMillis(),
                    response
            ))
            // History write failure must never fail the actual response
            .onErrorResume(e -> {
                log.warn("history append failed: {}", e.getMessage());
                return Mono.empty();
            })
            .thenReturn(response));
    }
}
