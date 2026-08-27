package com.simon.MindCrew.digitalemployee.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.digitalemployee.entity.DigitalEmployee;
import com.simon.MindCrew.digitalemployee.mapper.DigitalEmployeeMapper;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DigitalEmployeeConversationExportService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QaConversationMapper conversationMapper;
    private final QaMessageMapper messageMapper;
    private final DigitalEmployeeMapper employeeMapper;
    private final DigitalEmployeeAclService aclService;

    public byte[] exportMarkdown(Long userId, Long employeeId, Long conversationId) {
        if (!aclService.canUse(userId, employeeId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        QaConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !userId.equals(conv.getUserId())
                || !employeeId.equals(conv.getDigitalEmployeeId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "会话不存在或无权导出");
        }

        DigitalEmployee emp = employeeMapper.selectById(employeeId);
        String empName = emp != null ? emp.getName() : "数字员工";

        List<QaMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, conversationId)
                .orderByAsc(QaMessage::getCreateTime));

        String md = buildMarkdown(empName, conv, messages);
        return md.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String buildMarkdown(String empName, QaConversation conv, List<QaMessage> messages) {
        StringBuilder md = new StringBuilder();
        String exportTime = LocalDateTime.now().format(DT_FMT);
        String createTime = conv.getCreateTime() != null ? conv.getCreateTime().format(DT_FMT) : "-";
        String title = conv.getTitle() != null ? conv.getTitle() : empName + " 对话";

        md.append("# ").append(title).append("\n\n");
        md.append("> **数字员工**: ").append(empName).append("  \n");
        md.append("> **创建时间**: ").append(createTime).append("  \n");
        md.append("> **导出时间**: ").append(exportTime).append("  \n");
        md.append("> **消息数量**: ").append(messages.size()).append("  \n\n");
        md.append("---\n\n");

        for (QaMessage msg : messages) {
            String time = msg.getCreateTime() != null ? msg.getCreateTime().format(DT_FMT) : "";
            if ("user".equals(msg.getRole())) {
                md.append("### 用户");
                if (!time.isEmpty()) md.append(" · `").append(time).append("`");
                md.append("\n\n").append(msg.getContent()).append("\n\n");
            } else {
                md.append("### ").append(empName);
                if (!time.isEmpty()) md.append(" · `").append(time).append("`");
                if (msg.getResponseTime() != null) {
                    md.append(" · ").append(msg.getResponseTime()).append("ms");
                }
                md.append("\n\n").append(msg.getContent()).append("\n\n");
                appendSources(md, msg.getSources());
            }
            md.append("---\n\n");
        }
        md.append("*由 ZYCOO Nexus 数字员工导出。*\n");
        return md.toString();
    }

    private void appendSources(StringBuilder md, String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) return;
        try {
            JSONArray sources = JSON.parseArray(sourcesJson);
            if (sources == null || sources.isEmpty()) return;
            md.append("**参考来源**\n\n");
            for (int i = 0; i < sources.size(); i++) {
                var s = sources.getJSONObject(i);
                String name = s.getString("name");
                md.append("- [").append(i + 1).append("] ").append(name != null ? name : "来源").append("\n");
            }
            md.append("\n");
        } catch (Exception ignored) { }
    }
}