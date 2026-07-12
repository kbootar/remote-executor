package in.neuw.kbootar.collection;

public record MultipartPart(
        String type,       // "text" | "file"
        String name,
        String value,      // text parts
        String fileName,
        String mimeType,
        String base64      // file parts — base64-encoded bytes
) {}
