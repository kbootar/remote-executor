package in.neuw.kbootar.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import in.neuw.kbootar.collection.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/workspace")
class WorkspaceController {

    private final WorkspaceService workspaceService;

    WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping(value = "/environments", produces = MediaType.APPLICATION_JSON_VALUE)
    Flux<Environment> listEnvironments() {
        return workspaceService.listEnvironments();
    }

    @PutMapping("/environments/{id}")
    Mono<Environment> saveEnvironment(@PathVariable String id, @RequestBody Environment env) {
        return workspaceService.saveEnvironment(env);
    }

    @DeleteMapping("/environments/{id}")
    Mono<Void> deleteEnvironment(@PathVariable String id) {
        return workspaceService.deleteEnvironment(id);
    }

    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    Flux<HistoryEntry> listHistory(@RequestParam(defaultValue = "100") int limit) {
        return workspaceService.listHistory(limit);
    }

    @DeleteMapping("/history/{id}")
    Mono<Void> deleteHistoryEntry(@PathVariable String id) {
        return workspaceService.deleteHistoryEntry(id);
    }

    @DeleteMapping("/history")
    Mono<Void> clearHistory() {
        return workspaceService.clearHistory();
    }

    @GetMapping("/settings")
    Mono<WorkspaceSettings> getSettings() {
        return workspaceService.getSettings();
    }

    @PutMapping("/settings")
    Mono<WorkspaceSettings> saveSettings(@RequestBody WorkspaceSettings settings) {
        return workspaceService.saveSettings(settings);
    }

    @GetMapping("/tabs")
    Mono<JsonNode> readTabs() {
        return workspaceService.readTabs();
    }

    @PutMapping("/tabs")
    Mono<JsonNode> saveTabs(@RequestBody JsonNode tabs) {
        return workspaceService.saveTabs(tabs);
    }
}
