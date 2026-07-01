package org.example.indexcurrency.service;

import org.example.indexcurrency.model.ChartData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Fetches daily historical data from investing.com.
 *
 * <p>investing.com identifies instruments by a numeric {@code pairId} rather than a ticker, so we
 * resolve one of these by (a) consulting an optional override map ({@code investing-overrides.properties}
 * on the classpath, {@code symbol=pairId[,CURRENCY]}) and falling back to (b) the public search API.
 * The search payload carries no currency, so we derive it from the listing country ({@code flag}).
 *
 * <p>Failures (no match, network error, unparseable payload) surface as {@link RuntimeException} so the
 * caller can treat investing.com as a best-effort secondary source and fall back to Yahoo.
 */
@Service
public class InvestingFinanceService {

    private static final Logger log = LoggerFactory.getLogger(InvestingFinanceService.class);
    private static final String SEARCH_URL = "https://api.investing.com/api/search/v2/search";
    private static final String HISTORICAL_URL = "https://api.investing.com/api/financialdata/historical/";
    private static final String DIVIDENDS_URL = "https://api.investing.com/api/financialdata/";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);
    private static final DateTimeFormatter ROW_DATE = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US);
    private static final long DELAY_BETWEEN_REQUESTS_MS = 500;
    // ~10y per request. investing.com caps responses near 5000 rows; 10y of daily data (~2600 trading
    // days) stays well under that, so each window comes back complete.
    private static final int CHUNK_DAYS = 3650;

    private final RestTemplate restTemplate;
    private final Semaphore rateLimiter = new Semaphore(1);
    private final Map<String, Instrument> instrumentCache = new ConcurrentHashMap<>();
    private final Properties overrides = new Properties();

    /** Resolved investing.com instrument: its pairId plus the metadata we can derive for it. */
    private record Instrument(long pairId, String currency, String name, String timezone) {}

    // investing.com search exposes the listing country ("flag") but no currency. Map the common
    // markets to their trading currency; unknown countries fall back to USD (logged).
    private static final Map<String, String> COUNTRY_CURRENCY = Map.ofEntries(
            Map.entry("USA", "USD"), Map.entry("United States", "USD"),
            Map.entry("Euro Zone", "EUR"), Map.entry("Germany", "EUR"), Map.entry("France", "EUR"),
            Map.entry("Italy", "EUR"), Map.entry("Spain", "EUR"), Map.entry("Netherlands", "EUR"),
            Map.entry("Belgium", "EUR"), Map.entry("Austria", "EUR"), Map.entry("Finland", "EUR"),
            Map.entry("Ireland", "EUR"), Map.entry("Portugal", "EUR"), Map.entry("Greece", "EUR"),
            Map.entry("Luxembourg", "EUR"),
            Map.entry("United Kingdom", "GBP"), Map.entry("UK", "GBP"),
            Map.entry("Sweden", "SEK"), Map.entry("Norway", "NOK"), Map.entry("Denmark", "DKK"),
            Map.entry("Switzerland", "CHF"), Map.entry("Japan", "JPY"), Map.entry("China", "CNY"),
            Map.entry("Hong Kong", "HKD"), Map.entry("Canada", "CAD"), Map.entry("Australia", "AUD"),
            Map.entry("New Zealand", "NZD"), Map.entry("India", "INR"), Map.entry("Brazil", "BRL"),
            Map.entry("South Korea", "KRW"), Map.entry("Singapore", "SGD"), Map.entry("South Africa", "ZAR"),
            Map.entry("Mexico", "MXN"), Map.entry("Poland", "PLN"), Map.entry("Turkey", "TRY"),
            Map.entry("Israel", "ILS"), Map.entry("Russia", "RUB"));

    // Best-effort timezone per listing country (display only; defaults to America/New_York).
    private static final Map<String, String> COUNTRY_TIMEZONE = Map.ofEntries(
            Map.entry("Sweden", "Europe/Stockholm"), Map.entry("Norway", "Europe/Oslo"),
            Map.entry("Denmark", "Europe/Copenhagen"), Map.entry("Germany", "Europe/Berlin"),
            Map.entry("France", "Europe/Paris"), Map.entry("Italy", "Europe/Rome"),
            Map.entry("Spain", "Europe/Madrid"), Map.entry("Netherlands", "Europe/Amsterdam"),
            Map.entry("Switzerland", "Europe/Zurich"), Map.entry("United Kingdom", "Europe/London"),
            Map.entry("UK", "Europe/London"), Map.entry("Japan", "Asia/Tokyo"),
            Map.entry("China", "Asia/Shanghai"), Map.entry("Hong Kong", "Asia/Hong_Kong"),
            Map.entry("Australia", "Australia/Sydney"));

    public InvestingFinanceService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        loadOverrides();
    }

    /**
     * Log one line at startup indicating whether investing.com is reachable or Cloudflare-gated, so the
     * secondary source's availability is visible without digging through per-symbol fetch warnings. Runs
     * on a daemon thread so a slow/blocked probe never delays application readiness.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logReachabilityOnStartup() {
        Thread probe = new Thread(this::probeReachability, "investing-reachability-probe");
        probe.setDaemon(true);
        probe.start();
    }

    private void probeReachability() {
        long pairId = 6408; // AAPL — a stable, well-known instrument for a lightweight liveness check.
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        String url = UriComponentsBuilder.fromUriString(HISTORICAL_URL + pairId)
                .queryParam("start-date", end.minusDays(7).format(DATE))
                .queryParam("end-date", end.format(DATE))
                .queryParam("time-frame", "Daily")
                .queryParam("add-missing-rows", "false")
                .build().encode().toUriString();
        try {
            Object data = get(url).get("data");
            int rows = data instanceof List<?> l ? l.size() : 0;
            log.info("investing.com secondary source is REACHABLE at startup (probe returned {} row(s))", rows);
        } catch (HttpStatusCodeException e) {
            log.warn("investing.com secondary source is BLOCKED at startup (HTTP {} — likely Cloudflare); "
                    + "using Yahoo only until it recovers", e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("investing.com secondary source is UNREACHABLE at startup ({}); using Yahoo only until it recovers",
                    e.getClass().getSimpleName());
        }
    }

    private void loadOverrides() {
        ClassPathResource resource = new ClassPathResource("investing-overrides.properties");
        if (!resource.exists()) {
            log.info("No investing-overrides.properties on classpath; relying on auto-search only");
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            overrides.load(in);
            log.info("Loaded {} investing.com pairId override(s)", overrides.size());
        } catch (Exception e) {
            log.warn("Failed to load investing-overrides.properties: {}", e.getMessage());
        }
    }

    /** Full daily history covering at least the given range (e.g. "10y"). */
    public ChartData fetchChart(String symbol, String range) {
        Instrument inst = resolveInstrument(symbol);
        LocalDate start = LocalDate.now(ZoneOffset.UTC).minusDays(rangeToDays(range));
        return fetchHistorical(symbol, inst, start);
    }

    /** Daily history from the day of {@code fromEpochSeconds} (inclusive) to now, for incremental updates. */
    public ChartData fetchIncremental(String symbol, long fromEpochSeconds) {
        Instrument inst = resolveInstrument(symbol);
        LocalDate start = Instant.ofEpochSecond(fromEpochSeconds).atZone(ZoneOffset.UTC).toLocalDate();
        return fetchHistorical(symbol, inst, start);
    }

    /**
     * Best-effort dividend history (ex-date&rarr;amount) from investing.com. Used only as a backup to fill
     * the pre-Yahoo tail when investing.com's price history reaches further back than Yahoo's. The
     * dividends endpoint is undocumented, so any failure (no endpoint, unparseable payload) is swallowed
     * and returns an empty map — the caller then adjusts with whatever Yahoo provided.
     */
    @SuppressWarnings("unchecked")
    public NavigableMap<LocalDate, Double> fetchDividends(String symbol) {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        try {
            Instrument inst = resolveInstrument(symbol);
            String url = UriComponentsBuilder.fromUriString(DIVIDENDS_URL + inst.pairId() + "/dividends")
                    .queryParam("page-size", 200)
                    .queryParam("page", 0)
                    .build().encode().toUriString();
            log.info("Fetching dividends from investing.com (backup): {}", url);
            Map<String, Object> body = get(url);
            List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("data");
            if (rows == null) return out;
            for (Map<String, Object> row : rows) {
                LocalDate exDate = parseDividendDate(row);
                Double amount = dividendAmount(row);
                if (exDate != null && amount != null && amount > 0) {
                    out.merge(exDate, amount, Double::sum);
                }
            }
            log.info("investing.com dividends for {} (pairId {}): {} row(s)", symbol, inst.pairId(), out.size());
        } catch (Exception e) {
            log.warn("investing.com dividends backup failed for {}: {}", symbol, e.getMessage());
        }
        return out;
    }

    /** Parse a dividend row's ex-date, tolerating epoch/ISO timestamp fields or a formatted date string. */
    private static LocalDate parseDividendDate(Map<String, Object> row) {
        for (String key : new String[]{"dateTimestamp", "rowDateTimestamp", "exDateTimestamp"}) {
            Object ts = row.get(key);
            if (ts instanceof Number n) {
                return Instant.ofEpochSecond(n.longValue()).atZone(ZoneOffset.UTC).toLocalDate();
            }
            if (ts != null) {
                try { return OffsetDateTime.parse(ts.toString()).toLocalDate(); }
                catch (Exception ignored) { /* try next */ }
            }
        }
        for (String key : new String[]{"date", "rowDate", "exDate"}) {
            Object d = row.get(key);
            if (d != null) {
                try { return LocalDate.parse(d.toString(), ROW_DATE); }
                catch (Exception ignored) { /* try next */ }
            }
        }
        return null;
    }

    // Candidate amount fields on an investing.com dividend row; the endpoint's exact schema is unverified.
    private static final String[] DIVIDEND_AMOUNT_KEYS = {"value", "dividend", "amount"};

    /** First parseable dividend amount among the known candidate fields (each also tried with {@code Raw}). */
    private static Double dividendAmount(Map<String, Object> row) {
        for (String key : DIVIDEND_AMOUNT_KEYS) {
            Double v = num(row, key);
            if (v != null) return v;
        }
        return null;
    }

    // ── instrument resolution ──

    private Instrument resolveInstrument(String symbol) {
        Instrument cached = instrumentCache.get(symbol);
        if (cached != null) return cached;

        Instrument inst;
        String override = overrides.getProperty(symbol);
        if (override != null && !override.isBlank()) {
            inst = parseOverride(symbol, override);
        } else {
            inst = null;
        }
        if (inst == null) {
            inst = searchInstrument(symbol);
        }
        instrumentCache.put(symbol, inst);
        return inst;
    }

    /** Override value is {@code pairId} or {@code pairId,CURRENCY}; currency is optional. */
    private Instrument parseOverride(String symbol, String value) {
        String[] parts = value.split(",", 2);
        try {
            long id = Long.parseLong(parts[0].trim());
            String currency = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim().toUpperCase(Locale.US) : "USD";
            log.info("investing.com pairId for {} from override map: {} (currency {})", symbol, id, currency);
            return new Instrument(id, currency, symbol, "America/New_York");
        } catch (NumberFormatException e) {
            log.warn("Invalid pairId override for {}: '{}'", symbol, value);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Instrument searchInstrument(String symbol) {
        String query = toSearchQuery(symbol);
        String url = UriComponentsBuilder.fromUriString(SEARCH_URL)
                .queryParam("q", query)
                .build().encode().toUriString();
        Map<String, Object> body = get(url);
        List<Map<String, Object>> quotes = (List<Map<String, Object>>) body.get("quotes");
        if (quotes == null || quotes.isEmpty()) {
            throw new RuntimeException("investing.com search returned no quotes for " + symbol);
        }

        String wanted = normalize(query);
        Map<String, Object> match = null;
        for (Map<String, Object> q : quotes) {
            Object qs = q.get("symbol");
            if (qs != null && normalize(qs.toString()).equals(wanted)) {
                match = q;
                break;
            }
        }
        if (match == null) match = quotes.getFirst(); // fall back to top result

        Object pairId = match.get("id");
        if (pairId == null) {
            throw new RuntimeException("investing.com search match for " + symbol + " has no id");
        }
        long id = ((Number) pairId).longValue();

        String flag = match.get("flag") != null ? match.get("flag").toString() : null;
        String currency = COUNTRY_CURRENCY.get(flag);
        if (currency == null) {
            log.warn("No currency mapping for investing.com country '{}' ({}); defaulting to USD", flag, symbol);
            currency = "USD";
        }
        String timezone = COUNTRY_TIMEZONE.getOrDefault(flag, "America/New_York");
        String name = match.get("description") != null ? match.get("description").toString() : symbol;

        log.info("investing.com pairId for {} via search (query '{}'): {} [{} / {} / {} {}]",
                symbol, query, id, match.get("symbol"), name, flag, currency);
        return new Instrument(id, currency, name, timezone);
    }

    /** Map a Yahoo-style ticker to a reasonable investing.com search term. */
    static String toSearchQuery(String symbol) {
        String s = symbol;
        if (s.endsWith("=X")) s = s.substring(0, s.length() - 2); // forex: EURUSD=X -> EURUSD
        if (s.startsWith("^")) s = s.substring(1);                // index: ^GSPC -> GSPC
        return s;
    }

    private static String normalize(String s) {
        return s.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    // ── historical data ──

    private ChartData fetchHistorical(String symbol, Instrument inst, LocalDate start) {
        long pairId = inst.pairId();
        LocalDate end = LocalDate.now(ZoneOffset.UTC);

        // investing.com caps each historical response at ~5000 rows and, for an over-wide window,
        // returns the OLDEST rows — truncating recent data. Fetch in sub-windows comfortably under
        // the cap (~10y of daily rows) and merge, so we get the full span end-to-end.
        TreeMap<Long, double[]> byTs = new TreeMap<>();
        LocalDate windowStart = start;
        while (!windowStart.isAfter(end)) {
            LocalDate windowEnd = windowStart.plusDays(CHUNK_DAYS);
            if (windowEnd.isAfter(end)) windowEnd = end;
            mergeChunk(byTs, fetchChunk(pairId, windowStart, windowEnd));
            if (windowEnd.equals(end)) break;
            windowStart = windowEnd.plusDays(1);
        }
        if (byTs.isEmpty()) {
            throw new RuntimeException("investing.com returned no parseable data for " + symbol + " (pairId " + pairId + ")");
        }

        ChartData data = new ChartData();
        data.setSymbol(symbol);
        data.setCurrency(inst.currency());
        data.setShortName(inst.name());
        data.setExchangeTimezoneName(inst.timezone());
        data.setSource("investing");
        for (Map.Entry<Long, double[]> e : byTs.entrySet()) {
            double[] v = e.getValue();
            // investing.com prices are not dividend-adjusted; adjClose starts equal to close and is
            // recomputed from the dividend series by the caller (see CsvCacheService).
            data.addRow(e.getKey(), v[0], v[1], v[2], v[3], v[3], (long) v[4]);
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchChunk(long pairId, LocalDate start, LocalDate end) {
        String url = UriComponentsBuilder.fromUriString(HISTORICAL_URL + pairId)
                .queryParam("start-date", start.format(DATE))
                .queryParam("end-date", end.format(DATE))
                .queryParam("time-frame", "Daily")
                .queryParam("add-missing-rows", "false")
                .build().encode().toUriString();
        log.info("Fetching history from investing.com ({} .. {}): {}", start, end, url);
        Map<String, Object> body = get(url);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("data");
        return rows != null ? rows : List.of();
    }

    /** investing.com returns rows newest-first; the TreeMap key (timestamp) de-dupes overlapping windows. */
    private static void mergeChunk(TreeMap<Long, double[]> byTs, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Long ts = parseTimestamp(row);
            Double close = num(row, "last_close");
            if (ts == null || close == null) continue;
            Double open = num(row, "last_open");
            Double high = num(row, "last_max");
            Double low = num(row, "last_min");
            Double volume = num(row, "volume");
            byTs.put(ts, new double[]{
                    open != null ? open : close,
                    high != null ? high : close,
                    low != null ? low : close,
                    close,
                    volume != null ? volume : 0d
            });
        }
    }

    private static Long parseTimestamp(Map<String, Object> row) {
        Object iso = row.get("rowDateTimestamp");
        if (iso != null) {
            try {
                return OffsetDateTime.parse(iso.toString()).toEpochSecond();
            } catch (Exception ignored) { /* fall through to rowDate */ }
        }
        Object rowDate = row.get("rowDate");
        if (rowDate != null) {
            try {
                return LocalDate.parse(rowDate.toString(), ROW_DATE).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            } catch (Exception ignored) { /* unparseable */ }
        }
        return null;
    }

    /** Read a numeric field, preferring the {@code <key>Raw} variant, tolerating comma-formatted strings. */
    private static Double num(Map<String, Object> row, String key) {
        Object raw = row.get(key + "Raw");
        if (raw == null) raw = row.get(key);
        if (raw == null) return null;
        if (raw instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(raw.toString().replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long rangeToDays(String range) {
        if (range == null || range.isEmpty()) return 5 * 365L;
        char unit = range.charAt(range.length() - 1);
        int value;
        try {
            value = Integer.parseInt(range.substring(0, range.length() - 1));
        } catch (NumberFormatException e) {
            return 5 * 365L;
        }
        return switch (unit) {
            case 'd' -> value;
            case 'm' -> value * 30L;
            case 'y' -> value * 365L;
            default -> 5 * 365L;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String url) {
        rateLimiter.acquireUninterruptibly();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("domain-id", "www");
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            Map<String, Object> body = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class).getBody();
            if (body == null) throw new RuntimeException("investing.com returned empty body for " + url);
            return body;
        } finally {
            try { Thread.sleep(DELAY_BETWEEN_REQUESTS_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            rateLimiter.release();
        }
    }
}
