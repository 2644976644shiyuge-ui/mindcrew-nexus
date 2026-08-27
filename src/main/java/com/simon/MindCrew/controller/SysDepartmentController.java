package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.SysDepartment;
import com.simon.MindCrew.service.SysDepartmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.simon.MindCrew.controller.SysPositionController.PermissionsDTO;

@RestController
@RequestMapping("/api/v2/department")
@RequiredArgsConstructor
public class SysDepartmentController {

    private final SysDepartmentService service;

    @GetMapping("/list")
    public Result<List<SysDepartment>> list() {
        return Result.success(service.listAll());
    }

    @GetMapping("/tree")
    public Result<List<SysDepartmentService.DeptNode>> tree() {
        return Result.success(service.tree());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> create(@RequestBody SysDepartment d) {
        return Result.success(service.create(d));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysDepartment d) {
        d.setId(id);
        service.update(d);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }

    /** #3 · 配置部门默认可用功能（permissions=null 表示清空=继承基线） */
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updatePermissions(@PathVariable Long id, @RequestBody PermissionsDTO dto) {
        service.updatePermissions(id, SysPositionController.toJson(dto.getPermissions()));
        return Result.success();
    }
}
