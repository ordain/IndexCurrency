package org.example.indexcurrency.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Serves application defaults from {@code app-defaults.properties} on the classpath, so they can be changed
 * by editing that file rather than the frontend code. The file holds two kinds of entries, told apart by the
 * key:
 *
 * <ul>
 *   <li>Proxy-splice backfills: {@code <fund>=<proxy>} &mdash; exposed at {@code /api/backfill-defaults}.</li>
 *   <li>Risk-free rates: {@code <currency>.RiskFreeRate=<annual percent>} &mdash; exposed at
 *       {@code /api/risk-free-rates}.</li>
 * </ul>
 *
 * <p>Mirrors the {@code investing-overrides.properties} convention: a plain {@code key=value} classpath
 * properties file loaded once at startup.
 */
@RestController
public class AppDefaultsController {

    private static final Logger log = LoggerFactory.getLogger(AppDefaultsController.class);
    private static final String RISK_FREE_SUFFIX = ".RiskFreeRate";

    private final Map<String, String> backfills = new LinkedHashMap<>();
    private final Map<String, Double> riskFreeRates = new LinkedHashMap<>();

    public AppDefaultsController() {
        load();
    }

    private void load() {
        ClassPathResource resource = new ClassPathResource("app-defaults.properties");
        if (!resource.exists()) {
            log.info("No app-defaults.properties on classpath; no defaults");
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            Properties props = new Properties();
            props.load(in);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (key.isBlank() || value == null || value.isBlank()) continue;
                if (key.toLowerCase(Locale.US).endsWith(RISK_FREE_SUFFIX.toLowerCase(Locale.US))) {
                    String currency = key.substring(0, key.length() - RISK_FREE_SUFFIX.length())
                            .trim().toUpperCase(Locale.US);
                    try {
                        riskFreeRates.put(currency, Double.parseDouble(value.trim()));
                    } catch (NumberFormatException e) {
                        log.warn("Ignoring non-numeric risk-free rate for {}: {}", currency, value);
                    }
                } else {
                    // Symbols are uppercased to match how the frontend keys tickers.
                    backfills.put(key.trim().toUpperCase(Locale.US), value.trim().toUpperCase(Locale.US));
                }
            }
            log.info("Loaded {} default backfill(s) and {} risk-free rate(s)", backfills.size(), riskFreeRates.size());
        } catch (Exception e) {
            log.warn("Failed to load app-defaults.properties: {}", e.getMessage());
        }
    }

    @GetMapping("/api/backfill-defaults")
    public Map<String, String> getBackfillDefaults() {
        return backfills;
    }

    @GetMapping("/api/risk-free-rates")
    public Map<String, Double> getRiskFreeRates() {
        return riskFreeRates;
    }
}
