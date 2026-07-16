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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class CsvCacheService {

    private static final Logger log = LoggerFactory.getLogger(CsvCacheService.class);
    private static final long STALE_THRESHOLD_SECONDS = 24 * 60 * 60;

    private final Path cacheDir;
    private final YahooFinanceService yahooService;
    private final InvestingFinanceService investingService;
    private final HandelsbankenFinanceService handelsbankenService;
    private final GitCacheService gitService;
    private final ConcurrentHashMap<String, ReentrantLock> symbolLocks = new ConcurrentHashMap<>();

    public CsvCacheService(@Value("${cache.dir:cache}") String cacheDir,
                           YahooFinanceService yahooService,
                           InvestingFinanceService investingService,
                           HandelsbankenFinanceService handelsbankenService,
                           GitCacheService gitService) {
        this.cacheDir = Path.of(cacheDir);
        this.yahooService = yahooService;
        this.investingService = investingService;
        this.handelsbankenService = handelsbankenService;
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

                // Check if cached data covers the requested range
                long rangeSeconds = parseRangeToSeconds(range);
                long cachedFetchedSeconds = parseRangeToSeconds(cached.getFetchedRange());
                long cachedSpan = lastTs - cached.getTimestamps().get(0);
                if (cachedSpan < rangeSeconds - 30 * 86400L && cachedFetchedSeconds < rangeSeconds) {
                    log.info("Cache for {} covers {}d but range {} requires {}d, re-fetching full",
                            symbol, cachedSpan / 86400, range, rangeSeconds / 86400);
                    ChartData data = fetchBest(symbol, range, interval);
                    data.setFetchedRange(range);
                    writeCsv(csvFile, data);
                    gitService.commitChanges("Update " + symbol);
                    return data;
                }

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

                log.info("Cache stale for {} (file written {}s ago, source={}), fetching incremental",
                        symbol, fileAge, cached.getSource());
                try {
                    ChartData incremental = switch (cached.getSource()) {
                        case "investing" -> investingService.fetchIncremental(symbol, lastTs);
                        case "handelsbanken" -> handelsbankenService.fetchIncremental(symbol, lastTs);
                        default -> yahooService.fetchIncremental(symbol, lastTs, interval);
                    };
                    cached.merge(incremental);
                    if ("investing".equals(cached.getSource())) {
                        // Newly appended investing.com rows are always recent (within Yahoo's coverage),
                        // so Yahoo dividends suffice; apply only past lastTs to avoid double-counting the
                        // dividends already recorded on existing rows, then re-adjust the whole series.
                        try {
                            cached.applyDividends(yahooService.fetchDividends(symbol), lastTs);
                        } catch (Exception e) {
                            log.warn("Yahoo dividend refresh failed for {}: {}", symbol, e.getMessage());
                        }
                        cached.recomputeAdjCloseFromDividends();
                    }
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
        ChartData data = fetchBest(symbol, range, interval);
        data.setFetchedRange(range);
        writeCsv(csvFile, data);
        gitService.commitChanges("Add " + symbol);
        return data;
    }

    /**
     * Fetch the symbol from both Yahoo and investing.com and return whichever series reaches further
     * back in time (longer history). investing.com is best-effort: if it fails we fall back to Yahoo,
     * and vice versa. When investing.com wins we borrow Yahoo's richer metadata (currency, name, tz).
     */
    private ChartData fetchBest(String symbol, String range, String interval) {
        // ISINs are fund identifiers Yahoo/investing.com don't index; serve them from Handelsbanken only.
        if (HandelsbankenFinanceService.isIsin(symbol)) {
            return handelsbankenService.fetchChart(symbol, range);
        }

        ChartData yahoo = null;
        try {
            yahoo = yahooService.fetchChart(symbol, range, interval);
        } catch (Exception e) {
            log.warn("Yahoo fetch failed for {}: {}", symbol, e.getMessage());
        }

        ChartData investing = null;
        try {
            investing = investingService.fetchChart(symbol, range);
        } catch (Exception e) {
            log.warn("investing.com fetch failed for {}: {}", symbol, e.getMessage());
        }

        if (yahoo == null && investing == null) {
            throw new RuntimeException("Both Yahoo and investing.com failed for " + symbol);
        }
        if (investing == null || investing.getTimestamps().isEmpty()) return yahoo;
        if (yahoo == null || yahoo.getTimestamps().isEmpty()) {
            dividendAdjustInvesting(symbol, investing, null);
            return investing;
        }

        long yahooStart = yahoo.getTimestamps().get(0);
        long investingStart = investing.getTimestamps().get(0);
        if (investingStart < yahooStart) {
            log.info("Using investing.com for {} (history from {} vs Yahoo {})",
                    symbol, investingStart, yahooStart);
            // investing.com lacks reliable metadata; carry over Yahoo's.
            investing.setCurrency(yahoo.getCurrency());
            investing.setShortName(yahoo.getShortName());
            investing.setExchangeTimezoneName(yahoo.getExchangeTimezoneName());
            dividendAdjustInvesting(symbol, investing, yahoo);
            return investing;
        }
        log.info("Using Yahoo for {} (history from {} vs investing.com {})",
                symbol, yahooStart, investingStart);
        return yahoo;
    }

    /**
     * Dividend-adjust an investing.com price series in place. Dividends come primarily from Yahoo (which
     * covers everything from {@code yahoo}'s history start onward); for the older tail where investing.com
     * reaches further back than Yahoo, investing.com's own (best-effort) dividend history fills the gap.
     * With the dividend column populated, adjClose is recomputed by back-adjustment.
     */
    private void dividendAdjustInvesting(String symbol, ChartData investing, ChartData yahoo) {
        NavigableMap<LocalDate, Double> combined = new java.util.TreeMap<>();

        LocalDate yahooStart = null;
        if (yahoo != null && !yahoo.getTimestamps().isEmpty()) {
            yahooStart = epochToUtcDate(yahoo.getTimestamps().get(0));
            try {
                combined.putAll(yahooService.fetchDividends(symbol));
            } catch (Exception e) {
                log.warn("Yahoo dividend fetch failed for {}: {}", symbol, e.getMessage());
            }
        }

        // Backup: fill dividends older than Yahoo's coverage (or all of them when Yahoo is absent).
        LocalDate investingStart = epochToUtcDate(investing.getTimestamps().get(0));
        if (yahooStart == null || investingStart.isBefore(yahooStart)) {
            NavigableMap<LocalDate, Double> backup = investingService.fetchDividends(symbol);
            for (Map.Entry<LocalDate, Double> e : backup.entrySet()) {
                if (yahooStart == null || e.getKey().isBefore(yahooStart)) {
                    combined.putIfAbsent(e.getKey(), e.getValue()); // never override Yahoo's authoritative data
                }
            }
        }

        investing.applyDividends(combined);
        investing.recomputeAdjCloseFromDividends();
    }

    private static LocalDate epochToUtcDate(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static long parseRangeToSeconds(String range) {
        if (range == null || range.isEmpty()) return 5 * 365 * 86400L;
        char unit = range.charAt(range.length() - 1);
        int value;
        try {
            value = Integer.parseInt(range.substring(0, range.length() - 1));
        } catch (NumberFormatException e) {
            return 5 * 365 * 86400L;
        }
        return switch (unit) {
            case 'd' -> value * 86400L;
            case 'm' -> value * 30L * 86400L;
            case 'y' -> value * 365L * 86400L;
            default -> 5 * 365 * 86400L;
        };
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
                if (data.getFetchedRange() != null) pw.println("# fetchedRange=" + data.getFetchedRange());
                if (data.getSource() != null) pw.println("# source=" + data.getSource());
                pw.println("date,open,high,low,close,adjclose,volume,dividend");
                for (int i = 0; i < data.getTimestamps().size(); i++) {
                    pw.printf(java.util.Locale.US, "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%.6f%n",
                            data.getTimestamps().get(i),
                            data.getOpen().get(i), data.getHigh().get(i),
                            data.getLow().get(i), data.getClose().get(i),
                            data.getAdjClose().get(i), data.getVolume().get(i),
                            data.getDividends().get(i));
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
                else if (line.startsWith("# fetchedRange=")) data.setFetchedRange(line.substring(15));
                else if (line.startsWith("# source=")) data.setSource(line.substring(9));
                else if (line.startsWith("#") || line.startsWith("date,")) continue;
                else {
                    String[] parts = line.split(",");
                    if (parts.length >= 7) {
                        // dividend is the optional 8th column; legacy 7-column files default it to 0.
                        double dividend = parts.length >= 8 ? Double.parseDouble(parts[7]) : 0.0;
                        data.addRow(
                                Long.parseLong(parts[0]),
                                Double.parseDouble(parts[1]),
                                Double.parseDouble(parts[2]),
                                Double.parseDouble(parts[3]),
                                Double.parseDouble(parts[4]),
                                Double.parseDouble(parts[5]),
                                Long.parseLong(parts[6]),
                                dividend
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
