package in.neuw.kbootar.collection;

import java.util.List;

public record Collection(
        String id,
        String name,
        String description,
        List<String> requestIds
) {}
