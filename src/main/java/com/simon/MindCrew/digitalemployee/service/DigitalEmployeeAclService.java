package com.simon.MindCrew.digitalemployee.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.digitalemployee.entity.DigitalEmployee;
import com.simon.MindCrew.digitalemployee.entity.DigitalEmployeeAcl;
import com.simon.MindCrew.digitalemployee.mapper.DigitalEmployeeAclMapper;
import com.simon.MindCrew.digitalemployee.mapper.DigitalEmployeeMapper;
import com.simon.MindCrew.entity.SysDepartment;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.mapper.SysDepartmentMapper;
import com.simon.MindCrew.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class DigitalEmployeeAclService {

    public static final String STATUS_PUBLISHED = "published";
    public static final String VIS_PUBLIC = "public";
    public static final String VIS_RESTRICTED = "restricted";

    private final DigitalEmployeeMapper employeeMapper;
    private final DigitalEmployeeAclMapper aclMapper;
    private final SysUserMapper userMapper;
    private final SysDepartmentMapper deptMapper;

    public boolean canUse(Long userId, Long employeeId) {
        if (userId == null || employeeId == null) return false;
        SysUser user = userMapper.selectById(userId);
        if (user == null) return false;
        if ("admin".equalsIgnoreCase(user.getRole())) return true;

        DigitalEmployee emp = employeeMapper.selectById(employeeId);
        if (emp == null || !STATUS_PUBLISHED.equals(emp.getStatus())) return false;

        if (VIS_PUBLIC.equalsIgnoreCase(emp.getVisibility())) return true;

        if (Objects.equals(emp.getCreatedBy(), userId)) return true;

        List<DigitalEmployeeAcl> acls = aclMapper.selectList(new LambdaQueryWrapper<DigitalEmployeeAcl>()
                .eq(DigitalEmployeeAcl::getEmployeeId, employeeId));
        if (acls.isEmpty()) return false;

        Set<Long> deptChain = resolveDeptAncestors(user.getDepartmentId());
        for (DigitalEmployeeAcl a : acls) {
            if (!"use".equals(a.getPermission()) && !"manage".equals(a.getPermission())) continue;
            switch (a.getPrincipalType() == null ? "" : a.getPrincipalType()) {
                case "user" -> {
                    if (a.getPrincipalId().equals(userId)) return true;
                }
                case "position" -> {
                    if (user.getPositionId() != null && a.getPrincipalId().equals(user.getPositionId())) return true;
                }
                case "department" -> {
                    if (deptChain.contains(a.getPrincipalId())) return true;
                }
                default -> { }
            }
        }
        return false;
    }

    public List<Long> listVisibleEmployeeIds(Long userId) {
        if (userId == null) return List.of();
        SysUser user = userMapper.selectById(userId);
        if (user == null) return List.of();

        List<DigitalEmployee> published = employeeMapper.selectList(new LambdaQueryWrapper<DigitalEmployee>()
                .eq(DigitalEmployee::getStatus, STATUS_PUBLISHED)
                .orderByAsc(DigitalEmployee::getSortOrder)
                .orderByDesc(DigitalEmployee::getId));

        if ("admin".equalsIgnoreCase(user.getRole())) {
            return published.stream().map(DigitalEmployee::getId).toList();
        }

        Set<Long> deptChain = resolveDeptAncestors(user.getDepartmentId());
        List<Long> ids = new ArrayList<>();
        for (DigitalEmployee emp : published) {
            if (canUseInternal(user, emp, deptChain)) {
                ids.add(emp.getId());
            }
        }
        return ids;
    }

    private boolean canUseInternal(SysUser user, DigitalEmployee emp, Set<Long> deptChain) {
        if (VIS_PUBLIC.equalsIgnoreCase(emp.getVisibility())) return true;
        if (Objects.equals(emp.getCreatedBy(), user.getId())) return true;

        List<DigitalEmployeeAcl> acls = aclMapper.selectList(new LambdaQueryWrapper<DigitalEmployeeAcl>()
                .eq(DigitalEmployeeAcl::getEmployeeId, emp.getId()));
        for (DigitalEmployeeAcl a : acls) {
            switch (a.getPrincipalType() == null ? "" : a.getPrincipalType()) {
                case "user" -> {
                    if (a.getPrincipalId().equals(user.getId())) return true;
                }
                case "position" -> {
                    if (user.getPositionId() != null && a.getPrincipalId().equals(user.getPositionId())) return true;
                }
                case "department" -> {
                    if (deptChain.contains(a.getPrincipalId())) return true;
                }
                default -> { }
            }
        }
        return false;
    }

    private Set<Long> resolveDeptAncestors(Long deptId) {
        if (deptId == null) return Collections.emptySet();
        Set<Long> chain = new LinkedHashSet<>();
        Long cur = deptId;
        int safety = 16;
        while (cur != null && chain.size() < safety) {
            chain.add(cur);
            SysDepartment d = deptMapper.selectById(cur);
            if (d == null) break;
            cur = d.getParentId();
        }
        return chain;
    }
}