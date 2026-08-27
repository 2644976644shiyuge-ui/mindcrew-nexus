package com.simon.MindCrew.digitalemployee.dto;

import com.simon.MindCrew.digitalemployee.entity.PptGenerationTask;

import java.time.LocalDateTime;

public record PptGenerationTaskVO(
        Long id,
        Long employeeId,
        Long conversationId,
        Long parentTaskId,
        Integer versionNo,
        String operationType,
        String prompt,
        String attachments,
        String warnings,
        String title,
        Integer pageCount,
        String language,
        String visualStyle,
        String audience,
        String purpose,
        String status,
        Integer progress,
        String stage,
        String provider,
        String providerName,
        boolean fallbackUsed,
        String fileName,
        Long fileSize,
        Long previewFileSize,
        String errorMessage,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        boolean downloadable,
        boolean previewable,
        boolean cancellable
) {
    public static PptGenerationTaskVO from(PptGenerationTask task) {
        return new PptGenerationTaskVO(
                task.getId(), task.getEmployeeId(), task.getConversationId(),
                task.getParentTaskId(), task.getVersionNo(), task.getOperationType(),
                task.getPrompt(), task.getAttachments(), task.getWarnings(),
                task.getTitle(), task.getPageCount(),
                task.getLanguage(), task.getVisualStyle(), task.getAudience(), task.getPurpose(),
                task.getStatus(), task.getProgress(), task.getStage(),
                task.getProvider(), task.getProviderName(),
                Integer.valueOf(1).equals(task.getFallbackUsed()),
                task.getFileName(), task.getFileSize(), task.getPreviewFileSize(), task.getErrorMessage(),
                task.getCreateTime(), task.getUpdateTime(), task.getStartedAt(), task.getCompletedAt(),
                "completed".equals(task.getStatus()) && task.getObjectName() != null,
                "completed".equals(task.getStatus()) && task.getPreviewObjectName() != null,
                "queued".equals(task.getStatus()) || "generating".equals(task.getStatus())
        );
    }
}
