package com.hackathon.shippingverifier;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class DocumentPreviewController {
    private final JsonMapper mapper;
    private final DocumentReader reader = new DocumentReader();
    public DocumentPreviewController(JsonMapper mapper) { this.mapper = mapper; }

    @GetMapping(value="/api/emails/{emailId}/attachments/{index}/text", produces="text/plain;charset=UTF-8")
    public ResponseEntity<String> preview(@PathVariable("emailId") String id, @PathVariable("index") int index) {
        if (!id.matches("email_\\d+") || index < 0) return response(400,"Invalid attachment reference.");
        try {
            Path data = Path.of("data").toAbsolutePath().normalize();
            Path email = data.resolve("inbox").resolve(id + ".json");
            if (!Files.isRegularFile(email)) return response(404,"Email not found.");
            var refs = mapper.readTree(Files.readString(email)).path("attachments");
            if (!refs.isArray() || index >= refs.size() || !refs.get(index).isString()) return response(404,"Attachment not found.");
            Path root = data.resolve("attachments");
            Path file = data.resolve(refs.get(index).stringValue()).normalize();
            if (!file.startsWith(root)) return response(400,"Invalid attachment location.");
            if (!Files.isRegularFile(file)) return response(404,"Attachment file is missing.");
            if (!file.toRealPath().startsWith(root.toRealPath())) return response(400,"Invalid attachment location.");
            return response(200,reader.read(file));
        } catch (DocumentReader.ReadFailure failure) {
            return response(422,"Needs review: " + failure.getMessage());
        } catch (Exception failure) { return response(500,"Could not read the attachment."); }
    }
    private ResponseEntity<String> response(int status,String text) {
        return ResponseEntity.status(status).header("X-Content-Type-Options","nosniff")
            .header("Cache-Control","no-store").body(text);
    }
}
