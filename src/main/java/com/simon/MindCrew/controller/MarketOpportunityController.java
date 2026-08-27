package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.service.MarketOpportunityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Market Opportunity Map · 市场机会地图 API
 *
 *   GET /api/market-opportunity/overview          全球概览（地图着色 + 排行）
 *   GET /api/market-opportunity/detail/{country}   单国家情报面板
 *   GET /api/market-opportunity/actions/{country}  AI 行动建议
 */
@RestController
@RequestMapping("/api/market-opportunity")
@RequiredArgsConstructor
public class MarketOpportunityController {

    private final MarketOpportunityService marketOpportunityService;

    @GetMapping("/overview")
    public Result<List<MarketOpportunityService.CountryOpportunity>> overview() {
        return Result.success(marketOpportunityService.getOverview());
    }

    @GetMapping("/detail/{country}")
    public Result<MarketOpportunityService.CountryOpportunity> detail(@PathVariable String country) {
        return Result.success(marketOpportunityService.getDetail(country));
    }

    @GetMapping("/actions/{country}")
    public Result<List<String>> actions(@PathVariable String country) {
        return Result.success(marketOpportunityService.getActionSuggestions(country));
    }
}
