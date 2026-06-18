package in.neuw.kbootar.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.neuw.kbootar.collection.Environment;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final Path workspaceDir;
    private final ObjectMapper objectMapper;

    WorkspaceService(@Value("${kbootar.workspace.dir}") String dir, ObjectMapper objectMapper) {
        this.workspaceDir = Path.of(dir);
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initWorkspace() throws IOException {
        Files.createDirectories(workspaceDir.resolve("environments"));
        Files.createDirectories(workspaceDir.resolve("history"));
        log.info("workspace dir: {}", workspaceDir.toAbsolutePath());
    }

    public Flux<Environment> listEnvironments() {
        return Mono.fromCallable(() -> {
            Path dir = workspaceDir.resolve("environments");
            try (var files = Files.list(dir)) {
                return files
                        .filter(p -> p.toString().endsWith(".json"))
                        .map(p -> {
                            try { return objectMapper.readValue(p.toFile(), Environment.class); }
                            catch (IOException e) { throw new RuntimeException("failed to read " + p, e); }
                        })
                        .toList();
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(Flux::fromIterable);
    }

    public Mono<Environment> saveEnvironment(Environment env) {
        return Mono.fromCallable(() -> {
            Path file = workspaceDir.resolve("environments").resolve(env.id() + ".json");
            objectMapper.writeValue(file.toFile(), env);
            return env;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> deleteEnvironment(String id) {
        return Mono.fromCallable(() -> {
            Files.deleteIfExists(workspaceDir.resolve("environments").resolve(id + ".json"));
            return (Void) null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Void> appendHistory(HistoryEntry entry) {
        return Mono.fromCallable(() -> {
            Path file = workspaceDir.resolve("history").resolve(LocalDate.now() + ".jsonl");
            String line = objectMapper.writeValueAsString(entry) + "\n";
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return (Void) null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // Returns entries newest-first, reading back up to 30 days of .jsonl files.
    public Flux<HistoryEntry> listHistory(int limit) {
        return Mono.fromCallable(() -> {
            Path histDir = workspaceDir.resolve("history");
            List<HistoryEntry> entries = new ArrayList<>();
            LocalDate date = LocalDate.now();
            for (int i = 0; i < 30 && entries.size() < limit; i++) {
                Path file = histDir.resolve(date + ".jsonl");
                if (Files.exists(file)) {
                    var lines = Files.readAllLines(file);
                    Collections.reverse(lines);
                    for (String line : lines) {
                        if (line.isBlank()) continue;
                        entries.add(objectMapper.readValue(line, HistoryEntry.class));
                        if (entries.size() >= limit) break;
                    }
                }
                date = date.minusDays(1);
            }
            return entries;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(Flux::fromIterable);
    }

    public Mono<WorkspaceSettings> getSettings() {
        return Mono.fromCallable(() -> {
            Path file = workspaceDir.resolve("settings.json");
            if (!Files.exists(file)) return new WorkspaceSettings("default");
            return objectMapper.readValue(file.toFile(), WorkspaceSettings.class);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<WorkspaceSettings> saveSettings(WorkspaceSettings settings) {
        return Mono.fromCallable(() -> {
            objectMapper.writeValue(workspaceDir.resolve("settings.json").toFile(), settings);
            return settings;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> deleteHistoryEntry(String id) {
        return Mono.fromCallable(() -> {
            Path histDir = workspaceDir.resolve("history");
            try (var files = Files.list(histDir)) {
                files.filter(p -> p.toString().endsWith(".jsonl")).forEach(file -> {
                    try {
                        var lines = Files.readAllLines(file);
                        var filtered = lines.stream()
                                .filter(line -> !line.isBlank() && !line.contains("\"" + id + "\""))
                                .toList();
                        if (filtered.size() < lines.stream().filter(l -> !l.isBlank()).count()) {
                            Files.writeString(file, String.join("\n", filtered) + "\n",
                                    StandardOpenOption.TRUNCATE_EXISTING);
                        }
                    } catch (IOException e) { log.warn("deleteHistoryEntry: failed to rewrite {}", file); }
                });
            }
            return (Void) null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Void> clearHistory() {
        return Mono.fromCallable(() -> {
            Path histDir = workspaceDir.resolve("history");
            try (var files = Files.list(histDir)) {
                files.filter(p -> p.toString().endsWith(".jsonl"))
                     .forEach(p -> { try { Files.delete(p); } catch (IOException e) { /* best-effort */ } });
            }
            return (Void) null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<JsonNode> readTabs() {
        return Mono.fromCallable(() -> {
            Path file = workspaceDir.resolve("tabs.json");
            if (!Files.exists(file)) return objectMapper.nullNode();
            return objectMapper.readTree(file.toFile());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<JsonNode> saveTabs(JsonNode tabs) {
        return Mono.fromCallable(() -> {
            objectMapper.writeValue(workspaceDir.resolve("tabs.json").toFile(), tabs);
            return tabs;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
