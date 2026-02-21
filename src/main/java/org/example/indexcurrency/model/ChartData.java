package org.example.indexcurrency.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChartData {
    private String symbol;
    private String currency;
    private String shortName;
    private String exchangeTimezoneName;
    private String fetchedRange;
    private final List<Long> timestamps = new ArrayList<>();
    private final List<Double> open = new ArrayList<>();
    private final List<Double> high = new ArrayList<>();
    private final List<Double> low = new ArrayList<>();
    private final List<Double> close = new ArrayList<>();
    private final List<Double> adjClose = new ArrayList<>();
    private final List<Long> volume = new ArrayList<>();

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

    public List<Long> getTimestamps() { return timestamps; }
    public List<Double> getOpen() { return open; }
    public List<Double> getHigh() { return high; }
    public List<Double> getLow() { return low; }
    public List<Double> getClose() { return close; }
    public List<Double> getAdjClose() { return adjClose; }
    public List<Long> getVolume() { return volume; }

    public void addRow(long ts, double o, double h, double l, double c, double ac, long v) {
        timestamps.add(ts);
        open.add(o);
        high.add(h);
        low.add(l);
        close.add(c);
        adjClose.add(ac);
        volume.add(v);
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
                        newer.low.get(i), newer.close.get(i), newer.adjClose.get(i), newer.volume.get(i));
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

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("result", List.of(result));
        chart.put("error", null);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("chart", chart);
        return root;
    }
}
