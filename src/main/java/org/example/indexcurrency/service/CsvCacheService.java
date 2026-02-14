package org.example.indexcurrency.service;

import org.example.indexcurrency.model.ChartData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class CsvCacheService {

    private static final Logger log = LoggerFactory.getLogger(CsvCacheService.class);
    private static final long STALE_THRESHOLD_SECONDS = 24 * 60 * 60;

    private final Path cacheDir;
    private final YahooFinanceService yahooService;
    private final GitCacheService gitService;
    private final ConcurrentHashMap<String, ReentrantLock> symbolLocks = new ConcurrentHashMap<>();

    public CsvCacheService(@Value("${cache.dir:cache}") String cacheDir,
                           YahooFinanceService yahooService,
                           GitCacheService gitService) {
        this.cacheDir = Path.of(cacheDir);
        this.yahooService = yahooService;
        this.gitService = gitService;
    }

    public ChartData getChartData(String symbol, String range, String interval) {
        ReentrantLock lock = symbolLocks.computeIfAbsent(sanitizeSymbol(symbol), k -> new ReentrantLock());
        lock.lock();
        try {
            return getChartDataLocked(symbol, range, interval);
        } finally {
            lock.unlock();
        }
    }

    private ChartData getChartDataLocked(String symbol, String range, String interval) {
        Path csvFile = cacheDir.resolve(sanitizeSymbol(symbol) + ".csv");

        if (Files.exists(csvFile)) {
            ChartData cached = readCsv(csvFile, symbol);
            if (cached != null && !cached.getTimestamps().isEmpty()) {
                long lastTs = cached.getLastTimestamp();
                long fileAge;
                try {
                    fileAge = Instant.now().getEpochSecond() - Files.getLastModifiedTime(csvFile).toInstant().getEpochSecond();
                } catch (IOException e) {
                    fileAge = Long.MAX_VALUE;
                }

                if (fileAge < STALE_THRESHOLD_SECONDS) {
                    log.info("Cache fresh for {} (file written {}s ago)", symbol, fileAge);
                    return cached;
                }

                log.info("Cache stale for {} (file written {}s ago), fetching incremental", symbol, fileAge);
                try {
                    ChartData incremental = yahooService.fetchIncremental(symbol, lastTs, interval);
                    cached.merge(incremental);
                    writeCsv(csvFile, cached);
                    gitService.commitChanges("Update " + symbol);
                    return cached;
                } catch (Exception e) {
                    log.warn("Incremental fetch failed for {}, returning stale cache: {}", symbol, e.getMessage());
                    return cached;
                }
            }
        }

        log.info("No cache for {}, fetching full {}", symbol, range);
        ChartData data = yahooService.fetchChart(symbol, range, interval);
        writeCsv(csvFile, data);
        gitService.commitChanges("Add " + symbol);
        return data;
    }

    static String sanitizeSymbol(String symbol) {
        return symbol.replace("=", "_EQ_")
                .replace("/", "_")
                .replace("\\", "_")
                .replace(":", "_")
                .replace("*", "_")
                .replace("?", "_")
                .replace("\"", "_")
                .replace("<", "_")
                .replace(">", "_")
                .replace("|", "_");
    }

    private void writeCsv(Path file, ChartData data) {
        try {
            Files.createDirectories(file.getParent());
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file))) {
                pw.println("# symbol=" + data.getSymbol());
                pw.println("# currency=" + data.getCurrency());
                pw.println("# shortName=" + data.getShortName());
                pw.println("# exchangeTimezoneName=" + data.getExchangeTimezoneName());
                pw.println("date,open,high,low,close,adjclose,volume");
                for (int i = 0; i < data.getTimestamps().size(); i++) {
                    pw.printf(java.util.Locale.US, "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d%n",
                            data.getTimestamps().get(i),
                            data.getOpen().get(i), data.getHigh().get(i),
                            data.getLow().get(i), data.getClose().get(i),
                            data.getAdjClose().get(i), data.getVolume().get(i));
                }
            }
            log.info("Wrote cache CSV: {}", file);
        } catch (IOException e) {
            log.error("Failed to write CSV {}: {}", file, e.getMessage());
        }
    }

    private ChartData readCsv(Path file, String symbol) {
        try (BufferedReader br = Files.newBufferedReader(file)) {
            ChartData data = new ChartData();
            data.setSymbol(symbol);
            data.setCurrency("USD");
            data.setShortName(symbol);

            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("# symbol=")) data.setSymbol(line.substring(9));
                else if (line.startsWith("# currency=")) data.setCurrency(line.substring(11));
                else if (line.startsWith("# shortName=")) data.setShortName(line.substring(12));
                else if (line.startsWith("# exchangeTimezoneName=")) data.setExchangeTimezoneName(line.substring(23));
                else if (line.startsWith("#") || line.startsWith("date,")) continue;
                else {
                    String[] parts = line.split(",");
                    if (parts.length >= 7) {
                        data.addRow(
                                Long.parseLong(parts[0]),
                                Double.parseDouble(parts[1]),
                                Double.parseDouble(parts[2]),
                                Double.parseDouble(parts[3]),
                                Double.parseDouble(parts[4]),
                                Double.parseDouble(parts[5]),
                                Long.parseLong(parts[6])
                        );
                    }
                }
            }
            return data;
        } catch (IOException e) {
            log.error("Failed to read CSV {}: {}", file, e.getMessage());
            return null;
        }
    }
}
