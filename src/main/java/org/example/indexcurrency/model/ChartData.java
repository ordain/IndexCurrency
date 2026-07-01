package org.example.indexcurrency.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

public class ChartData {
    private String symbol;
    private String currency;
    private String shortName;
    private String exchangeTimezoneName;
    private String fetchedRange;
    private String source = "yahoo";
    private final List<Long> timestamps = new ArrayList<>();
    private final List<Double> open = new ArrayList<>();
    private final List<Double> high = new ArrayList<>();
    private final List<Double> low = new ArrayList<>();
    private final List<Double> close = new ArrayList<>();
    private final List<Double> adjClose = new ArrayList<>();
    private final List<Long> volume = new ArrayList<>();
    // Per-row cash dividend paid on that date (ex-date), 0 when none. Kept alongside raw close and
    // adjClose so "raw vs. dividend-adjusted" can become a display switch without re-fetching.
    private final List<Double> dividends = new ArrayList<>();

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getShortName() { return shortName; }
    public void setShortName(String shortName) { this.shortName = shortName; }
    public String getExchangeTimezoneName() { return exchangeTimezoneName; }
    public void setExchangeTimezoneName(String tz) { this.exchangeTimezoneName = tz; }
    public String getFetchedRange() { return fetchedRange; }
    public void setFetchedRange(String fetchedRange) { this.fetchedRange = fetchedRange; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public List<Long> getTimestamps() { return timestamps; }
    public List<Double> getOpen() { return open; }
    public List<Double> getHigh() { return high; }
    public List<Double> getLow() { return low; }
    public List<Double> getClose() { return close; }
    public List<Double> getAdjClose() { return adjClose; }
    public List<Long> getVolume() { return volume; }
    public List<Double> getDividends() { return dividends; }

    public void addRow(long ts, double o, double h, double l, double c, double ac, long v) {
        addRow(ts, o, h, l, c, ac, v, 0.0);
    }

    public void addRow(long ts, double o, double h, double l, double c, double ac, long v, double div) {
        timestamps.add(ts);
        open.add(o);
        high.add(h);
        low.add(l);
        close.add(c);
        adjClose.add(ac);
        volume.add(v);
        dividends.add(div);
    }

    public long getLastTimestamp() {
        return timestamps.isEmpty() ? 0 : timestamps.get(timestamps.size() - 1);
    }

    public void merge(ChartData newer) {
        if (newer.timestamps.isEmpty()) return;
        long lastTs = getLastTimestamp();
        for (int i = 0; i < newer.timestamps.size(); i++) {
            if (newer.timestamps.get(i) > lastTs) {
                addRow(newer.timestamps.get(i), newer.open.get(i), newer.high.get(i),
                        newer.low.get(i), newer.close.get(i), newer.adjClose.get(i), newer.volume.get(i),
                        newer.dividends.get(i));
            }
        }
    }

    /** Map ex-date&rarr;amount dividends onto rows, placing each on the first row on/after its ex-date. */
    public void applyDividends(NavigableMap<LocalDate, Double> exDateToAmount) {
        applyDividends(exDateToAmount, Long.MIN_VALUE);
    }

    /**
     * Fill the dividend column from an ex-date&rarr;amount map. Each dividend lands on the first row whose
     * date is on/after its ex-date. Only rows with timestamp &gt; {@code afterTsExclusive} are touched
     * ({@link Long#MIN_VALUE} for all rows), so an incremental refresh can add newly-announced dividends
     * without double-counting ones already recorded.
     */
    public void applyDividends(NavigableMap<LocalDate, Double> exDateToAmount, long afterTsExclusive) {
        if (exDateToAmount == null || exDateToAmount.isEmpty() || timestamps.isEmpty()) return;
        for (Map.Entry<LocalDate, Double> e : exDateToAmount.entrySet()) {
            Double amt = e.getValue();
            if (amt == null || amt <= 0) continue;
            long exEpoch = e.getKey().atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            int idx = firstRowOnOrAfter(exEpoch);
            if (idx < 0 || timestamps.get(idx) <= afterTsExclusive) continue;
            dividends.set(idx, dividends.get(idx) + amt);
        }
    }

    /** Index of the first (ascending) row whose timestamp is &ge; the given epoch, or -1 if none. */
    private int firstRowOnOrAfter(long epochSeconds) {
        int lo = 0, hi = timestamps.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (timestamps.get(mid) >= epochSeconds) { ans = mid; hi = mid - 1; }
            else lo = mid + 1;
        }
        return ans;
    }

    /**
     * Recompute adjClose from raw close and the dividend column using standard back-adjustment: walking
     * newest&rarr;oldest, each ex-dividend scales every earlier close by {@code (1 - dividend/prevClose)}.
     * Use for sources (investing.com) whose prices are not dividend-adjusted; Yahoo already supplies its
     * own split-inclusive adjClose and must not be recomputed here.
     */
    public void recomputeAdjCloseFromDividends() {
        double factor = 1.0;
        for (int i = close.size() - 1; i >= 0; i--) {
            adjClose.set(i, close.get(i) * factor);
            double div = dividends.get(i);
            if (div > 0 && i > 0) {
                double prevClose = close.get(i - 1);
                if (prevClose > 0) factor *= (1.0 - div / prevClose);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toYahooFormat() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("currency", currency);
        meta.put("symbol", symbol);
        meta.put("shortName", shortName != null ? shortName : symbol);
        meta.put("exchangeTimezoneName", exchangeTimezoneName != null ? exchangeTimezoneName : "America/New_York");
        meta.put("regularMarketPrice", close.isEmpty() ? 0 : close.get(close.size() - 1));
        meta.put("previousClose", close.size() >= 2 ? close.get(close.size() - 2) : 0);

        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("open", new ArrayList<>(open));
        quote.put("high", new ArrayList<>(high));
        quote.put("low", new ArrayList<>(low));
        quote.put("close", new ArrayList<>(close));
        quote.put("volume", new ArrayList<>(volume));

        Map<String, Object> adjCloseMap = new LinkedHashMap<>();
        adjCloseMap.put("adjclose", new ArrayList<>(adjClose));

        Map<String, Object> indicators = new LinkedHashMap<>();
        indicators.put("quote", List.of(quote));
        indicators.put("adjclose", List.of(adjCloseMap));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meta", meta);
        result.put("timestamp", new ArrayList<>(timestamps));
        result.put("indicators", indicators);

        // Mirror Yahoo's events.dividends structure so a future "raw vs. adjusted" toggle has the
        // dividend amounts client-side. Keyed by the paying row's timestamp.
        Map<String, Object> divMap = new LinkedHashMap<>();
        for (int i = 0; i < dividends.size(); i++) {
            double d = dividends.get(i);
            if (d > 0) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("amount", d);
                entry.put("date", timestamps.get(i));
                divMap.put(String.valueOf(timestamps.get(i)), entry);
            }
        }
        if (!divMap.isEmpty()) {
            Map<String, Object> events = new LinkedHashMap<>();
            events.put("dividends", divMap);
            result.put("events", events);
        }

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("result", List.of(result));
        chart.put("error", null);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("chart", chart);
        return root;
    }
}
