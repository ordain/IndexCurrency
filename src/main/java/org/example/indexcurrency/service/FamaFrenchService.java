package org.example.indexcurrency.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Serves the daily Fama&ndash;French 5-factor returns (Mkt-RF, SMB, HML, RMW, CMA, RF) from Kenneth French's
 * data library. The zipped CSV is downloaded once, its extracted CSV cached under the app cache dir, and the
 * parsed factors held in memory. The frontend correlates each ticker/portfolio's daily returns against these
 * factor series. Factor values are returned as daily decimal returns (the source is in percent).
 */
@Service
public class FamaFrenchService {

    private static final Logger log = LoggerFactory.getLogger(FamaFrenchService.class);
    private static final String URL =
            "https://mba.tuck.dartmouth.edu/pages/faculty/ken.french/ftp/F-F_Research_Data_5_Factors_2x3_daily_CSV.zip";
    private static final long REFRESH_SECONDS = 7 * 24 * 3600L; // the library updates roughly monthly

    private final Path cacheFile;
    private volatile Map<String, Object> cached;

    public FamaFrenchService(@Value("${cache.dir:cache}") String cacheDir) {
        this.cacheFile = Path.of(cacheDir, "fama-french-5factor.csv");
    }

    public synchronized Map<String, Object> getFactors() {
        if (cached != null) return cached;
        String csv = loadCsv();
        cached = (csv == null) ? emptyResult() : parse(csv);
        return cached;
    }

    private Map<String, Object> emptyResult() {
        return Map.of("dates", List.of(), "factors", Map.of());
    }

    /** Fresh cached CSV if young enough, otherwise (re)download; on download failure fall back to any cache. */
    private String loadCsv() {
        try {
            if (Files.exists(cacheFile)) {
                long age = Instant.now().getEpochSecond()
                        - Files.getLastModifiedTime(cacheFile).toInstant().getEpochSecond();
                if (age < REFRESH_SECONDS) return Files.readString(cacheFile);
            }
        } catch (IOException e) {
            log.warn("Could not read Fama-French cache: {}", e.getMessage());
        }
        try {
            String csv = download();
            try {
                Files.createDirectories(cacheFile.getParent());
                Files.writeString(cacheFile, csv);
            } catch (IOException e) {
                log.warn("Could not write Fama-French cache: {}", e.getMessage());
            }
            return csv;
        } catch (Exception e) {
            log.warn("Fama-French download failed: {}", e.getMessage());
            try {
                if (Files.exists(cacheFile)) return Files.readString(cacheFile); // stale is better than nothing
            } catch (IOException ex) {
                log.warn("Could not read stale Fama-French cache: {}", ex.getMessage());
            }
            return null;
        }
    }

    private String download() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(40))
                .GET().build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(resp.body()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                zis.transferTo(bos);
                return bos.toString(StandardCharsets.UTF_8);
            }
        }
        throw new IOException("empty zip");
    }

    /**
     * Parse the daily 5-factor CSV. The file has a preamble, then a header row {@code ,Mkt-RF,SMB,HML,RMW,CMA,RF},
     * then rows of {@code YYYYMMDD,<six percents>}. Parsing stops at the first blank/non-date line after the data.
     */
    private Map<String, Object> parse(String csv) {
        List<String> dates = new ArrayList<>();
        List<Double> mkt = new ArrayList<>(), smb = new ArrayList<>(), hml = new ArrayList<>(),
                rmw = new ArrayList<>(), cma = new ArrayList<>(), rf = new ArrayList<>();
        boolean inData = false;
        for (String raw : csv.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (inData) break;
                continue;
            }
            if (!inData) {
                if (line.contains("Mkt-RF")) inData = true;
                continue;
            }
            String[] p = line.split(",");
            if (p.length < 7 || !p[0].trim().matches("\\d{8}")) break;
            try {
                String d = p[0].trim();
                double m = Double.parseDouble(p[1].trim()) / 100.0;
                double s = Double.parseDouble(p[2].trim()) / 100.0;
                double h = Double.parseDouble(p[3].trim()) / 100.0;
                double r = Double.parseDouble(p[4].trim()) / 100.0;
                double c = Double.parseDouble(p[5].trim()) / 100.0;
                double f = Double.parseDouble(p[6].trim()) / 100.0;
                dates.add(d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8));
                mkt.add(m); smb.add(s); hml.add(h); rmw.add(r); cma.add(c); rf.add(f);
            } catch (NumberFormatException e) {
                break;
            }
        }
        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("MKT", mkt);
        factors.put("SMB", smb);
        factors.put("HML", hml);
        factors.put("RMW", rmw);
        factors.put("CMA", cma);
        factors.put("RF", rf);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dates", dates);
        out.put("factors", factors);
        log.info("Loaded {} daily Fama-French 5-factor rows", dates.size());
        return out;
    }
}
