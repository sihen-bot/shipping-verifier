package com.hackathon.shippingverifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class InboxController {

    private final JsonMapper mapper;

    public InboxController(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping("/api/emails")
    public Map<String, Object> getEmails() throws IOException {

        Path inboxFolder = Path.of("data", "inbox");

        if (!Files.isDirectory(inboxFolder)) {
            throw new IOException(
                "Inbox folder not found: " + inboxFolder.toAbsolutePath()
            );
        }

        List<JsonNode> emails = new ArrayList<>();

        try (var files = Files.list(inboxFolder)) {
            List<Path> emailFiles = files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString()
                    .matches("email_\\d+\\.json"))
                .sorted()
                .toList();

            for (Path file : emailFiles) {
                JsonNode email = mapper.readTree(Files.readString(file));
                emails.add(email);
            }
        }

        return Map.of(
            "count", emails.size(),
            "emails", emails
        );
    }
}