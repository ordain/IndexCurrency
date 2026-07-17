package org.example.indexcurrency.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Serves the default proxy-splice backfills (fund&rarr;proxy) from {@code backfill-defaults.properties} on
 * the classpath, so the mapping can be changed by editing that file rather than the frontend code. The
 * frontend fetches this at startup and seeds each fund's backfill proxy from it.
 *
 * <p>Mirrors the {@code investing-overrides.properties} convention: a plain {@code key=value} classpath
 * properties file loaded once at startup.
 */
@RestController
@RequestMapping("/api/backfill-defaults")
public class BackfillDefaultsController {

    private static final Logger log = LoggerFactory.getLogger(BackfillDefaultsController.class);

    private final Map<String, String> defaults;

    public BackfillDefaultsController() {
        this.defaults = load();
    }

    private Map<String, String> load() {
        Map<String, String> map = new LinkedHashMap<>();
        ClassPathResource resource = new ClassPathResource("backfill-defaults.properties");
        if (!resource.exists()) {
            log.info("No backfill-defaults.properties on classpath; no default backfills");
            return map;
        }
        try (InputStream in = resource.getInputStream()) {
            Properties props = new Properties();
            props.load(in);
            for (String fund : props.stringPropertyNames()) {
                String proxy = props.getProperty(fund);
                if (fund.isBlank() || proxy == null || proxy.isBlank()) continue;
                // Symbols are uppercased to match how the frontend keys tickers.
                map.put(fund.trim().toUpperCase(Locale.US), proxy.trim().toUpperCase(Locale.US));
            }
            log.info("Loaded {} default backfill(s)", map.size());
        } catch (Exception e) {
            log.warn("Failed to load backfill-defaults.properties: {}", e.getMessage());
        }
        return map;
    }

    @GetMapping
    public Map<String, String> getDefaults() {
        return defaults;
    }
}
