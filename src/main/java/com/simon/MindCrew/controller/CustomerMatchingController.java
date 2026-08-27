package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.service.CustomerMatchingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Customer Matching Engine · 客户匹配引擎 API
 *
 *   POST /api/customer-matching/analyze   单客户 AI 分析
 */
@RestController
@RequestMapping("/api/customer-matching")
@RequiredArgsConstructor
public class CustomerMatchingController {

    private final CustomerMatchingService customerMatchingService;

    @PostMapping("/analyze")
    public Result<CustomerMatchingService.AnalysisResult> analyze(@RequestBody CustomerMatchingService.AnalyzeRequest req) {
        return Result.success(customerMatchingService.analyze(req.company));
    }
}
