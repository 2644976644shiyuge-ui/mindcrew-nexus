package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.UsageReconcileDaily;
import com.simon.MindCrew.service.BssReconcileService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 阿里云 BSS 对账 · 任务 13.7
 *
 *   GET  /api/v2/usage/reconcile/status     · 当前配置 + 最近一次对账
 *   GET  /api/v2/usage/reconcile/list?days  · 最近 N 天的对账记录
 *   POST /api/v2/usage/reconcile/run?date   · 管理员手动跑某一天
 */
@RestController
@RequestMapping("/api/v2/usage/reconcile")
@RequiredArgsConstructor
public class BssReconcileController {

    private final BssReconcileService service;

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<BssReconcileService.ReconcileStatus> status() {
        return Result.success(service.status());
    }

    @GetMapping("/list")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<List<UsageReconcileDaily>> list(@RequestParam(defaultValue = "30") int days) {
        return Result.success(service.listRecent(days));
    }

    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<UsageReconcileDaily>> run(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        try {
            return Result.success(service.reconcileDate(date));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }
}
