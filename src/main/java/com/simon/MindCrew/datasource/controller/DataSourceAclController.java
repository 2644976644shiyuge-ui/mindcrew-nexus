package com.simon.MindCrew.datasource.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.datasource.entity.DataSourceAcl;
import com.simon.MindCrew.datasource.service.DataSourceAclService;
import com.simon.MindCrew.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据源访问控制 API（镜像 KbAclController）
 *
 *   GET  /api/v2/datasource-acl/{dsId}            列出某数据源的所有授权
 *   PUT  /api/v2/datasource-acl/{dsId}/replace    一次性替换授权（前端整体保存）
 *   GET  /api/v2/datasource-acl/check?dsId=       当前用户是否可查询该数据源
 *   GET  /api/v2/datasource-acl/accessible        当前用户可查询的数据源 ID 列表
 */
@RestController
@RequestMapping("/api/v2/datasource-acl")
@RequiredArgsConstructor
public class DataSourceAclController {

    private final DataSourceAclService aclService;
    private final UserService userService;

    @GetMapping("/{dsId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<DataSourceAcl>> list(@PathVariable Long dsId) {
        return Result.success(aclService.listAcl(dsId));
    }

    @PutMapping("/{dsId}/replace")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> replace(@PathVariable Long dsId, @RequestBody ReplaceDTO dto) {
        Long me = userService.getCurrentUserId();
        List<DataSourceAclService.AclEntry> entries = dto.getEntries() == null ? List.of()
                : dto.getEntries().stream()
                    .map(e -> new DataSourceAclService.AclEntry(
                            e.getPositionId(), e.getDepartmentId(), e.getUserId(),
                            e.getPermission() == null ? "read" : e.getPermission()))
                    .collect(Collectors.toList());
        aclService.replaceAcls(dsId, entries, me);
        return Result.success();
    }

    @GetMapping("/check")
    public Result<Boolean> check(@RequestParam Long dsId,
                                 @RequestParam(defaultValue = "read") String perm) {
        Long me = userService.getCurrentUserId();
        return Result.success(aclService.canAccess(me, dsId, perm));
    }

    @GetMapping("/accessible")
    public Result<List<Long>> accessible() {
        Long me = userService.getCurrentUserId();
        return Result.success(aclService.listAccessibleIds(me));
    }

    // ─────────────────────────────────────────────
    @Data
    public static class ReplaceDTO {
        private List<Entry> entries;
    }

    @Data
    public static class Entry {
        private Long positionId;
        private Long departmentId;
        private Long userId;
        private String permission;
    }
}
