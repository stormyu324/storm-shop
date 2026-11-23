package com.javastorm.shop.api.admin;

import com.javastorm.shop.service.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/analytics")
public class AdminAnalyticsApi {
    private final AnalyticsService service;

    public AdminAnalyticsApi(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping
    public List<AnalyticsService.Summary> report(
            @RequestParam(required = false) LocalDate start,
            @RequestParam(required = false) LocalDate end) {
        LocalDate last = end == null ? LocalDate.now() : end;
        return service.report(start == null ? last.minusDays(29) : start, last);
    }
}
