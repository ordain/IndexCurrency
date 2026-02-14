package org.example.indexcurrency.controller;

import org.example.indexcurrency.model.ChartData;
import org.example.indexcurrency.service.CsvCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chart")
public class ChartController {

    private static final Logger log = LoggerFactory.getLogger(ChartController.class);

    private final CsvCacheService cacheService;

    public ChartController(CsvCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @GetMapping("/{symbol}")
    public ResponseEntity<Map<String, Object>> getChart(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5y") String range,
            @RequestParam(defaultValue = "1d") String interval) {
        log.info("Chart request: symbol={}, range={}, interval={}", symbol, range, interval);
        try {
            ChartData data = cacheService.getChartData(symbol, range, interval);
            return ResponseEntity.ok(data.toYahooFormat());
        } catch (Exception e) {
            log.error("Failed to get chart for {}: {}", symbol, e.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "chart", Map.of("result", new Object[0], "error", Map.of("description", e.getMessage()))
            ));
        }
    }
}
