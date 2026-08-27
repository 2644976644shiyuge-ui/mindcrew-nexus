package com.simon.MindCrew.digitalemployee.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.digitalemployee.dto.DigitalEmployeeCardVO;
import com.simon.MindCrew.digitalemployee.dto.DigitalEmployeeDetailVO;
import com.simon.MindCrew.digitalemployee.dto.DigitalEmployeeSaveRequest;
import com.simon.MindCrew.digitalemployee.entity.DigitalEmployee;
import com.simon.MindCrew.digitalemployee.entity.DigitalEmployeeAcl;
import com.simon.MindCrew.digitalemployee.entity.DigitalEmployeeKnowledge;
import com.simon.MindCrew.digitalemployee.mapper.DigitalEmployeeAclMapper;
import com.simon.MindCrew.digitalemployee.mapper.DigitalEmployeeKnowledgeMapper;
import com.simon.MindCrew.digitalemployee.mapper.DigitalEmployeeMapper;
import com.simon.MindCrew.digitalemployee.scenario.ScenarioPromptComposer;
import com.simon.MindCrew.digitalemployee.scenario.ScenarioTemplateRegistry;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.service.KnowledgeCollectionService;
import com.simon.MindCrew.service.KbAclService;
import com.simon.MindCrew.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DigitalEmployeeService {

    private final DigitalEmployeeMapper employeeMapper;
    private final DigitalEmployeeKnowledgeMapper knowledgeMapper;
    private final DigitalEmployeeAclMapper aclMapper;
    private final DigitalEmployeeAclService aclService;
    private final ScenarioTemplateRegistry scenarioRegistry;
    private final ScenarioPromptComposer promptComposer;
    private final DigitalEmployeeUsageService usageService;
    private final UserService userService;
    private final KnowledgeCollectionService collectionService;
    private final KbAclService kbAclService;
    private final QaConversationMapper qaConversationMapper;
    private final ChatClient.Builder chatClientBuilder;

    public List<DigitalEmployee> listAllAdmin(String keyword) {
        LambdaQueryWrapper<DigitalEmployee> q = new LambdaQueryWrapper<DigitalEmployee>()
                .orderByAsc(DigitalEmployee::getSortOrder)
                .orderByDesc(DigitalEmployee::getId);
        if (StringUtils.hasText(keyword)) {
            q.like(DigitalEmployee::getName, keyword.trim());
        }
        return employeeMapper.selectList(q);
    }

    public List<DigitalEmployeeCardVO> listMine(String keyword) {
        Long userId = userService.getCurrentUserId();
        List<Long> visibleIds = aclService.listVisibleEmployeeIds(userId);
        if (visibleIds.isEmpty()) return List.of();

        LambdaQueryWrapper<DigitalEmployee> q = new LambdaQueryWrapper<DigitalEmployee>()
                .in(DigitalEmployee::getId, visibleIds)
                .orderByAsc(DigitalEmployee::getSortOrder);
        if (StringUtils.hasText(keyword)) {
            q.like(DigitalEmployee::getName, keyword.trim());
        }
        List<DigitalEmployee> list = employeeMapper.selectList(q);
        Map<String, String> labels = scenarioRegistry.idToLabel();

        return list.stream().map(e -> toCard(e, labels, userId)).toList();
    }

    private DigitalEmployeeCardVO toCard(DigitalEmployee e, Map<String, String> labels, Long userId) {
        DigitalEmployeeCardVO vo = new DigitalEmployeeCardVO();
        vo.setId(e.getId());
        vo.setName(e.getName());
        vo.setAvatar(e.getAvatar());
        vo.setSummary(e.getSummary());
        vo.setStatus(e.getStatus());
        vo.setPrimaryScenario(e.getPrimaryScenario());
        vo.setPrimaryScenarioLabel(labels.getOrDefault(e.getPrimaryScenario(), "常规问答"));
        vo.setRuntimeLabel("published".equals(e.getStatus()) ? "运行中" : "已停用");

        long sessions = qaConversationMapper.selectCount(new LambdaQueryWrapper<QaConversation>()
                .eq(QaConversation::getDigitalEmployeeId, e.getId())
                .eq(QaConversation::getUserId, userId));
        vo.setSessionCount(sessions);

        QaConversation last = qaConversationMapper.selectOne(new LambdaQueryWrapper<QaConversation>()
                .eq(QaConversation::getDigitalEmployeeId, e.getId())
                .orderByDesc(QaConversation::getLastActive)
                .last("LIMIT 1"));
        if (last != null && last.getLastActive() != null) {
            vo.setActiveDisplay(formatDuration(Duration.between(last.getLastActive(), LocalDateTime.now())));
        } else {
            vo.setActiveDisplay("—");
        }
        long tokens30 = usageService.sumTokensLast30Days(e.getId());
        vo.setTokenDisplay(tokens30 > 0 ? DigitalEmployeeUsageService.formatTokenDisplay(tokens30) : "—");
        return vo;
    }

    private static String formatDuration(Duration d) {
        if (d.isNegative()) d = d.negated();
        long minutes = d.toMinutes();
        if (minutes < 60) return minutes + "m";
        long hours = d.toHours();
        if (hours < 48) return hours + "h";
        return (hours / 24) + "d " + (hours % 24) + "h";
    }

    public DigitalEmployeeDetailVO getDetail(Long id, boolean adminView) {
        DigitalEmployee e = employeeMapper.selectById(id);
        if (e == null) throw new BusinessException(ResultCode.NOT_FOUND);
        if (!adminView) {
            Long userId = userService.getCurrentUserId();
            if (!aclService.canUse(userId, id)) {
                throw new BusinessException(ResultCode.FORBIDDEN);
            }
        }
        return toDetailVo(e);
    }

    private DigitalEmployeeDetailVO toDetailVo(DigitalEmployee e) {
        DigitalEmployeeDetailVO vo = new DigitalEmployeeDetailVO();
        vo.setId(e.getId());
        vo.setName(e.getName());
        vo.setAvatar(e.getAvatar());
        vo.setSummary(e.getSummary());
        vo.setSystemPrompt(e.getSystemPrompt());
        vo.setModelProvider(e.getModelProvider());
        vo.setModelName(e.getModelName());
        parseFeatureFlags(e.getFeatureFlags(), vo);
        vo.setScenarioConfig(e.getScenarioConfig());
        vo.setPrimaryScenario(e.getPrimaryScenario());
        vo.setStatus(e.getStatus());
        vo.setVisibility(e.getVisibility());
        vo.setKbOnlyReply(e.getKbOnlyReply() != null && e.getKbOnlyReply() == 1);
        vo.setSortOrder(e.getSortOrder());

        List<Long> cols = knowledgeMapper.selectList(new LambdaQueryWrapper<DigitalEmployeeKnowledge>()
                        .eq(DigitalEmployeeKnowledge::getEmployeeId, e.getId()))
                .stream().map(DigitalEmployeeKnowledge::getCollectionId).toList();
        vo.setCollectionIds(cols);

        List<DigitalEmployeeSaveRequest.AclEntry> aclEntries = aclMapper.selectList(
                        new LambdaQueryWrapper<DigitalEmployeeAcl>().eq(DigitalEmployeeAcl::getEmployeeId, e.getId()))
                .stream().map(a -> {
                    DigitalEmployeeSaveRequest.AclEntry en = new DigitalEmployeeSaveRequest.AclEntry();
                    en.setPrincipalType(a.getPrincipalType());
                    en.setPrincipalId(a.getPrincipalId());
                    en.setPermission(a.getPermission());
                    return en;
                }).toList();
        vo.setAclEntries(aclEntries);
        return vo;
    }

    private void parseFeatureFlags(String json, DigitalEmployeeDetailVO vo) {
        vo.setWebSearch(false);
        vo.setMemoryEnabled(true);
        if (json == null || json.isBlank()) return;
        try {
            JSONObject o = JSON.parseObject(json);
            if (o.containsKey("webSearch")) vo.setWebSearch(o.getBoolean("webSearch"));
            if (o.containsKey("memoryEnabled")) vo.setMemoryEnabled(o.getBoolean("memoryEnabled"));
        } catch (Exception ignored) { }
    }

    @Transactional
    public DigitalEmployee createDraft(DigitalEmployeeSaveRequest req) {
        DigitalEmployee e = new DigitalEmployee();
        applySave(e, req);
        ensureDefaults(e);
        e.setStatus("draft");
        e.setCreatedBy(userService.getCurrentUserId());
        validateScenarioConfig(e.getPrimaryScenario(), e.getScenarioConfig());
        employeeMapper.insert(e);
        saveRelations(e.getId(), req);
        return e;
    }

    @Transactional
    public void update(Long id, DigitalEmployeeSaveRequest req) {
        DigitalEmployee e = employeeMapper.selectById(id);
        if (e == null) throw new BusinessException(ResultCode.NOT_FOUND);
        applySave(e, req);
        ensureDefaults(e);
        validateScenarioConfig(e.getPrimaryScenario(), e.getScenarioConfig());
        employeeMapper.updateById(e);
        knowledgeMapper.delete(new LambdaQueryWrapper<DigitalEmployeeKnowledge>()
                .eq(DigitalEmployeeKnowledge::getEmployeeId, id));
        aclMapper.delete(new LambdaQueryWrapper<DigitalEmployeeAcl>()
                .eq(DigitalEmployeeAcl::getEmployeeId, id));
        saveRelations(id, req);
    }

    private void applySave(DigitalEmployee e, DigitalEmployeeSaveRequest req) {
        if (req.getName() != null) e.setName(req.getName());
        if (req.getAvatar() != null) e.setAvatar(req.getAvatar());
        if (req.getSummary() != null) e.setSummary(req.getSummary());
        if (req.getSystemPrompt() != null) e.setSystemPrompt(req.getSystemPrompt());
        if (req.getModelProvider() != null) e.setModelProvider(req.getModelProvider());
        if (req.getModelName() != null) e.setModelName(req.getModelName());
        if (req.getScenarioConfig() != null) e.setScenarioConfig(req.getScenarioConfig());
        if (req.getPrimaryScenario() != null) e.setPrimaryScenario(req.getPrimaryScenario());
        if (req.getVisibility() != null) e.setVisibility(req.getVisibility());
        if (req.getSortOrder() != null) e.setSortOrder(req.getSortOrder());
        if (req.getKbOnlyReply() != null) e.setKbOnlyReply(req.getKbOnlyReply() ? 1 : 0);

        JSONObject flags = new JSONObject();
        flags.put("webSearch", Boolean.TRUE.equals(req.getWebSearch()));
        flags.put("memoryEnabled", req.getMemoryEnabled() == null || req.getMemoryEnabled());
        e.setFeatureFlags(flags.toJSONString());
    }

    private void saveRelations(Long employeeId, DigitalEmployeeSaveRequest req) {
        if (req.getCollectionIds() != null) {
            for (Long colId : req.getCollectionIds()) {
                if (colId == null) continue;
                DigitalEmployeeKnowledge k = new DigitalEmployeeKnowledge();
                k.setEmployeeId(employeeId);
                k.setCollectionId(colId);
                knowledgeMapper.insert(k);
            }
        }
        if (req.getAclEntries() != null) {
            for (DigitalEmployeeSaveRequest.AclEntry en : req.getAclEntries()) {
                if (en.getPrincipalId() == null || en.getPrincipalType() == null) continue;
                DigitalEmployeeAcl a = new DigitalEmployeeAcl();
                a.setEmployeeId(employeeId);
                a.setPrincipalType(en.getPrincipalType());
                a.setPrincipalId(en.getPrincipalId());
                a.setPermission(en.getPermission() != null ? en.getPermission() : "use");
                aclMapper.insert(a);
            }
        }
    }

    public void publish(Long id) {
        DigitalEmployee e = employeeMapper.selectById(id);
        if (e == null) throw new BusinessException(ResultCode.NOT_FOUND);
        ensureDefaults(e);
        String scenarioId = StringUtils.hasText(e.getPrimaryScenario()) ? e.getPrimaryScenario() : "general_qa";
        validateScenarioConfig(scenarioId, e.getScenarioConfig());
        if (e.getKbOnlyReply() != null && e.getKbOnlyReply() == 1) {
            long kbCount = knowledgeMapper.selectCount(new LambdaQueryWrapper<DigitalEmployeeKnowledge>()
                    .eq(DigitalEmployeeKnowledge::getEmployeeId, id));
            if (kbCount == 0) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                        "开启「仅从知识库回复」须至少绑定一个知识库");
            }
        }
        e.setStatus(DigitalEmployeeAclService.STATUS_PUBLISHED);
        employeeMapper.updateById(e);
    }

    private void ensureDefaults(DigitalEmployee e) {
        String scenarioId = StringUtils.hasText(e.getPrimaryScenario())
                ? e.getPrimaryScenario().trim()
                : "general_qa";
        e.setPrimaryScenario(scenarioId);
        String scenarioName = scenarioRegistry.idToLabel().getOrDefault(scenarioId, "智能助手");
        if (!StringUtils.hasText(e.getName())) {
            e.setName(scenarioName.endsWith("助手") ? scenarioName : scenarioName + "助手");
        } else {
            e.setName(e.getName().trim());
        }
        if (!StringUtils.hasText(e.getSummary())) {
            e.setSummary("用于" + scenarioName + "，可直接通过对话完成相关工作。");
        }
        if (!StringUtils.hasText(e.getAvatar())) e.setAvatar("🤖");
        if (!StringUtils.hasText(e.getVisibility())) e.setVisibility(DigitalEmployeeAclService.VIS_PUBLIC);
        if (!StringUtils.hasText(e.getModelProvider())) e.setModelProvider("default");
        if (!StringUtils.hasText(e.getModelName())) e.setModelName("系统默认模型");
        if (e.getKbOnlyReply() == null) e.setKbOnlyReply(0);
    }

    /**
     * 校验 scenario_config JSON 结构与已知字段类型（非法则拒绝保存/发布）。
     */
    public void validateScenarioConfig(String primaryScenario, String scenarioConfigJson) {
        if (!StringUtils.hasText(scenarioConfigJson)) {
            return;
        }
        JSONObject cfg;
        try {
            cfg = JSON.parseObject(scenarioConfigJson);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "场景配置不是合法 JSON");
        }
        if (cfg == null) {
            return;
        }
        String scenarioId = StringUtils.hasText(primaryScenario) ? primaryScenario : "general_qa";
        for (var field : scenarioRegistry.configFieldsFor(scenarioId)) {
            if (!cfg.containsKey(field.key())) {
                continue;
            }
            Object val = cfg.get(field.key());
            if (val == null) {
                continue;
            }
            if ("number".equals(field.type())) {
                try {
                    int n = val instanceof Number ? ((Number) val).intValue() : Integer.parseInt(String.valueOf(val));
                    if ("slideCount".equals(field.key()) && (n < 1 || n > 80)) {
                        throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "PPT 目标页数须在 1～80 之间");
                    }
                } catch (NumberFormatException ex) {
                    throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                            field.label() + "须为数字");
                }
            }
        }
    }

    public void unpublish(Long id) {
        DigitalEmployee e = employeeMapper.selectById(id);
        if (e == null) throw new BusinessException(ResultCode.NOT_FOUND);
        e.setStatus("offline");
        employeeMapper.updateById(e);
    }

    public void delete(Long id) {
        employeeMapper.deleteById(id);
    }

    /**
     * 构建数字员工对话用的技能指令（system + 场景）
     */
    public String buildSkillInstruction(Long employeeId) {
        DigitalEmployee e = employeeMapper.selectById(employeeId);
        if (e == null) return null;
        String identity = "【当前数字员工身份】\n名称：" + nullToEmpty(e.getName())
                + "\n职责：" + nullToEmpty(e.getSummary()) + "\n\n";
        String composed = identity + promptComposer.compose(
                e.getPrimaryScenario(), e.getScenarioConfig(), e.getSystemPrompt());
        if (e.getKbOnlyReply() != null && e.getKbOnlyReply() == 1) {
            composed += "\n\n【硬性要求】仅依据知识库与用户提供材料回答；知识库无依据时明确说明无法回答，勿编造。";
        }
        return composed.trim();
    }

    public void recordUsageAfterChat(Long employeeId, Long userId, String userMessage, String assistantMessage) {
        int est = estimateTokens(userMessage) + estimateTokens(assistantMessage);
        usageService.recordChatCompletion(employeeId, userId, est);
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() * 2 / 3);
    }

    public DigitalEmployeeRuntimeContext buildRuntimeContext(Long userId, Long employeeId) {
        if (!aclService.canUse(userId, employeeId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        DigitalEmployee e = employeeMapper.selectById(employeeId);
        if (e == null || !DigitalEmployeeAclService.STATUS_PUBLISHED.equals(e.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "数字员工不可用");
        }

        List<Long> boundCols = knowledgeMapper.selectList(new LambdaQueryWrapper<DigitalEmployeeKnowledge>()
                        .eq(DigitalEmployeeKnowledge::getEmployeeId, employeeId))
                .stream().map(DigitalEmployeeKnowledge::getCollectionId).toList();

        List<Long> accessibleCols = kbAclService.listAccessibleCollectionIds(userId);
        Set<Long> accessibleSet = new HashSet<>(accessibleCols);
        List<Long> effectiveCols = boundCols.stream().filter(accessibleSet::contains).toList();

        List<Long> docIds = new ArrayList<>();
        if (!effectiveCols.isEmpty()) {
            docIds.addAll(collectionService.expandCollectionsToDocIds(effectiveCols));
        }
        List<Long> userDocScope = kbAclService.listAccessibleKbIds(userId);
        if (!docIds.isEmpty()) {
            docIds.retainAll(new HashSet<>(userDocScope));
        }

        Boolean webSearch = false;
        Boolean memory = true;
        if (StringUtils.hasText(e.getFeatureFlags())) {
            try {
                JSONObject o = JSON.parseObject(e.getFeatureFlags());
                if (o.containsKey("webSearch")) webSearch = o.getBoolean("webSearch");
                if (o.containsKey("memoryEnabled")) memory = o.getBoolean("memoryEnabled");
            } catch (Exception ignored) { }
        }

        DigitalEmployeeRuntimeContext ctx = new DigitalEmployeeRuntimeContext();
        ctx.setEmployeeId(employeeId);
        ctx.setEmployeeName(e.getName());
        ctx.setSkillInstruction(buildSkillInstruction(employeeId));
        ctx.setCollectionIds(effectiveCols);
        ctx.setKbDocIds(docIds);
        ctx.setWebSearchEnabled(webSearch);
        ctx.setMemoryEnabled(memory);
        ctx.setKbOnlyReply(e.getKbOnlyReply() != null && e.getKbOnlyReply() == 1);
        return ctx;
    }

    public String optimizePrompt(Long id) {
        DigitalEmployee e = employeeMapper.selectById(id);
        if (e == null) throw new BusinessException(ResultCode.NOT_FOUND);
        Map<String, String> labels = scenarioRegistry.idToLabel();
        String scenarioName = labels.getOrDefault(e.getPrimaryScenario(), "常规问答");

        String userMsg = """
                请为以下企业数字员工优化「智能体设定」(System Prompt)，要求专业、可执行、适合%s场景。
                名称：%s
                简介：%s
                当前设定：
                %s
                只输出优化后的设定正文，不要解释。
                """.formatted(
                scenarioName,
                nullToEmpty(e.getName()),
                nullToEmpty(e.getSummary()),
                nullToEmpty(e.getSystemPrompt()));

        String optimized = chatClientBuilder.build().prompt()
                .user(userMsg)
                .call()
                .content();
        String text = optimized != null ? optimized.trim() : "";
        if (StringUtils.hasText(text)) {
            e.setSystemPrompt(text);
            employeeMapper.updateById(e);
        }
        return text;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @lombok.Data
    public static class DigitalEmployeeRuntimeContext {
        private Long employeeId;
        private String employeeName;
        private String skillInstruction;
        private List<Long> collectionIds = List.of();
        private List<Long> kbDocIds = List.of();
        private Boolean webSearchEnabled;
        private Boolean memoryEnabled;
        private boolean kbOnlyReply;
    }
}
