package com.simon.MindCrew.digitalemployee.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.digitalemployee.dto.PptGenerationTaskRequest;
import com.simon.MindCrew.digitalemployee.dto.PptGenerationTaskVO;
import com.simon.MindCrew.digitalemployee.entity.DigitalEmployee;
import com.simon.MindCrew.digitalemployee.entity.PptGenerationTask;
import com.simon.MindCrew.digitalemployee.mapper.DigitalEmployeeMapper;
import com.simon.MindCrew.digitalemployee.mapper.PptGenerationTaskMapper;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PptGenerationTaskService {

    private static final List<String> ACTIVE_STATUSES = List.of("queued", "generating");
    private static final Pattern ARABIC_PAGE_COUNT =
            Pattern.compile("(?<!\\d)([4-9]|[1-3]\\d|40)\\s*(?:页|頁)");
    private final PptGenerationTaskMapper mapper;
    private final QaConversationMapper conversationMapper;
    private final DigitalEmployeeAclService aclService;
    private final DigitalEmployeeMapper digitalEmployeeMapper;
    private final QaMessageMapper messageMapper;

    public PptGenerationTaskVO create(Long userId, PptGenerationTaskRequest request) {
        validateConversationScope(userId, request.employeeId(), request.conversationId());
        Long activeCount = mapper.selectCount(new LambdaQueryWrapper<PptGenerationTask>()
                .eq(PptGenerationTask::getUserId, userId)
                .in(PptGenerationTask::getStatus, ACTIVE_STATUSES));
        if (activeCount >= 5) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "已有较多任务在生成，请等待部分任务完成后再提交");
        }

        PptGenerationTask task = new PptGenerationTask();
        task.setUserId(userId);
        task.setEmployeeId(request.employeeId());
        task.setConversationId(request.conversationId());
        PptGenerationTask baseTask = resolveBaseTask(userId, request);
        task.setParentTaskId(baseTask == null ? null : baseTask.getId());
        task.setVersionNo(baseTask == null ? 1 : Math.max(1,
                baseTask.getVersionNo() == null ? 1 : baseTask.getVersionNo()) + 1);
        task.setOperationType(baseTask == null ? "create" : "revise");
        task.setPrompt(request.prompt().trim());
        task.setAttachments(request.attachments() == null || request.attachments().isEmpty()
                ? null : JSON.toJSONString(request.attachments()));
        task.setTitle(baseTask == null
                ? normalizeTitle(request.title(), request.prompt())
                : baseTask.getTitle());
        task.setPageCount(inferPageCount(request.prompt(),
                request.pageCount() == null ? 12 : request.pageCount()));
        task.setLanguage(defaultValue(request.language(), "zh-CN"));
        task.setVisualStyle(defaultValue(request.visualStyle(), "business"));
        task.setAudience(trimToNull(request.audience()));
        task.setPurpose(trimToNull(request.purpose()));
        task.setStatus("queued");
        task.setProgress(5);
        task.setStage("等待生成");
        task.setFallbackUsed(0);
        mapper.insert(task);
        persistConversationMessages(task, request);
        touchConversation(request.conversationId(), task.getTitle());
        return PptGenerationTaskVO.from(task);
    }

    public List<PptGenerationTaskVO> listMine(Long userId, Integer limit,
                                               Long employeeId, Long conversationId) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 30 : limit, 100));
        LambdaQueryWrapper<PptGenerationTask> query = new LambdaQueryWrapper<PptGenerationTask>()
                .eq(PptGenerationTask::getUserId, userId);
        if (employeeId != null) {
            query.eq(PptGenerationTask::getEmployeeId, employeeId);
        }
        if (conversationId != null) {
            query.eq(PptGenerationTask::getConversationId, conversationId);
        }
        return mapper.selectList(query
                        .orderByDesc(PptGenerationTask::getCreateTime)
                        .last("LIMIT " + safeLimit))
                .stream().map(PptGenerationTaskVO::from).toList();
    }

    public PptGenerationTask requireOwned(Long userId, Long taskId) {
        PptGenerationTask task = mapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "生成任务不存在");
        }
        if (!userId.equals(task.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return task;
    }

    public PptGenerationTaskVO detail(Long userId, Long taskId) {
        return PptGenerationTaskVO.from(requireOwned(userId, taskId));
    }

    public PptGenerationTaskVO retry(Long userId, Long taskId) {
        PptGenerationTask task = requireOwned(userId, taskId);
        boolean emergencyFallback = "completed".equals(task.getStatus())
                && Integer.valueOf(1).equals(task.getFallbackUsed());
        if (!"failed".equals(task.getStatus()) && !emergencyFallback) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "仅失败任务或基础应急版可以重试");
        }
        task.setStatus("queued");
        task.setProgress(5);
        task.setStage("等待重试");
        task.setErrorMessage(null);
        task.setStartedAt(null);
        task.setCompletedAt(null);
        mapper.updateById(task);
        updateAssistantMessage(task, emergencyFallback
                ? "正在重新调用阿里 PPT 服务生成商用版本。"
                : "PPT 正在重新生成。");
        return PptGenerationTaskVO.from(task);
    }

    public PptGenerationTaskVO cancel(Long userId, Long taskId) {
        PptGenerationTask task = requireOwned(userId, taskId);
        if (!ACTIVE_STATUSES.contains(task.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "当前任务不能取消");
        }
        task.setStatus("canceled");
        task.setProgress(0);
        task.setStage("已取消");
        task.setCompletedAt(LocalDateTime.now());
        mapper.updateById(task);
        updateAssistantMessage(task, "PPT 生成已取消。");
        return PptGenerationTaskVO.from(task);
    }

    public void markDispatchFailed(Long taskId, String message) {
        PptGenerationTask task = mapper.selectById(taskId);
        if (task == null) return;
        task.setStatus("failed");
        task.setProgress(0);
        task.setStage("任务排队失败");
        task.setErrorMessage(safeError(message));
        task.setCompletedAt(LocalDateTime.now());
        mapper.updateById(task);
        updateAssistantMessage(task, "PPT 任务排队失败：" + safeError(message));
    }

    public boolean isCanceled(Long taskId) {
        PptGenerationTask current = mapper.selectById(taskId);
        return current != null && "canceled".equals(current.getStatus());
    }

    public void updateAssistantMessage(PptGenerationTask task, String content) {
        if (task.getAssistantMessageId() == null) return;
        QaMessage message = messageMapper.selectById(task.getAssistantMessageId());
        if (message == null) return;
        message.setContent(content);
        message.setSources(taskSources(task));
        messageMapper.updateById(message);
    }

    private PptGenerationTask resolveBaseTask(Long userId, PptGenerationTaskRequest request) {
        if (request.conversationId() == null) return null;
        if (request.baseTaskId() != null) {
            PptGenerationTask explicit = requireOwned(userId, request.baseTaskId());
            if (!request.conversationId().equals(explicit.getConversationId())
                    || !"completed".equals(explicit.getStatus())) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                        "只能修改当前对话中已经完成的 PPT");
            }
            return explicit;
        }
        if (!looksLikeRevision(request.prompt())) return null;
        return mapper.selectOne(new LambdaQueryWrapper<PptGenerationTask>()
                .eq(PptGenerationTask::getUserId, userId)
                .eq(PptGenerationTask::getConversationId, request.conversationId())
                .eq(PptGenerationTask::getStatus, "completed")
                .orderByDesc(PptGenerationTask::getCreateTime)
                .last("LIMIT 1"));
    }

    static boolean looksLikeRevision(String prompt) {
        if (prompt == null) return false;
        String value = prompt.trim();
        if (value.matches(".*(新建|新做|另外|另一份|再生成一份|重新做一份).*")) return false;
        return value.matches(".*(修改|改成|调整|替换|删掉|删除|增加|新增|补充|更新|优化|换成|换个|第.{0,6}页|上一份|刚才|这份PPT|这个PPT).*");
    }

    public static int inferPageCount(String prompt, int fallback) {
        if (prompt != null) {
            Matcher matcher = ARABIC_PAGE_COUNT.matcher(prompt);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
            String normalized = prompt.replaceAll("\\s+", "");
            String[] chinese = {
                    "四", "五", "六", "七", "八", "九", "十",
                    "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十"
            };
            for (int i = chinese.length - 1; i >= 0; i--) {
                if (normalized.contains(chinese[i] + "页")
                        || normalized.contains(chinese[i] + "頁")) {
                    return i + 4;
                }
            }
        }
        return Math.max(4, Math.min(fallback, 40));
    }

    private void persistConversationMessages(PptGenerationTask task,
                                             PptGenerationTaskRequest request) {
        if (task.getConversationId() == null) return;
        QaMessage user = new QaMessage();
        user.setConversationId(task.getConversationId());
        user.setRole("user");
        user.setContent(task.getPrompt());
        user.setSources(taskSources(task));
        user.setFeedback(0);
        messageMapper.insert(user);

        QaMessage assistant = new QaMessage();
        assistant.setConversationId(task.getConversationId());
        assistant.setRole("assistant");
        assistant.setContent("revise".equals(task.getOperationType())
                ? "已根据你的要求开始修改 PPT。" : "已开始生成 PPT。");
        assistant.setSources(taskSources(task));
        assistant.setFeedback(0);
        messageMapper.insert(assistant);

        task.setUserMessageId(user.getId());
        task.setAssistantMessageId(assistant.getId());
        mapper.updateById(task);
    }

    private static String taskSources(PptGenerationTask task) {
        JSONObject source = new JSONObject();
        source.put("type", "ppt_task");
        source.put("taskId", task.getId());
        source.put("status", task.getStatus());
        source.put("versionNo", task.getVersionNo());
        source.put("operationType", task.getOperationType());
        if (task.getWarnings() != null) source.put("warnings", task.getWarnings());
        JSONArray sources = new JSONArray();
        sources.add(source);
        return sources.toJSONString();
    }

    private void validateConversationScope(Long userId, Long employeeId, Long conversationId) {
        if (employeeId == null && conversationId == null) {
            return;
        }
        if (employeeId == null || conversationId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "数字员工与对话参数必须同时提供");
        }
        if (!aclService.canUse(userId, employeeId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        DigitalEmployee employee = digitalEmployeeMapper.selectById(employeeId);
        if (employee == null || !"ppt_authoring".equals(employee.getPrimaryScenario())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "当前数字员工不是 PPT 类型");
        }
        QaConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())
                || !employeeId.equals(conversation.getDigitalEmployeeId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    private void touchConversation(Long conversationId, String title) {
        if (conversationId == null) return;
        QaConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) return;
        if (conversation.getTitle() == null || conversation.getTitle().isBlank()
                || "新对话".equals(conversation.getTitle())) {
            conversation.setTitle(title);
        }
        conversation.setMessageCount((conversation.getMessageCount() == null
                ? 0 : conversation.getMessageCount()) + 2);
        conversation.setLastActive(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }

    static String safeError(String message) {
        String value = message == null || message.isBlank() ? "生成失败，请稍后重试" : message.trim();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String normalizeTitle(String title, String prompt) {
        String value = trimToNull(title);
        if (value == null) {
            String firstLine = prompt == null ? "" : prompt.strip().split("\\R", 2)[0].trim();
            value = firstLine.isBlank() ? "企业演示文稿" : firstLine;
        }
        value = value.replaceAll("[#*_`]+", "").trim();
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
