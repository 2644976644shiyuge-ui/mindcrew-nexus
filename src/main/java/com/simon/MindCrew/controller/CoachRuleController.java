package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.CoachRule;
import com.simon.MindCrew.service.CoachRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 教练规则接口
 *   GET  /api/v2/coach-rule/usable  · 可用列表（任意登录用户 · 知识库下拉选）
 *   GET  /api/v2/coach-rule/list    · 管理员全部
 *   POST/PUT/DELETE                 · 管理员维护
 */
@RestController
@RequestMapping("/api/v2/coach-rule")
@RequiredArgsConstructor
public class CoachRuleController {

    private final CoachRuleService service;

    /** 可用教练规则（知识库设置里下拉选） */
    @GetMapping("/usable")
    public Result<List<CoachRule>> usable() {
        return Result.success(service.listEnabled());
    }

    @GetMapping("/list")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<CoachRule>> list() {
        return Result.success(service.listAll());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<CoachRule> create(@RequestBody CoachRule body) {
        return Result.success(service.create(body));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody CoachRule body) {
        service.update(id, body);
        return Result.success();
    }

    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        service.setEnabled(id, enabled);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }
}
