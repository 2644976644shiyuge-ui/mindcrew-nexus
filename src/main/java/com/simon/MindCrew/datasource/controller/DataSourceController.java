package com.simon.MindCrew.datasource.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.datasource.dto.DataSourceBrief;
import com.simon.MindCrew.datasource.dto.IntrospectedTable;
import com.simon.MindCrew.datasource.entity.DataSource;
import com.simon.MindCrew.datasource.entity.DataSourceTable;
import com.simon.MindCrew.datasource.service.DataSourceAclService;
import com.simon.MindCrew.datasource.service.DataSourceService;
import com.simon.MindCrew.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据源可视化配置 API（管理员）
 *
 *   GET    /api/v2/datasource              列表
 *   GET    /api/v2/datasource/{id}         详情
 *   POST   /api/v2/datasource              新建
 *   PUT    /api/v2/datasource/{id}         更新
 *   DELETE /api/v2/datasource/{id}         删除
 *   POST   /api/v2/datasource/test         测试连接（新建/编辑时，未保存也能测）
 *   POST   /api/v2/datasource/{id}/introspect   反查表结构
 *   GET    /api/v2/datasource/{id}/tables  表元数据列表
 *   PUT    /api/v2/datasource/{id}/tables  保存表元数据
 */
@RestController
@RequestMapping("/api/v2/datasource")
@RequiredArgsConstructor
public class DataSourceController {

    private final DataSourceService service;
    private final DataSourceAclService aclService;
    private final com.simon.MindCrew.datasource.service.DataAnalysisService analysisService;
    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<DataSource>> list() {
        return Result.success(service.listAll());
    }

    /**
     * 当前登录用户 ACL 可访问 + enabled 的数据源精简列表。
     * 供「智能问答」里的数据源选择器使用，普通用户也可调用（不暴露连接敏感信息）。
     */
    @GetMapping("/accessible")
    public Result<List<DataSourceBrief>> accessible() {
        Long me = userService.getCurrentUserId();
        List<Long> ids = aclService.listAccessibleIds(me);
        return Result.success(service.listAccessibleBrief(ids));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DataSource> get(@PathVariable Long id) {
        return Result.success(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> create(@RequestBody DataSourceDTO dto) {
        Long me = userService.getCurrentUserId();
        return Result.success(service.create(dto.toEntity(), dto.getPassword(), me));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody DataSourceDTO dto) {
        service.update(id, dto.toEntity(), dto.getPassword());
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }

    /** 测试连接 · 已保存的数据源（带 id，密码可留空用库里的）或临时配置 */
    @PostMapping("/test")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<String> test(@RequestBody DataSourceDTO dto) {
        DataSource ds = dto.toEntity();
        ds.setId(dto.getId());
        String err = service.test(ds, dto.getPassword());
        return err == null ? Result.success("连接成功", "ok")
                           : Result.error("连接失败：" + err);
    }

    @PostMapping("/{id}/introspect")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<IntrospectedTable>> introspect(@PathVariable Long id) {
        return Result.success(service.introspect(id));
    }

    @GetMapping("/{id}/tables")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<DataSourceTable>> listTables(@PathVariable Long id) {
        return Result.success(service.listTables(id));
    }

    @PutMapping("/{id}/tables")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> saveTables(@PathVariable Long id, @RequestBody List<DataSourceTable> tables) {
        service.saveTables(id, tables);
        return Result.success();
    }

    /**
     * 手动「刷新表结构」· 合并式同步：把业务库新表并进来（默认不启用），
     * 不动已有表的启用状态/业务说明。返回新增的表名。
     */
    @PostMapping("/{id}/sync-tables")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<com.simon.MindCrew.datasource.dto.SchemaSyncResult> syncTables(@PathVariable Long id) {
        return Result.success(service.mergeIntrospectedTables(id));
    }

    /**
     * 数据二次分析：对已查到的结果做 数据解读/归因/预测（复用结果，不重新查库）。
     * 任意登录用户可用——数据本就是其问答里已拿到的。
     */
    @PostMapping("/analyze")
    public Result<String> analyze(@RequestBody AnalyzeDTO dto) {
        String text = analysisService.analyze(dto.getType(), dto.getQuestion(),
                dto.getDatasourceName(), dto.getColumns(), dto.getRows(), dto.getSql());
        return Result.success(text);
    }

    @Data
    public static class AnalyzeDTO {
        /** interpret(解读) / attribution(归因) / forecast(预测) */
        private String type;
        private String question;
        private String datasourceName;
        private String sql;
        private List<String> columns;
        private List<List<Object>> rows;
    }

    // ─────────────────────────────────────────────
    @Data
    public static class DataSourceDTO {
        private Long id;
        private String name;
        private String dbType;
        private String host;
        private Integer port;
        private String dbName;
        private String username;
        /** 明文密码 · 更新时留空表示沿用旧值 */
        private String password;
        private String jdbcParams;
        private String description;
        private String visibility;
        private String status;
        /** 是否自动同步表结构（1开/0关） */
        private Integer autoSync;
        /** 同步周期(分钟)，0=仅手动 */
        private Integer syncIntervalMin;

        public DataSource toEntity() {
            DataSource ds = new DataSource();
            ds.setName(name);
            ds.setDbType(dbType);
            ds.setHost(host);
            ds.setPort(port);
            ds.setDbName(dbName);
            ds.setUsername(username);
            ds.setJdbcParams(jdbcParams);
            ds.setDescription(description);
            ds.setVisibility(visibility);
            ds.setStatus(status);
            ds.setAutoSync(autoSync);
            ds.setSyncIntervalMin(syncIntervalMin);
            return ds;
        }
    }
}
