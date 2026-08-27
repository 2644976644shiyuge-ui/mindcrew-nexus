package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.LeadHuntSession;
import com.simon.MindCrew.service.LeadHunterService;
import com.simon.MindCrew.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 全球获客数字员工 (Global Lead Hunter) API
 *
 *   POST /api/lead-hunter/start          启动猎单任务（异步）
 *   GET  /api/lead-hunter/status/{id}    实时进度（11 步 Agent 工作流 + 统计）
 *   GET  /api/lead-hunter/leads/{id}     结果列表（筛选/分页）
 *   GET  /api/lead-hunter/export/{id}    导出 xlsx / csv（26 列）
 *   GET  /api/lead-hunter/sessions       历史任务
 *
 * 产品红线：只做发现与验证，不自动发邮件、不碰 LinkedIn/WhatsApp、不自动上传 CRM。
 */
@RestController
@RequestMapping("/api/lead-hunter")
@RequiredArgsConstructor
public class LeadHunterController {

    private final LeadHunterService leadHunterService;
    private final UserService userService;

    /** 启动任务 */
    @PostMapping("/start")
    public Result<Map<String, Object>> start(@RequestBody LeadHunterService.StartRequest req) {
        Long uid = userService.getCurrentUserId();
        Long sessionId = leadHunterService.start(uid, req);
        return Result.success(Map.of("sessionId", sessionId));
    }

    /** 实时进度 */
    @GetMapping("/status/{id}")
    public Result<Map<String, Object>> status(@PathVariable Long id) {
        return Result.success(leadHunterService.getStatus(userService.getCurrentUserId(), id));
    }

    /** 结果列表 */
    @GetMapping("/leads/{id}")
    public Result<Map<String, Object>> leads(
            @PathVariable Long id,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String emailStatus,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) Boolean onlyWithContact,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(leadHunterService.getLeads(
                userService.getCurrentUserId(), id, keyword, emailStatus, minScore, onlyWithContact,
                Math.max(1, page), Math.max(1, Math.min(size, 100))));
    }

    /** 导出 */
    @GetMapping("/export/{id}")
    public ResponseEntity<byte[]> export(@PathVariable Long id,
                                         @RequestParam(defaultValue = "xlsx") String format) throws Exception {
        byte[] bytes = leadHunterService.export(userService.getCurrentUserId(), id, format);
        boolean csv = "csv".equalsIgnoreCase(format);
        String ext = csv ? "csv" : "xlsx";
        String mime = csv ? "text/csv" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        String filename = URLEncoder.encode("leads-" + id + "." + ext, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(mime + ";charset=UTF-8"))
                .body(bytes);
    }

    /** 历史任务（本人发起的） */
    @GetMapping("/sessions")
    public Result<List<LeadHuntSession>> sessions() {
        Long uid = userService.getCurrentUserId();
        return Result.success(leadHunterService.listSessions(uid));
    }

    /** 国家维度线索分布（个人历史，或指定当前任务） */
    @GetMapping("/map-stats")
    public Result<List<Map<String, Object>>> mapStats(@RequestParam(required = false) Long sessionId) {
        return Result.success(leadHunterService.getCountryStats(userService.getCurrentUserId(), sessionId));
    }

    /** 取消排队或运行中任务 */
    @PostMapping("/cancel/{id}")
    public Result<Void> cancel(@PathVariable Long id) {
        leadHunterService.cancel(userService.getCurrentUserId(), id);
        return Result.success();
    }
}
