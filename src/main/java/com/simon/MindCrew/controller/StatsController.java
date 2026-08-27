package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 数据统计接口
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    /**
     * 首页统计大屏数据
     *
     * <p>v2：放开 admin 限制，普通用户也能访问数据大屏（用户已通过菜单 ACL 控制可见性，
     * 后端不再二次拦截）。如未来需对普通用户脱敏某些指标，可在 StatsService 里按
     * SecurityContext 用户角色裁剪返回字段。
     */
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(
            @RequestParam(value = "timeRange", defaultValue = "week") String timeRange) {
        return Result.success(statsService.getDashboard(timeRange));
    }
}
