package org.example.indexcurrency.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path workspacesDir;

    public WorkspaceService(@Value("${cache.dir:cache}") String cacheDir) {
        this.workspacesDir = Path.of(cacheDir, "workspaces");
    }

    public String save(String jsonBody) throws IOException {
        Files.createDirectories(workspacesDir);
        String code;
        Path file;
        do {
            code = generateCode();
            file = workspacesDir.resolve(code + ".json");
        } while (Files.exists(file));

        Files.writeString(file, jsonBody);
        log.info("Saved workspace: {}", code);
        return code;
    }

    public String load(String code) throws IOException {
        if (!code.matches("[A-Za-z0-9]{1,20}")) {
            throw new IllegalArgumentException("Invalid workspace code");
        }
        Path file = workspacesDir.resolve(code + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        return Files.readString(file);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }
}
