package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.SkillPack;
import com.simon.MindCrew.service.SkillPackService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 技能包接口
 *   GET  /api/v2/skill-pack/usable  · 销售端可用列表（任意登录用户）
 *   GET  /api/v2/skill-pack/list    · 管理员全部
 *   POST/PUT/DELETE                 · 管理员维护
 */
@RestController
@RequestMapping("/api/v2/skill-pack")
@RequiredArgsConstructor
public class SkillPackController {

    private final SkillPackService service;

    /** 销售端：可用技能包（提问时选） */
    @GetMapping("/usable")
    public Result<List<SkillPack>> usable() {
        return Result.success(service.listEnabled());
    }

    @GetMapping("/list")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<SkillPack>> list() {
        return Result.success(service.listAll());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<SkillPack> create(@RequestBody SkillPack body) {
        return Result.success(service.create(body));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody SkillPack body) {
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
