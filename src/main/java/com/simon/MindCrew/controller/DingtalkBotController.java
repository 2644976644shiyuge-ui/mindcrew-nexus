package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.DingtalkBot;
import com.simon.MindCrew.service.DingtalkBotService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理后台 · 钉钉机器人配置 CRUD
 */
@RestController
@RequestMapping("/api/v2/dingtalk-bot")
@RequiredArgsConstructor
public class DingtalkBotController {

    private final DingtalkBotService service;
    private final com.simon.MindCrew.service.DingtalkStreamManager streamManager;

    @GetMapping("/list")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<DingtalkBot>> list() {
        return Result.success(service.listAll());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DingtalkBot> create(@RequestBody BotDTO dto) {
        DingtalkBot bot = service.create(dto.getName(), dto.getAppKey(), dto.getCollectionId(),
                dto.getAppSecret(), dto.getSignatureVerify(), dto.getDescription());
        streamManager.restartBot(bot.getId());
        return Result.success(bot);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody BotDTO dto) {
        service.update(id, dto.getName(), dto.getAppKey(), dto.getCollectionId(),
                dto.getAppSecret(), dto.getSignatureVerify(), dto.getDescription());
        streamManager.restartBot(id);
        return Result.success();
    }

    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        service.setEnabled(id, enabled);
        if (enabled) streamManager.restartBot(id); else streamManager.stopBot(id);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        streamManager.stopBot(id);
        service.delete(id);
        return Result.success();
    }

    /** 聊天记录 · 分页 · botId/keyword 可空 */
    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<com.simon.MindCrew.entity.vo.PageVO<com.simon.MindCrew.entity.DingtalkChatLog>> logs(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "20") Integer size,
            @RequestParam(value = "botId", required = false) Long botId,
            @RequestParam(value = "keyword", required = false) String keyword) {
        return Result.success(com.simon.MindCrew.entity.vo.PageVO.of(
                service.listChatLogs(current, size, botId, keyword)));
    }

    @Data
    public static class BotDTO {
        private String name;
        private String appKey;
        private Long collectionId;
        /** 钉钉 AppSecret · 更新时留空表示不改 */
        private String appSecret;
        private Integer signatureVerify;
        private String description;
    }
}
