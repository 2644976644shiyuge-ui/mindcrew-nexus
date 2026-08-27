package com.simon.MindCrew.controller;

import com.alibaba.fastjson2.JSON;
import com.simon.MindCrew.common.FeatureCatalog;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.SysPosition;
import com.simon.MindCrew.service.SysPositionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/position")
@RequiredArgsConstructor
public class SysPositionController {

    private final SysPositionService service;

    @GetMapping("/list")
    public Result<List<SysPosition>> list(@RequestParam(required = false) Long departmentId) {
        return Result.success(
                departmentId == null ? service.listAll() : service.listByDepartment(departmentId)
        );
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> create(@RequestBody SysPosition p) {
        return Result.success(service.create(p));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysPosition p) {
        p.setId(id);
        service.update(p);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }

    /** #3 · 功能权限目录（供部门/职位配置勾选） */
    @GetMapping("/features")
    public Result<List<FeatureCatalog.Feature>> features() {
        return Result.success(FeatureCatalog.FEATURES);
    }

    /** #3 · 配置职位可用功能（permissions=null 表示清空=继承部门/基线） */
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updatePermissions(@PathVariable Long id, @RequestBody PermissionsDTO dto) {
        service.updatePermissions(id, toJson(dto.getPermissions()));
        return Result.success();
    }

    static String toJson(List<String> perms) {
        if (perms == null) return null;   // null=继承
        // 只保留合法 key，去重，保持目录顺序
        List<String> clean = FeatureCatalog.allKeys().stream().filter(perms::contains).toList();
        return JSON.toJSONString(clean);
    }

    @Data
    public static class PermissionsDTO {
        private List<String> permissions;
    }
}
