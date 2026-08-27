package com.simon.MindCrew.digitalemployee.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.util.List;

public record PptGenerationTaskRequest(
        @NotBlank(message = "请描述要生成的演示文稿")
        @Size(max = 20_000, message = "描述不能超过 20000 字")
        String prompt,
        @Size(max = 200, message = "标题不能超过 200 字")
        String title,
        @Min(value = 4, message = "页数不能少于 4 页")
        @Max(value = 40, message = "页数不能超过 40 页")
        Integer pageCount,
        @Size(max = 32) String language,
        @Size(max = 32) String visualStyle,
        @Size(max = 200) String audience,
        @Size(max = 200) String purpose,
        Long employeeId,
        Long conversationId,
        Long baseTaskId,
        @Size(max = 10, message = "单次最多上传 10 个附件")
        List<@Valid AttachmentRef> attachments
) {
    public record AttachmentRef(
            @NotBlank @Size(max = 512) String objectName,
            @Size(max = 255) String name
    ) {
    }
}
