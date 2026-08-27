package com.simon.MindCrew.digitalemployee.controller;

import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.digitalemployee.dto.PptGenerationTaskRequest;
import com.simon.MindCrew.digitalemployee.dto.PptGenerationTaskVO;
import com.simon.MindCrew.digitalemployee.entity.PptGenerationTask;
import com.simon.MindCrew.digitalemployee.service.PptGenerationTaskService;
import com.simon.MindCrew.digitalemployee.service.PptGenerationTaskWorker;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.service.knowledge.FileStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/ppt/tasks")
@RequiredArgsConstructor
public class PptGenerationTaskController {

    private static final MediaType PPTX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final MediaType PDF = MediaType.APPLICATION_PDF;

    private final UserService userService;
    private final PptGenerationTaskService taskService;
    private final PptGenerationTaskWorker worker;
    private final FileStorageService fileStorage;

    @PostMapping
    public Result<PptGenerationTaskVO> create(@Valid @RequestBody PptGenerationTaskRequest request) {
        PptGenerationTaskVO task = taskService.create(userService.getCurrentUserId(), request);
        dispatch(task.id());
        return Result.success("任务已进入后台生成", task);
    }

    @GetMapping
    public Result<List<PptGenerationTaskVO>> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) Long conversationId) {
        return Result.success(taskService.listMine(
                userService.getCurrentUserId(), limit, employeeId, conversationId));
    }

    @GetMapping("/{taskId}")
    public Result<PptGenerationTaskVO> detail(@PathVariable Long taskId) {
        return Result.success(taskService.detail(userService.getCurrentUserId(), taskId));
    }

    @PostMapping("/{taskId}/retry")
    public Result<PptGenerationTaskVO> retry(@PathVariable Long taskId) {
        PptGenerationTaskVO task = taskService.retry(userService.getCurrentUserId(), taskId);
        dispatch(task.id());
        return Result.success("任务已重新进入队列", task);
    }

    @PostMapping("/{taskId}/cancel")
    public Result<PptGenerationTaskVO> cancel(@PathVariable Long taskId) {
        PptGenerationTaskVO task = taskService.cancel(userService.getCurrentUserId(), taskId);
        worker.requestCancel(taskId);
        return Result.success("任务已取消", task);
    }

    @GetMapping("/{taskId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long taskId) {
        PptGenerationTask task = taskService.requireOwned(userService.getCurrentUserId(), taskId);
        if (!"completed".equals(task.getStatus()) || task.getObjectName() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "文件尚未生成完成");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(PPTX);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(task.getFileName(), StandardCharsets.UTF_8).build());
        if (task.getFileSize() != null) {
            headers.setContentLength(task.getFileSize());
        }
        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(fileStorage.getFileStream(task.getObjectName())));
    }

    @GetMapping("/{taskId}/preview")
    public ResponseEntity<InputStreamResource> preview(@PathVariable Long taskId) {
        PptGenerationTask task = taskService.requireOwned(userService.getCurrentUserId(), taskId);
        if (!"completed".equals(task.getStatus()) || task.getPreviewObjectName() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "预览尚未生成完成");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(PDF);
        String previewFileName = task.getFileName() == null
                ? "presentation.pdf"
                : task.getFileName().replaceAll("(?i)\\.pptx$", ".pdf");
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(previewFileName, StandardCharsets.UTF_8).build());
        if (task.getPreviewFileSize() != null) {
            headers.setContentLength(task.getPreviewFileSize());
        }
        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(
                        fileStorage.getFileStream(task.getPreviewObjectName())));
    }

    private void dispatch(Long taskId) {
        try {
            worker.generate(taskId);
        } catch (TaskRejectedException e) {
            taskService.markDispatchFailed(taskId, "后台生成队列已满，请稍后重试");
            throw new BusinessException(ResultCode.ERROR.getCode(), "后台生成队列已满，请稍后重试");
        }
    }
}
