package org.example.indexcurrency.service;

import org.example.indexcurrency.model.ChartData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Fetches daily fund NAV history from Handelsbanken's public fund list (the data behind
 * {@code handelsbanken.fondlista.se}) keyed by ISIN.
 *
 * <p>The page is a Next.js front end that calls a JSON backend at {@code shb-be.fondlista.se}. The NAV
 * series comes from {@code /api/universe/time-series?isin=&webId=&startDate=&endDate=}; the backend
 * rejects requests (HTTP 403) unless an {@code X-Domain} header names the site origin. {@code webId}
 * {@value #DEFAULT_WEB_ID} is the generic "all funds" universe used by the public list. A separate
 * {@code /api/Metadata} call supplies the fund name and native currency.
 *
 * <p>Funds quote a single NAV per day (no OHLC/volume), so every price populates open/high/low/close and
 * adjClose alike. The NAV already reflects reinvested returns, so there is no separate dividend series.
 * Failures surface as {@link RuntimeException} so the caller can treat this as best-effort.
 */
@Service
public class HandelsbankenFinanceService {

    private static final Logger log = LoggerFactory.getLogger(HandelsbankenFinanceService.class);
    private static final String BASE_URL = "https://shb-be.fondlista.se";
    private static final String TIME_SERIES_URL = BASE_URL + "/api/universe/time-series";
    private static final String METADATA_URL = BASE_URL + "/api/Metadata";
    // The backend 403s unless this header names the public site origin; the value is not a real request URL.
    private static final String SITE_ORIGIN = "https://handelsbanken.fondlista.se";
    private static final String DEFAULT_WEB_ID = "9999";
    private static final String DEFAULT_CURRENCY = "SEK";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);
    private static final long DELAY_BETWEEN_REQUESTS_MS = 500;

    // ISIN: 2-letter country code + 9 alphanumerics + 1 numeric check digit (12 chars total).
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    private final RestTemplate restTemplate;
    private final Semaphore rateLimiter = new Semaphore(1);

    public HandelsbankenFinanceService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** Whether a symbol looks like an ISIN and should be served from Handelsbanken rather than Yahoo. */
    public static boolean isIsin(String symbol) {
        return symbol != null && ISIN.matcher(symbol).matches();
    }

    /** Full daily NAV history covering at least the given range (e.g. "10y"), capped at what the fund has. */
    public ChartData fetchChart(String isin, String range) {
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = end.minusDays(rangeToDays(range));
        return fetch(isin, start, end);
    }

    /** Daily NAV from the day of {@code fromEpochSeconds} (inclusive) to now, for incremental updates. */
    public ChartData fetchIncremental(String isin, long fromEpochSeconds) {
        LocalDate start = Instant.ofEpochSecond(fromEpochSeconds).atZone(ZoneOffset.UTC).toLocalDate();
        return fetch(isin, start, LocalDate.now(ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private ChartData fetch(String isin, LocalDate start, LocalDate end) {
        String url = UriComponentsBuilder.fromUriString(TIME_SERIES_URL)
                .queryParam("isin", isin)
                .queryParam("webId", DEFAULT_WEB_ID)
                .queryParam("startDate", start.format(DATE))
                .queryParam("endDate", end.format(DATE))
                .build().encode().toUriString();
        log.info("Fetching NAV history from Handelsbanken ({} .. {}): {}", start, end, url);

        Map<String, Object> body = get(url);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("timeSeries");
        if (rows == null || rows.isEmpty()) {
            Object desc = body.get("description");
            throw new RuntimeException("Handelsbanken returned no NAV data for " + isin
                    + (desc != null ? " (" + desc + ")" : ""));
        }

        ChartData data = new ChartData();
        data.setSymbol(isin);
        data.setSource("handelsbanken");
        data.setExchangeTimezoneName("Europe/Stockholm");
        applyMetadata(isin, data, rows);

        for (Map<String, Object> row : rows) {
            Long ts = parseDate(row.get("endDate"));
            Double price = num(row.get("price"));
            if (ts == null || price == null) continue;
            // Single NAV per day: open=high=low=close=adjClose=price, no volume, no dividend.
            data.addRow(ts, price, price, price, price, price, 0L);
        }
        if (data.getTimestamps().isEmpty()) {
            throw new RuntimeException("Handelsbanken returned no parseable NAV rows for " + isin);
        }
        return data;
    }

    /** Set fund name and currency from the Metadata endpoint, falling back to the series/defaults. */
    private void applyMetadata(String isin, ChartData data, List<Map<String, Object>> rows) {
        String name = isin;
        String currency = firstCurrency(rows);
        try {
            String url = UriComponentsBuilder.fromUriString(METADATA_URL)
                    .queryParam("webId", DEFAULT_WEB_ID)
                    .queryParam("isin", isin)
                    .build().encode().toUriString();
            Map<String, Object> meta = get(url);
            Object fundName = meta.get("fundName");
            if (fundName != null && !fundName.toString().isBlank()) name = fundName.toString();
            Object metaCurrency = meta.get("currency");
            if (metaCurrency != null && !metaCurrency.toString().isBlank()) currency = metaCurrency.toString();
        } catch (Exception e) {
            log.warn("Handelsbanken metadata lookup failed for {}: {}", isin, e.getMessage());
        }
        data.setShortName(name);
        data.setCurrency(currency);
    }

    private static String firstCurrency(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Object c = row.get("currency");
            if (c != null && !c.toString().isBlank()) return c.toString();
        }
        return DEFAULT_CURRENCY;
    }

    /** Parse an endDate like {@code 2017-05-29T00:00:00} (local, no zone) to a UTC start-of-day epoch. */
    private static Long parseDate(Object endDate) {
        if (endDate == null) return null;
        String s = endDate.toString();
        if (s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        } catch (Exception e) {
            return null;
        }
    }

    private static Double num(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long rangeToDays(String range) {
        if (range == null || range.isEmpty()) return 10 * 365L;
        char unit = range.charAt(range.length() - 1);
        int value;
        try {
            value = Integer.parseInt(range.substring(0, range.length() - 1));
        } catch (NumberFormatException e) {
            return 10 * 365L;
        }
        return switch (unit) {
            case 'd' -> value;
            case 'm' -> value * 30L;
            case 'y' -> value * 365L;
            default -> 10 * 365L;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String url) {
        rateLimiter.acquireUninterruptibly();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Domain", SITE_ORIGIN);
            headers.set("Accept-Language", "sv");
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            Map<String, Object> body = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class).getBody();
            if (body == null) throw new RuntimeException("Handelsbanken returned empty body for " + url);
            return body;
        } finally {
            try { Thread.sleep(DELAY_BETWEEN_REQUESTS_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            rateLimiter.release();
        }
    }
}
