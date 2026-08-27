package com.simon.MindCrew.digitalemployee.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.digitalemployee.entity.PptGenerationTask;
import com.simon.MindCrew.digitalemployee.entity.DigitalEmployee;
import com.simon.MindCrew.digitalemployee.export.ExportBranding;
import com.simon.MindCrew.digitalemployee.export.PptContentPlanningService;
import com.simon.MindCrew.digitalemployee.export.PptGenerationService;
import com.simon.MindCrew.digitalemployee.mapper.DigitalEmployeeMapper;
import com.simon.MindCrew.digitalemployee.mapper.PptGenerationTaskMapper;
import com.simon.MindCrew.service.knowledge.DocumentExtractor;
import com.simon.MindCrew.service.knowledge.FileStorageService;
import com.simon.MindCrew.service.knowledge.OfficeConverter;
import com.simon.MindCrew.service.rag.BM25Retriever;
import com.simon.MindCrew.service.rag.ParentContextExpander;
import com.simon.MindCrew.service.rag.RRFFusion;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import com.simon.MindCrew.service.rag.VectorRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class PptGenerationTaskWorker {

    private static final String PPTX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final int ATTACHMENT_TEXT_CAP = 24_000;
    private static final int KNOWLEDGE_TEXT_CAP = 20_000;
    private static final int PREVIOUS_VERSION_TEXT_CAP = 24_000;
    private static final int MAX_ATTACHMENT_BYTES = 30 * 1024 * 1024;
    private final ConcurrentMap<Long, Thread> runningTasks = new ConcurrentHashMap<>();

    private final PptGenerationTaskMapper mapper;
    private final PptGenerationService generationService;
    private final PptContentPlanningService planningService;
    private final FileStorageService fileStorage;
    private final DocumentExtractor documentExtractor;
    private final DigitalEmployeeMapper digitalEmployeeMapper;
    private final DigitalEmployeeService digitalEmployeeService;
    private final VectorRetriever vectorRetriever;
    private final BM25Retriever bm25Retriever;
    private final RRFFusion rrfFusion;
    private final ParentContextExpander parentContextExpander;
    private final OfficeConverter officeConverter;
    private final PptGenerationTaskService taskService;

    @Async("pptGenerationExecutor")
    public void generate(Long taskId) {
        PptGenerationTask task = mapper.selectById(taskId);
        if (task == null || !"queued".equals(task.getStatus())) {
            return;
        }
        runningTasks.put(taskId, Thread.currentThread());

        Path tempFile = null;
        Path previewTempFile = null;
        try {
            if (!updateProgress(task, "generating", 12, "正在读取资料")) return;
            ContextResult attachment = extractAttachmentContext(task);
            ContextResult knowledge = extractKnowledgeContext(task);
            ContextResult previousVersion = extractPreviousVersionContext(task);
            List<String> warnings = new ArrayList<>();
            warnings.addAll(attachment.warnings());
            warnings.addAll(knowledge.warnings());
            warnings.addAll(previousVersion.warnings());
            task.setWarnings(warnings.isEmpty() ? null : JSON.toJSONString(warnings));
            mapper.updateById(task);

            if (!updateProgress(task, "generating", 28,
                    "revise".equals(task.getOperationType()) ? "正在分析修改要求" : "正在规划内容")) return;
            String sourceBrief = buildGenerationBrief(task, attachment.context(), knowledge.context(),
                    previousVersion.context());
            PptContentPlanningService.PlanningResult plan = planningService.plan(
                    task.getTitle(), task.getPrompt(), sourceBrief, task.getPageCount());
            warnings.addAll(plan.warnings());
            task.setTitle(plan.title());
            task.setWarnings(warnings.isEmpty() ? null : JSON.toJSONString(warnings));
            mapper.updateById(task);

            PptGenerationService.PptGenerationOptions options =
                    new PptGenerationService.PptGenerationOptions(
                            "auto", task.getVisualStyle(), task.getAudience(), task.getPurpose(),
                            true, true, true);
            if (!updateProgress(task, "generating", 36, "正在生成大纲与页面")) return;

            PptGenerationService.PptGenerationResult result = generationService.generateDetailed(
                    task.getTitle(),
                    plan.markdown(),
                    task.getPrompt(),
                    taskBranding(task),
                    options,
                    (percentage, stage) -> updateProgress(task, "generating",
                            Math.max(37, Math.min(82, percentage)), stage));
            if (taskService.isCanceled(taskId)) return;
            if (result.fallback()) {
                warnings.add("阿里 PPT 服务未能完成正式文件，当前为基础应急版："
                        + PptGenerationTaskService.safeError(
                        result.fallbackReason() == null ? "服务商调用失败" : result.fallbackReason()));
                if (requiresRichVisuals(task.getPrompt())) {
                    warnings.add("基础应急版无法完整实现图片背景等视觉要求，建议重试阿里商用生成");
                }
                task.setWarnings(JSON.toJSONString(warnings));
                mapper.updateById(task);
            }

            if (!updateProgress(task, "generating", 86, "正在保存文件")) return;
            byte[] body = result.body();
            tempFile = Files.createTempFile("mindcrew-ppt-" + taskId + "-", ".pptx");
            Files.write(tempFile, body);
            String objectName = fileStorage.uploadLocalFile(
                    tempFile, "ppt-exports/user-" + task.getUserId(), PPTX_CONTENT_TYPE);

            if (!updateProgress(task, "generating", 93, "正在生成在线预览")) return;
            String previewObjectName = null;
            Long previewFileSize = null;
            try {
                if (!officeConverter.isAvailable()) {
                    throw new IllegalStateException("LibreOffice 不可用");
                }
                try (InputStream preview = officeConverter.convertTo(
                        new ByteArrayInputStream(body), "pptx", "pdf")) {
                    previewTempFile = Files.createTempFile(
                            "mindcrew-ppt-preview-" + taskId + "-", ".pdf");
                    Files.copy(preview, previewTempFile, StandardCopyOption.REPLACE_EXISTING);
                    previewFileSize = Files.size(previewTempFile);
                    previewObjectName = fileStorage.uploadLocalFile(
                            previewTempFile, "ppt-previews/user-" + task.getUserId(),
                            "application/pdf");
                }
            } catch (Exception previewError) {
                warnings.add("在线预览生成失败，PPT 文件仍可正常下载");
                task.setWarnings(JSON.toJSONString(warnings));
                log.warn("[PPT Task] preview failed taskId={}, reason={}",
                        taskId, previewError.getMessage());
            }
            if (taskService.isCanceled(taskId)) return;

            task.setProvider(result.provider());
            task.setProviderName(result.providerName());
            task.setFallbackUsed(result.fallback() ? 1 : 0);
            task.setObjectName(objectName);
            task.setPreviewObjectName(previewObjectName);
            task.setPreviewFileSize(previewFileSize);
            String versionSuffix = task.getVersionNo() != null && task.getVersionNo() > 1
                    ? "-v" + task.getVersionNo() : "";
            task.setFileName(safeFileName(task.getTitle()) + versionSuffix + ".pptx");
            task.setFileSize((long) body.length);
            task.setStatus("completed");
            task.setProgress(100);
            task.setStage(result.fallback() ? "基础应急版已生成" : "阿里商用版生成完成");
            task.setErrorMessage(null);
            task.setCompletedAt(LocalDateTime.now());
            mapper.updateById(task);
            taskService.updateAssistantMessage(task, result.fallback()
                    ? "阿里 PPT 服务本次未能交付正式文件，已生成基础应急版。"
                    + "建议查看原因后重试阿里商用生成。"
                    : "阿里商用 PPT 已生成完成，可直接预览、下载，或继续告诉我需要修改的内容。");
            log.info("[PPT Task] completed taskId={}, userId={}, provider={}, bytes={}",
                    taskId, task.getUserId(), result.provider(), body.length);
        } catch (Exception e) {
            if (taskService.isCanceled(taskId)) return;
            log.error("[PPT Task] failed taskId={}, userId={}", taskId, task.getUserId(), e);
            task.setStatus("failed");
            task.setProgress(0);
            task.setStage("生成失败");
            task.setErrorMessage(PptGenerationTaskService.safeError(rootMessage(e)));
            task.setCompletedAt(LocalDateTime.now());
            mapper.updateById(task);
            taskService.updateAssistantMessage(task,
                    "PPT 生成失败：" + PptGenerationTaskService.safeError(rootMessage(e)));
        } finally {
            runningTasks.remove(taskId, Thread.currentThread());
            // 清除线程池工作线程的中断标记，避免影响下一项任务。
            Thread.interrupted();
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
            if (previewTempFile != null) {
                try {
                    Files.deleteIfExists(previewTempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void requestCancel(Long taskId) {
        Thread running = runningTasks.get(taskId);
        if (running != null) running.interrupt();
    }

    private boolean updateProgress(PptGenerationTask task, String status, int progress, String stage) {
        if (taskService.isCanceled(task.getId())) return false;
        task.setStatus(status);
        task.setProgress(progress);
        task.setStage(stage);
        if (task.getStartedAt() == null) {
            task.setStartedAt(LocalDateTime.now());
        }
        mapper.updateById(task);
        taskService.updateAssistantMessage(task, stage + "（" + progress + "%）");
        return true;
    }

    private static String buildGenerationBrief(PptGenerationTask task, String attachmentContext,
                                               String knowledgeContext, String previousVersionContext) {
        return """
                # 生成要求

                - 目标页数：%d 页（允许根据内容在上下 2 页内调整）
                - 输出语言：%s
                - 版式风格：克制、专业的企业 SaaS 商务风格
                - 汇报对象：%s
                - 汇报目的：%s
                - 内容要求：先结论后论据；每页一个核心观点；自动完成封面、目录、正文和总结
                - 视觉要求：使用清晰的数据卡片、表格、流程和时间线；不使用机器人、炫光、霓虹渐变等 AI 风格元素
                - 事实要求：不得编造用户未提供的数字；信息不足时使用“待补充”占位

                # 用户描述

                %s

                %s

                %s

                %s
                """.formatted(
                task.getPageCount(),
                "zh-CN".equalsIgnoreCase(task.getLanguage()) ? "简体中文" : task.getLanguage(),
                empty(task.getAudience(), "企业管理者"),
                empty(task.getPurpose(), "内部汇报"),
                task.getPrompt(),
                attachmentContext == null || attachmentContext.isBlank()
                        ? "" : "# 用户附件内容\n\n" + attachmentContext,
                knowledgeContext == null || knowledgeContext.isBlank()
                        ? "" : "# 数字员工知识库参考资料\n\n" + knowledgeContext,
                previousVersionContext == null || previousVersionContext.isBlank()
                        ? "" : """
                        # 上一版本 PPT 内容

                        以下内容是待修改的上一版本。仅修改用户明确要求调整的部分，未提及的页面、事实和结构应尽量保留：

                        %s
                        """.formatted(previousVersionContext));
    }

    private ContextResult extractAttachmentContext(PptGenerationTask task) {
        if (task.getAttachments() == null || task.getAttachments().isBlank()) {
            return ContextResult.empty();
        }
        StringBuilder context = new StringBuilder();
        List<String> warnings = new ArrayList<>();
        int used = 0;
        JSONArray attachments;
        try {
            attachments = JSON.parseArray(task.getAttachments());
        } catch (Exception e) {
            log.warn("[PPT Task] invalid attachments taskId={}", task.getId());
            return new ContextResult("", List.of("附件信息格式无效，未纳入本次生成"));
        }
        for (int i = 0; i < attachments.size() && used < ATTACHMENT_TEXT_CAP; i++) {
            JSONObject attachment = attachments.getJSONObject(i);
            if (attachment == null) continue;
            String objectName = attachment.getString("objectName");
            if (objectName == null || !objectName.startsWith("chat-attachment/")) {
                log.warn("[PPT Task] rejected attachment objectName={}", objectName);
                warnings.add("附件“" + empty(attachment.getString("name"), "未知文件")
                        + "”未通过安全校验，未纳入生成");
                continue;
            }
            String name = empty(attachment.getString("name"), objectName);
            try (InputStream input = fileStorage.getFileStream(objectName)) {
                byte[] bytes = input.readNBytes(MAX_ATTACHMENT_BYTES + 1);
                if (bytes.length > MAX_ATTACHMENT_BYTES) {
                    warnings.add("附件“" + name + "”超过 30MB，未纳入生成");
                    continue;
                }
                String text = documentExtractor.extract(
                        new ByteArrayInputStream(bytes), extensionOf(objectName));
                if (text == null || text.isBlank()) continue;
                int remaining = ATTACHMENT_TEXT_CAP - used;
                String normalized = text.trim();
                if (normalized.length() > remaining) {
                    normalized = normalized.substring(0, remaining);
                }
                context.append("【附件：").append(name).append("】\n")
                        .append(normalized).append("\n\n");
                used += normalized.length();
            } catch (Exception e) {
                log.warn("[PPT Task] attachment parse failed taskId={}, objectName={}, reason={}",
                        task.getId(), objectName, e.getMessage());
                warnings.add("附件“" + name + "”解析失败，未纳入生成");
            }
        }
        if (used >= ATTACHMENT_TEXT_CAP) {
            context.append("（附件较长，已截取与本次生成相关的前部内容。）\n");
        }
        return new ContextResult(context.toString(), warnings);
    }

    private ContextResult extractKnowledgeContext(PptGenerationTask task) {
        if (task.getEmployeeId() == null) return ContextResult.empty();
        try {
            var runtime = digitalEmployeeService.buildRuntimeContext(
                    task.getUserId(), task.getEmployeeId());
            List<Long> kbIds = runtime.getKbDocIds();
            if (kbIds == null || kbIds.isEmpty()) return ContextResult.empty();

            List<RetrievedChunk> vector = vectorRetriever.retrieve(
                    task.getPrompt(), null, kbIds, 12);
            List<RetrievedChunk> keyword = bm25Retriever.retrieve(
                    task.getPrompt(), null, kbIds, 12);
            List<RetrievedChunk> chunks = rrfFusion.fuse(vector, keyword, 10);
            parentContextExpander.expand(chunks, 1);

            StringBuilder context = new StringBuilder();
            int used = 0;
            for (RetrievedChunk chunk : chunks) {
                if (chunk.getContent() == null || chunk.getContent().isBlank()) continue;
                int remaining = KNOWLEDGE_TEXT_CAP - used;
                if (remaining <= 0) break;
                String content = chunk.getContent().trim();
                if (content.length() > remaining) content = content.substring(0, remaining);
                String source = empty(chunk.getSourceName(),
                        chunk.getChapter() == null ? "企业知识库" : chunk.getChapter());
                context.append("【来源：").append(source).append("】\n")
                        .append(content).append("\n\n");
                used += content.length();
            }
            return new ContextResult(context.toString(), List.of());
        } catch (Exception e) {
            log.warn("[PPT Task] knowledge retrieval failed taskId={}, reason={}",
                    task.getId(), e.getMessage());
            return new ContextResult("", List.of("知识库取材暂时不可用，本次已使用用户描述和附件继续生成"));
        }
    }

    private ContextResult extractPreviousVersionContext(PptGenerationTask task) {
        if (task.getParentTaskId() == null) return ContextResult.empty();
        PptGenerationTask parent = mapper.selectById(task.getParentTaskId());
        if (parent == null || parent.getObjectName() == null || parent.getObjectName().isBlank()) {
            return new ContextResult("", List.of("未能读取上一版本，已按当前修改要求重新生成"));
        }
        try (InputStream input = fileStorage.getFileStream(parent.getObjectName())) {
            String text = documentExtractor.extract(input, "pptx");
            if (text == null || text.isBlank()) {
                return new ContextResult("", List.of("上一版本内容为空，已按当前修改要求重新生成"));
            }
            String normalized = text.trim();
            if (normalized.length() > PREVIOUS_VERSION_TEXT_CAP) {
                normalized = normalized.substring(0, PREVIOUS_VERSION_TEXT_CAP);
            }
            return new ContextResult(normalized, List.of());
        } catch (Exception e) {
            log.warn("[PPT Task] previous version parse failed taskId={}, parentTaskId={}, reason={}",
                    task.getId(), task.getParentTaskId(), e.getMessage());
            return new ContextResult("", List.of("上一版本读取失败，已按当前修改要求重新生成"));
        }
    }

    private record ContextResult(String context, List<String> warnings) {
        private static ContextResult empty() {
            return new ContextResult("", List.of());
        }
    }

    private static String extensionOf(String objectName) {
        int dot = objectName.lastIndexOf('.');
        return dot >= 0 && dot < objectName.length() - 1
                ? objectName.substring(dot + 1).toLowerCase() : "";
    }

    private static boolean requiresRichVisuals(String prompt) {
        if (prompt == null) return false;
        return prompt.matches(".*(背景图|图片背景|大海|海洋|天空|城市背景|照片|插画|视觉丰富).*");
    }

    private ExportBranding taskBranding(PptGenerationTask task) {
        DigitalEmployee employee = task.getEmployeeId() == null
                ? null : digitalEmployeeMapper.selectById(task.getEmployeeId());
        JSONObject config = new JSONObject();
        if (employee != null && employee.getScenarioConfig() != null
                && !employee.getScenarioConfig().isBlank()) {
            try {
                config = JSON.parseObject(employee.getScenarioConfig());
            } catch (Exception e) {
                log.warn("[PPT Task] ignored invalid employee scenario config employeeId={}",
                        task.getEmployeeId());
            }
        }
        boolean oceanStyle = task.getPrompt() != null
                && task.getPrompt().matches(".*(大海|海洋|海面|蓝色海浪|海浪).*");
        return new ExportBranding(
                empty(config.getString("exportCompanyName"), "ZYCOO Nexus"),
                empty(config.getString("exportDocIdPrefix"), "PPT"), null,
                empty(config.getString("exportFooterNote"), "内部资料 · 请审核后使用"),
                employee == null ? "演示文稿工作台" : empty(employee.getName(), "演示文稿工作台"),
                oceanStyle ? "海洋商务" : task.getVisualStyle(),
                oceanStyle ? "#087EA4" : empty(config.getString("pptPrimaryColor"), "#1F2937"),
                oceanStyle ? "#38BDF8" : empty(config.getString("pptAccentColor"), "#315EFB"));
    }

    private static String safeFileName(String value) {
        String safe = value == null ? "演示文稿" : value.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (safe.isBlank()) safe = "演示文稿";
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    private static String empty(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? error.getMessage() : current.getMessage();
    }
}
