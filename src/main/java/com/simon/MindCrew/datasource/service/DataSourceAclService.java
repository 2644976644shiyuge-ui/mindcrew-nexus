package com.simon.MindCrew.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.datasource.entity.DataSource;
import com.simon.MindCrew.datasource.entity.DataSourceAcl;
import com.simon.MindCrew.datasource.mapper.DataSourceAclMapper;
import com.simon.MindCrew.datasource.mapper.DataSourceMapper;
import com.simon.MindCrew.entity.SysDepartment;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.mapper.SysDepartmentMapper;
import com.simon.MindCrew.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 数据源 ACL 服务 · 镜像 {@link com.simon.MindCrew.service.KbAclService} 的判定逻辑
 *
 * 访问规则（按优先级）：
 *   1. 系统管理员（role=admin）       → 全部数据源通行
 *   2. visibility = public            → 所有登录用户可读
 *   3. visibility = private           → 仅 creator (user_id)
 *   4. visibility = scoped            → 按 data_source_acl 命中（用户 / 职位 / 部门含祖先）
 *
 * 权限层级：admin > read。read 操作需要至少 read。
 *
 * 务实约束（与 KbAclService 一致）：失败返回 false 而非抛异常；提供批量 listAccessibleIds 避免 N+1。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceAclService {

    private final DataSourceAclMapper aclMapper;
    private final DataSourceMapper dsMapper;
    private final SysUserMapper userMapper;
    private final SysDepartmentMapper deptMapper;

    public static final String PERM_READ  = "read";
    public static final String PERM_ADMIN = "admin";

    private static final Map<String, Integer> PERM_LEVEL = Map.of(
            PERM_READ, 1,
            PERM_ADMIN, 2
    );

    // ─────────────────────────────────────────────
    // 单点判定
    // ─────────────────────────────────────────────
    public boolean canAccess(Long userId, Long datasourceId, String requiredPerm) {
        if (userId == null || datasourceId == null) return false;

        SysUser user = userMapper.selectById(userId);
        if (user == null) return false;
        if ("admin".equalsIgnoreCase(user.getRole())) return true;

        DataSource ds = dsMapper.selectById(datasourceId);
        if (ds == null) return false;

        String visibility = ds.getVisibility() == null ? "private" : ds.getVisibility();

        if ("public".equals(visibility)) {
            if (PERM_READ.equals(requiredPerm)) return true;
            return Objects.equals(user.getId(), ds.getUserId());
        }
        if ("private".equals(visibility)) {
            return Objects.equals(user.getId(), ds.getUserId());
        }

        // scoped
        if (Objects.equals(user.getId(), ds.getUserId())) return true;

        List<DataSourceAcl> acls = aclMapper.selectList(new LambdaQueryWrapper<DataSourceAcl>()
                .eq(DataSourceAcl::getDatasourceId, datasourceId));
        if (acls.isEmpty()) return false;

        // a) 用户级
        for (DataSourceAcl a : acls) {
            if (a.getUserId() != null
                    && a.getUserId().equals(user.getId())
                    && permitsAtLeast(a.getPermission(), requiredPerm)) {
                return true;
            }
        }
        // b) 职位级
        if (user.getPositionId() != null) {
            for (DataSourceAcl a : acls) {
                if (a.getPositionId() != null
                        && a.getPositionId().equals(user.getPositionId())
                        && permitsAtLeast(a.getPermission(), requiredPerm)) {
                    return true;
                }
            }
        }
        // c) 部门级（含祖先链 · 上级部门授权下级自动有权）
        Set<Long> deptChain = resolveDeptAncestors(user.getDepartmentId());
        if (!deptChain.isEmpty()) {
            for (DataSourceAcl a : acls) {
                if (a.getDepartmentId() != null
                        && deptChain.contains(a.getDepartmentId())
                        && permitsAtLeast(a.getPermission(), requiredPerm)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────
    // 批量：用户可读的全部数据源 ID（NL2SQL / 列表过滤一次性入口）
    // ─────────────────────────────────────────────
    public List<Long> listAccessibleIds(Long userId) {
        if (userId == null) return Collections.emptyList();
        SysUser user = userMapper.selectById(userId);
        if (user == null) return Collections.emptyList();

        if ("admin".equalsIgnoreCase(user.getRole())) {
            return dsMapper.selectList(new LambdaQueryWrapper<DataSource>()
                            .select(DataSource::getId))
                    .stream().map(DataSource::getId).toList();
        }

        List<DataSource> all = dsMapper.selectList(new LambdaQueryWrapper<DataSource>()
                .select(DataSource::getId, DataSource::getVisibility, DataSource::getUserId));

        // 一次性算出 scoped 命中集合（用户 / 职位 / 部门祖先）
        Set<Long> scopedHits = new HashSet<>();
        Set<Long> deptChain = resolveDeptAncestors(user.getDepartmentId());

        LambdaQueryWrapper<DataSourceAcl> w = new LambdaQueryWrapper<DataSourceAcl>()
                .and(q -> {
                    q.eq(DataSourceAcl::getUserId, user.getId());
                    if (user.getPositionId() != null) {
                        q.or().eq(DataSourceAcl::getPositionId, user.getPositionId());
                    }
                    if (!deptChain.isEmpty()) {
                        q.or().in(DataSourceAcl::getDepartmentId, deptChain);
                    }
                });
        List<DataSourceAcl> acls = aclMapper.selectList(w);
        for (DataSourceAcl a : acls) {
            if (permitsAtLeast(a.getPermission(), PERM_READ)) scopedHits.add(a.getDatasourceId());
        }

        List<Long> result = new ArrayList<>();
        for (DataSource ds : all) {
            String vis = ds.getVisibility() == null ? "private" : ds.getVisibility();
            if ("public".equals(vis)) result.add(ds.getId());
            else if (Objects.equals(ds.getUserId(), user.getId())) result.add(ds.getId());
            else if ("scoped".equals(vis) && scopedHits.contains(ds.getId())) result.add(ds.getId());
        }
        return result;
    }

    private Set<Long> resolveDeptAncestors(Long deptId) {
        if (deptId == null) return Collections.emptySet();
        Set<Long> chain = new LinkedHashSet<>();
        Long cur = deptId;
        int safety = 16;
        while (cur != null && chain.size() < safety) {
            chain.add(cur);
            SysDepartment d;
            try { d = deptMapper.selectById(cur); }
            catch (Exception e) { break; }
            if (d == null) break;
            cur = d.getParentId();
        }
        return chain;
    }

    private boolean permitsAtLeast(String have, String need) {
        int h = PERM_LEVEL.getOrDefault(have == null ? "" : have, 0);
        int n = PERM_LEVEL.getOrDefault(need == null ? "" : need, 1);
        return h >= n;
    }

    // ─────────────────────────────────────────────
    // ACL 写入
    // ─────────────────────────────────────────────
    public List<DataSourceAcl> listAcl(Long datasourceId) {
        return aclMapper.selectList(new LambdaQueryWrapper<DataSourceAcl>()
                .eq(DataSourceAcl::getDatasourceId, datasourceId)
                .orderByAsc(DataSourceAcl::getDepartmentId)
                .orderByAsc(DataSourceAcl::getPositionId)
                .orderByAsc(DataSourceAcl::getUserId));
    }

    /** ACL 条目 · positionId / departmentId / userId 三选一 */
    public record AclEntry(Long positionId, Long departmentId, Long userId, String permission) {}

    /** 一次性替换某数据源的所有 ACL（前端整体保存） */
    @Transactional(rollbackFor = Exception.class)
    public void replaceAcls(Long datasourceId, List<AclEntry> entries, Long grantedBy) {
        aclMapper.delete(new LambdaQueryWrapper<DataSourceAcl>()
                .eq(DataSourceAcl::getDatasourceId, datasourceId));
        if (entries == null) return;
        for (AclEntry e : entries) {
            String perm = e.permission() == null ? PERM_READ : e.permission();
            if (!PERM_LEVEL.containsKey(perm)) continue;
            // 三选一校验
            int subjectCount = (e.positionId() != null ? 1 : 0)
                    + (e.departmentId() != null ? 1 : 0)
                    + (e.userId() != null ? 1 : 0);
            if (subjectCount != 1) continue;
            DataSourceAcl acl = new DataSourceAcl();
            acl.setDatasourceId(datasourceId);
            acl.setPositionId(e.positionId());
            acl.setDepartmentId(e.departmentId());
            acl.setUserId(e.userId());
            acl.setPermission(perm);
            acl.setGrantedBy(grantedBy);
            aclMapper.insert(acl);
        }
        log.info("[DsAcl] 数据源 {} 替换 {} 条授权", datasourceId, entries.size());
    }
}
