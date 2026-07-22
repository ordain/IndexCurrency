package org.example.indexcurrency.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hamtar rante-serier fran Riksbankens Swea-API och skriver 'date,value'-CSV som IrisBondBackfill
 * (real-lage) laser. Vardena ar i procent.
 *
 * Riksbankens API-portal:  https://developer.api.riksbank.se/apis   (Swea API)
 *
 * Anvandning (fristaende, JDK 11+):
 *   1) Hitta ratt seriesId (fatnamn/id varierar mellan serier):
 *        java RiksbankFetcher.java list obligation
 *        java RiksbankFetcher.java list bostad
 *   2) Hamta en serie:
 *        java RiksbankFetcher.java fetch <seriesId> stats_10y.csv 1986-01-01
 *   3) Eller fyll i SERIES[] nedan med verifierade id:n och hamta alla pa en gang:
 *        java RiksbankFetcher.java presets
 *
 * Om API-porten kraver nyckel: registrera en app pa portalen och satt miljovariabeln
 *   RIKSBANK_API_KEY=<din-subscription-key>
 * (skickas som Ocp-Apim-Subscription-Key).
 */
public class RiksbankFetcher {

    static final String BASE = "https://api.riksbank.se/swea/v1";
    static final String KEY  = System.getenv().getOrDefault("RIKSBANK_API_KEY", "");
    static final String FROM = "1986-01-01";

    // Verifierade seriesId fran Swea-API:et (SE GVB = statsobligation, SE MB = bostadsobligation/covered).
    static final String[][] SERIES = {
        { "SEGVB10YC",    "stats_10y.csv" },   // SE Government Bond 10 Year (fran 1987)
        { "SEGVB5YC",     "stats_5y.csv"  },   // SE Government Bond 5 Year  (fran 1985)
        { "SEMB5YCACOMB", "bostad_5y.csv" },   // SE Mortgage Bond 5 Year    (fran 1986)
    };

    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        switch (args[0]) {
            case "list" -> listSeries(args.length > 1 ? args[1] : "");
            case "fetch" -> {
                if (args.length < 3) { usage(); return; }
                fetchToCsv(args[1], args[2], args.length > 3 ? args[3] : FROM);
            }
            case "presets" -> {
                boolean any = false;
                for (String[] s : SERIES) {
                    if (s[0].isBlank()) { System.out.println("Hoppar " + s[1] + " (tomt seriesId - fyll i SERIES[])"); continue; }
                    fetchToCsv(s[0], s[1], FROM);
                    any = true;
                }
                if (!any) System.out.println("Inga seriesId ifyllda. Kor forst: java RiksbankFetcher.java list obligation");
            }
            default -> usage();
        }
    }

    static void usage() {
        System.out.println("""
            RiksbankFetcher  (Swea-API -> date,value CSV, procent)
              list [text]                        lista serier, filtrera pa text (t.ex. obligation / bostad)
              fetch <seriesId> <fil.csv> [from]  hamta en serie (from=YYYY-MM-DD, default 1986-01-01)
              presets                            hamta serierna i SERIES[] (fyll i id:n forst)
            """);
    }

    static String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .GET();
        if (!KEY.isBlank()) b.header("Ocp-Apim-Subscription-Key", KEY);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() == 401 || r.statusCode() == 403)
            throw new IOException("HTTP " + r.statusCode() + " - kraver troligen en subscription-nyckel; "
                    + "registrera en app pa developer.api.riksbank.se och satt RIKSBANK_API_KEY");
        if (r.statusCode() != 200)
            throw new IOException("HTTP " + r.statusCode() + " for " + url + "\n" + trunc(r.body()));
        return r.body();
    }

    /** Lista serier vars (rada) JSON innehaller filtertexten; skriv ut seriesId + en komprimerad rad. */
    static void listSeries(String filter) throws IOException, InterruptedException {
        String json = httpGet(BASE + "/Series");
        String f = filter.toLowerCase(Locale.ROOT);
        int count = 0;
        for (String obj : objects(json)) {
            if (!f.isEmpty() && !obj.toLowerCase(Locale.ROOT).contains(f)) continue;
            String id = firstGroup(obj, "\"seriesId\"\\s*:\\s*\"([^\"]+)\"");
            System.out.println((id != null ? id : "?") + "   " + oneLine(obj));
            count++;
        }
        System.out.println("\n(" + count + " serier" + (filter.isEmpty() ? "" : " matchar '" + filter + "'") + ")");
    }

    static void fetchToCsv(String seriesId, String outFile, String from) throws IOException, InterruptedException {
        String json = httpGet(BASE + "/Observations/" + seriesId + "/" + from);
        List<String> rows = new ArrayList<>();
        rows.add("date,value");
        int n = 0;
        for (String obj : objects(json)) {
            String d = firstGroup(obj, "\"date\"\\s*:\\s*\"([^\"]+)\"");
            String v = firstGroup(obj, "\"value\"\\s*:\\s*\"?(-?[0-9.]+)\"?");
            if (d == null || v == null) continue;    // hoppar null/tomma varden
            rows.add(d + "," + v);
            n++;
        }
        if (n == 0) throw new IOException("Inga observationer for " + seriesId + " (fel seriesId?). Kor 'list' for att hitta ratt id.");
        Files.write(Path.of(outFile), rows);
        System.out.printf("Skrev %s: %d observationer (%s ->) fran serie %s%n", outFile, n, from, seriesId);
    }

    // ── liten JSON-hjalp: platt array av objekt utan nastlade objekt ──
    static List<String> objects(String json) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(json);   // "leaf"-objekt
        while (m.find()) out.add(m.group());
        return out;
    }
    static String firstGroup(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }
    static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }
    static String trunc(String s) { return s == null ? "" : (s.length() > 500 ? s.substring(0, 500) : s); }
}
