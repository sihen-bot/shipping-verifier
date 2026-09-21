package com.hackathon.shippingverifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.json.JsonMapper;

@RestController
public class AttachmentController {

    private final JsonMapper mapper;

    public AttachmentController(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public record EmailRecord(
        String email_id,
        String from,
        String subject,
        String body,
        List<String> attachments
    ) {}

    @GetMapping(
        value = "/api/emails/{emailId}/attachments/{index}",
        produces = "text/plain;charset=UTF-8"
    )
    public ResponseEntity<String> readAttachment(
        @PathVariable("emailId") String emailId,
        @PathVariable("index") int index
    ) {
        if (!emailId.matches("email_\\d+")) {
            return ResponseEntity.badRequest().body("Invalid email ID.");
        }

        Path dataFolder = Path.of("data").toAbsolutePath().normalize();
        Path emailFile = dataFolder.resolve("inbox")
            .resolve(emailId + ".json");

        if (!Files.isRegularFile(emailFile)) {
            return ResponseEntity.status(404).body("Email not found.");
        }

        try {
            EmailRecord email = mapper.readValue(
                Files.readString(emailFile),
                EmailRecord.class
            );

            if (email.attachments() == null
                || index < 0
                || index >= email.attachments().size()) {
                return ResponseEntity.status(404)
                    .body("Attachment is not listed for this email.");
            }

            String listedPath = email.attachments().get(index);
            Path attachmentFolder = dataFolder.resolve("attachments");
            Path file = dataFolder.resolve(listedPath).normalize();

            // Only allow files inside the attachments folder.
            if (!file.startsWith(attachmentFolder)) {
                return ResponseEntity.badRequest()
                    .body("Invalid attachment location.");
            }

            if (!Files.isRegularFile(file)) {
                return ResponseEntity.status(404)
                    .body("The attachment is listed, but its file is missing.");
            }

            // Also prevent symbolic links from pointing outside the folder.
            if (!file.toRealPath().startsWith(attachmentFolder.toRealPath())) {
                return ResponseEntity.badRequest()
                    .body("Invalid attachment location.");
            }

            if (!file.getFileName().toString()
                .toLowerCase(Locale.ROOT).endsWith(".txt")) {
                return ResponseEntity.status(415)
                    .body("This preview currently supports TXT files only.");
            }

            return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .body(Files.readString(file));

        } catch (IOException | RuntimeException error) {
            return ResponseEntity.internalServerError()
                .body("Could not read this attachment. Check the source files.");
        }
    }
}