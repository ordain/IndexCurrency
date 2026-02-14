package org.example.indexcurrency.service;

import org.example.indexcurrency.model.ChartData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Service
public class YahooFinanceService {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceService.class);
    private static final String YAHOO_CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final int MAX_RETRIES = 3;
    private static final long DELAY_BETWEEN_REQUESTS_MS = 500;

    private final RestTemplate restTemplate;
    private final Semaphore rateLimiter = new Semaphore(1);

    public YahooFinanceService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ChartData fetchChart(String symbol, String range, String interval) {
        String url = YAHOO_CHART_URL + symbol + "?range=" + range + "&interval=" + interval + "&includeAdjustedClose=true";
        log.info("Fetching full chart from Yahoo: {}", url);
        return parseResponse(symbol, fetchWithThrottle(url));
    }

    public ChartData fetchIncremental(String symbol, long period1, String interval) {
        long period2 = System.currentTimeMillis() / 1000;
        String url = YAHOO_CHART_URL + symbol + "?period1=" + period1 + "&period2=" + period2
                + "&interval=" + interval + "&includeAdjustedClose=true";
        log.info("Fetching incremental chart from Yahoo: {}", url);
        return parseResponse(symbol, fetchWithThrottle(url));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchWithThrottle(String url) {
        rateLimiter.acquireUninterruptibly();
        try {
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    Map<String, Object> result = restTemplate.getForObject(url, Map.class);
                    return result;
                } catch (HttpClientErrorException.TooManyRequests e) {
                    long backoff = DELAY_BETWEEN_REQUESTS_MS * attempt * 2;
                    log.warn("Yahoo 429 rate limited (attempt {}/{}), waiting {}ms", attempt, MAX_RETRIES, backoff);
                    if (attempt == MAX_RETRIES) throw e;
                    sleep(backoff);
                }
            }
            throw new RuntimeException("Unreachable");
        } finally {
            sleep(DELAY_BETWEEN_REQUESTS_MS);
            rateLimiter.release();
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @SuppressWarnings("unchecked")
    private ChartData parseResponse(String symbol, Map<String, Object> json) {
        Map<String, Object> chart = (Map<String, Object>) json.get("chart");
        List<Map<String, Object>> results = (List<Map<String, Object>>) chart.get("result");
        if (results == null || results.isEmpty()) {
            throw new RuntimeException("No data returned for " + symbol);
        }
        Map<String, Object> result = results.get(0);
        Map<String, Object> meta = (Map<String, Object>) result.get("meta");
        List<Number> timestamps = (List<Number>) result.get("timestamp");
        Map<String, Object> indicators = (Map<String, Object>) result.get("indicators");

        List<Map<String, Object>> quoteList = (List<Map<String, Object>>) indicators.get("quote");
        Map<String, Object> quote = quoteList.get(0);

        List<Number> openList = (List<Number>) quote.get("open");
        List<Number> highList = (List<Number>) quote.get("high");
        List<Number> lowList = (List<Number>) quote.get("low");
        List<Number> closeList = (List<Number>) quote.get("close");
        List<Number> volumeList = (List<Number>) quote.get("volume");

        List<Number> adjCloseList = closeList;
        List<Map<String, Object>> adjCloseOuter = (List<Map<String, Object>>) indicators.get("adjclose");
        if (adjCloseOuter != null && !adjCloseOuter.isEmpty()) {
            List<Number> ac = (List<Number>) adjCloseOuter.get(0).get("adjclose");
            if (ac != null) adjCloseList = ac;
        }

        ChartData data = new ChartData();
        data.setSymbol(meta.getOrDefault("symbol", symbol).toString());
        data.setCurrency(meta.getOrDefault("currency", "USD").toString());
        Object shortName = meta.get("shortName");
        if (shortName == null) shortName = meta.get("longName");
        data.setShortName(shortName != null ? shortName.toString() : symbol);
        data.setExchangeTimezoneName(meta.getOrDefault("exchangeTimezoneName", "America/New_York").toString());

        for (int i = 0; i < timestamps.size(); i++) {
            Number o = openList.get(i), h = highList.get(i), l = lowList.get(i),
                    c = closeList.get(i), ac = adjCloseList.get(i), v = volumeList.get(i);
            if (c == null) continue;
            data.addRow(
                    timestamps.get(i).longValue(),
                    o != null ? o.doubleValue() : c.doubleValue(),
                    h != null ? h.doubleValue() : c.doubleValue(),
                    l != null ? l.doubleValue() : c.doubleValue(),
                    c.doubleValue(),
                    ac != null ? ac.doubleValue() : c.doubleValue(),
                    v != null ? v.longValue() : 0L
            );
        }
        return data;
    }
}
