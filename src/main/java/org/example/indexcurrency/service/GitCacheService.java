package org.example.indexcurrency.service;

import jakarta.annotation.PostConstruct;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class GitCacheService {

    private static final Logger log = LoggerFactory.getLogger(GitCacheService.class);

    private final Path cacheDir;
    private Git git;

    public GitCacheService(@Value("${cache.dir:cache}") String cacheDir) {
        this.cacheDir = Path.of(cacheDir);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(cacheDir);
            Path gitDir = cacheDir.resolve(".git");
            if (Files.exists(gitDir)) {
                git = Git.open(cacheDir.toFile());
                log.info("Opened existing git repo at {}", cacheDir);
            } else {
                git = Git.init().setDirectory(cacheDir.toFile()).call();
                log.info("Initialized new git repo at {}", cacheDir);
            }
        } catch (IOException | GitAPIException e) {
            log.error("Failed to initialize git cache repo: {}", e.getMessage());
        }
    }

    public synchronized void commitChanges(String message) {
        if (git == null) return;
        try {
            git.add().addFilepattern(".").call();
            var status = git.status().call();
            if (status.getChanged().isEmpty() && status.getAdded().isEmpty() && status.getRemoved().isEmpty()) {
                log.debug("No changes to commit");
                return;
            }
            git.commit()
                    .setAuthor("IndexCurrency", "indexcurrency@local")
                    .setMessage(message)
                    .call();
            log.info("Committed: {}", message);
        } catch (GitAPIException e) {
            log.warn("Git commit failed: {}", e.getMessage());
        }
    }
}
