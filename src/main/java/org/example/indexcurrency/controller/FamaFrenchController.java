package org.example.indexcurrency.controller;

import org.example.indexcurrency.service.FamaFrenchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes the daily Fama&ndash;French 5-factor returns at {@code /api/fama-french} as
 * {@code { dates: [...], factors: { MKT, SMB, HML, RMW, CMA, RF } } }, each factor a parallel array of daily
 * decimal returns. The frontend uses these to compute per-factor return correlations.
 */
@RestController
public class FamaFrenchController {

    private final FamaFrenchService service;

    public FamaFrenchController(FamaFrenchService service) {
        this.service = service;
    }

    @GetMapping("/api/fama-french")
    public Map<String, Object> getFactors() {
        return service.getFactors();
    }
}
