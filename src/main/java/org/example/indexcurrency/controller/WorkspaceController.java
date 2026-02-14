package org.example.indexcurrency.controller;

import org.example.indexcurrency.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> save(@RequestBody String body) {
        try {
            String code = workspaceService.save(body);
            return ResponseEntity.ok(Map.of("code", code));
        } catch (Exception e) {
            log.error("Failed to save workspace: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> load(@PathVariable String code) {
        try {
            String json = workspaceService.load(code);
            if (json == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(json);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to load workspace {}: {}", code, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
