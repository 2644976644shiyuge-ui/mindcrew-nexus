package com.simon.MindCrew.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.SysAiConfig;
import com.simon.MindCrew.mapper.SysAiConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 配置初始化器
 *
 * <p>Spring 启动完毕后执行：
 * <ol>
 *   <li>若 sys_ai_config 表为空（首次部署），插入 12 条默认配置</li>
 *   <li>将全部配置加载到 AiConfigHolder 的内存快照</li>
 *   <li>用当前配置重建 LLM 模型实例</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiConfigInitializer implements ApplicationRunner {

    private final SysAiConfigMapper aiConfigMapper;
    private final AiConfigHolder aiConfigHolder;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[AiConfigInitializer] 开始初始化 AI 配置...");

        // ① 补齐缺失的默认配置（首次全插；后续版本新增的 key 也自动补上，不覆盖已有值）
        List<SysAiConfig> existing = aiConfigMapper.selectList(
                new LambdaQueryWrapper<SysAiConfig>().eq(SysAiConfig::getDeleted, 0));
        java.util.Set<String> existingKeys = existing.stream()
                .map(SysAiConfig::getConfigKey).collect(Collectors.toSet());
        int inserted = 0;
        for (SysAiConfig cfg : defaultConfigs()) {
            if (!existingKeys.contains(cfg.getConfigKey())) {
                aiConfigMapper.insert(cfg);
                inserted++;
            }
        }
        if (inserted > 0) log.info("[AiConfigInitializer] 补齐 {} 条新默认配置", inserted);

        // ② 从 DB 加载所有配置到内存
        List<SysAiConfig> all = aiConfigMapper.selectList(
                new LambdaQueryWrapper<SysAiConfig>().eq(SysAiConfig::getDeleted, 0));
        Map<String, String> map = all.stream()
                .collect(Collectors.toMap(SysAiConfig::getConfigKey, SysAiConfig::getConfigValue));
        aiConfigHolder.updateBatch(map);
        log.info("[AiConfigInitializer] 已加载 {} 条配置到内存", map.size());

        // ③ 构建初始 LLM 模型实例
        aiConfigHolder.refreshLlmModel();
        // ④ 加载模型端点配置（ocr/vision/video/asr/tts/reranker/voice_chat）
        aiConfigHolder.refreshModelEndpoints();
        log.info("[AiConfigInitializer] AI 配置初始化完成");
    }

    // ======================== 默认配置 ========================

    private void insertDefaults() {
        for (SysAiConfig cfg : defaultConfigs()) {
            aiConfigMapper.insert(cfg);
        }
    }

    private List<SysAiConfig> defaultConfigs() {
        return Arrays.asList(
                build("rag", "rag.vector_top_k",         "20",       "integer", "向量召回 TopK",    "向量检索返回的最大候选数量",     "5",  "100"),
                build("rag", "rag.bm25_top_k",           "20",       "integer", "BM25 召回 TopK",   "关键词检索返回的最大候选数量",   "5",  "100"),
                build("rag", "rag.rrf_top_n",            "30",       "integer", "RRF 融合 TopN",    "RRF 融合后保留的最大数量",       "5",  "200"),
                build("rag", "rag.rerank_top_k",         "8",        "integer", "重排序 TopK",      "Cross-Encoder 重排序后的最终数量", "1", "20"),
                build("rag", "rag.rrf_k_constant",       "60",       "integer", "RRF 常数 K",       "RRF 公式经验常数，值越大头部优势越小", "1", "200"),
                build("rag", "rag.neighbor_window",      "2",        "integer", "邻居扩展窗口",     "命中片前后各取 N 片拼为连贯上下文喂 LLM（仅影响上下文，不影响检索排序）", "0", "5"),
                build("rag", "rag.parent_child_enabled", "1",        "integer", "父子切片增强",     "1=新文档生成父子切片并在命中后回查父段；0=全部使用原相邻切片扩展", "0", "1"),
                build("rag", "rag.parent_chunk_target_chars", "1800", "integer", "父切片目标长度", "父切片期望字符数，仅影响新入库或重新处理的文档", "800", "4000"),
                build("rag", "rag.parent_chunk_max_chars", "2600",   "integer", "父切片最大长度",   "父切片最大字符数，防止上下文过长", "1200", "6000"),
                build("rag", "rag.context_min_score",    "0.20",     "float",   "上下文相关分下限", "低于此相关分的命中片不进上下文（挡低分噪声，保信噪比）", "0.0", "1.0"),
                build("rag", "rag.context_rel_ratio",    "0.45",     "float",   "上下文相对分比例", "命中片相关分需 ≥ 最高分×此比例才进上下文（相对噪声过滤）", "0.0", "1.0"),
                build("llm", "llm.model",                "qwen-plus","string",  "对话模型",         "qwen-turbo / qwen-plus / qwen-max", null, null),
                build("llm", "llm.streaming_temperature","0.3",      "float",   "流式对话温度",     "回答生成的随机性（0=确定性，1=随机）", "0.0", "2.0"),
                build("llm", "llm.chat_temperature",     "0.1",      "float",   "改写/检查温度",    "Query 改写和安全检查的温度参数",  "0.0", "2.0"),
                build("llm", "llm.timeout_seconds",      "60",       "integer", "请求超时(秒)",     "LLM 接口超时时间，流式加倍",      "10",  "300"),
                build("ppt", "ppt_generation.enabled", "true", "string", "启用 PPT Agent",
                        "开启后使用配置的 AI PPT API；关闭时使用内置安全渲染器", null, null),
                build("ppt", "ppt_generation.service-provider", "qwen-doc", "string", "AI PPT 服务商",
                        "推荐阿里云 Qwen-Doc-Turbo；也支持 Gamma 和自定义 PPT Agent", null, null),
                build("ppt", "ppt_generation.api-url",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1", "string", "API 地址",
                        "Qwen/Gamma 可留空使用默认地址；自定义直出需填写完整生成接口", null, null),
                build("ppt", "ppt_generation.api-key", "", "string", "服务商 API Key",
                        "仅保存在服务端，用于调用 AI PPT 服务商", null, null),
                build("ppt", "ppt_generation.theme-id", "", "string", "服务商主题 ID",
                        "Gamma Theme ID；留空时由服务商自动选择主题", null, null),
                build("ppt", "ppt_generation.qwen-mode", "general", "string", "千问 PPT 模式",
                        "general 为原生可编辑企业模板；creative 为视觉更丰富的图片型 PPT", null, null),
                build("ppt", "ppt_generation.qwen-template-id", "internet_01", "string", "千问 PPT 模板",
                        "模板模式支持 internet_01、summary_01、thesis_01、news_01", null, null),
                build("ppt", "ppt_generation.planner-provider", "dashscope", "string", "策划模型 Provider",
                        "默认使用阿里云百炼 DashScope，也支持任意 OpenAI 兼容服务", null, null),
                build("ppt", "ppt_generation.model", "qwen-plus", "string", "PPT 策划模型",
                        "推荐 qwen-plus；重要汇报可选 qwen3.7-plus 或 qwen3.7-max", null, null),
                build("ppt", "ppt_generation.model-base-url",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1", "string", "模型 Base URL",
                        "千问 OpenAI 兼容地址；私有化部署可填写 vLLM/Ollama 兼容地址", null, null),
                build("ppt", "ppt_generation.model-api-key", "", "string", "模型 API Key",
                        "PPT Agent 调用策划模型使用的 API Key；留空时由 Agent 自身环境变量提供", null, null),
                build("ppt", "ppt_generation.timeout-seconds", "600", "integer", "生成超时（秒）",
                        "完整 PPT 生成允许的最长时间", "30", "900"),
                build("ppt", "ppt_generation.poll-interval-ms", "2000", "integer", "任务轮询间隔（毫秒）",
                        "异步服务商任务状态查询间隔，建议 1500～5000 毫秒", "500", "10000"),
                build("ppt", "ppt_generation.fallback-on-error", "true", "string", "失败自动回退",
                        "PPT Agent 失败时自动使用内置渲染器，生产环境建议开启", null, null),
                build("cache", "cache.freq_threshold",   "3",        "integer", "缓存频次阈值",     "同一问题被问几次后触发缓存写入",  "1",   "100"),
                build("cache", "cache.ttl_hours",        "2",        "integer", "缓存 TTL(小时)",   "缓存答案的保留时长",              "1",   "168"),
                build("safety", "safety.confidence_threshold", "0.3","float",  "置信度阈值",       "低于此值触发知识库未匹配兜底",    "0.0", "1.0"),
                build("rag", "rag.min_rerank_score",      "0.15",    "float",   "最低重排分数",     "低于此值的切片在压缩阶段直接丢弃",  "0.0", "1.0"),
                build("rag", "rag.max_chunks_per_kb",     "5",       "integer", "同KB最多切片数",   "同一个知识库在上下文中最多保留的切片数", "1", "20"),
                build("rag", "rag.front_bias_ratio",     "0.35",    "float",   "前向偏置比例",     "位置感知查询时分配给文档前部的切片比例",  "0.1", "0.7"),
                build("rag", "rag.graph_enabled",        "1",       "string",  "知识图谱召回(GraphRAG)", "开启后知识图谱作为独立第三路召回融入检索（命中实体→沿关系扩散→补召回），改善关联概念与简称追问；可随时关闭并回退「向量+BM25」。", null, null),
                // 解析模型（视频/图片/OCR 所用模型）· 运行时可在「AI 配置」切换
                build("parse", "video.mode",   "qwen-vl",       "string", "视频理解方式", "qwen-vl 原生(准确·贵) / legacy 经济(ASR+关键帧·口播访谈类性价比高)", null, null),
                build("parse", "video.model",  "qwen3-vl-plus", "string", "视频理解模型", "qwen3-vl-plus / qwen3-vl-flash(更省钱) / qwen-vl-max", null, null),
                build("parse", "vision.model", "qwen-vl-max",   "string", "图片识别模型", "qwen-vl-max / qwen3-vl-plus / qwen3-vl-flash", null, null),
                build("parse", "ocr.model",    "qwen3.5-ocr",   "string", "文档 OCR 模型", "扫描 PDF 专用 OCR；默认 qwen3.5-ocr，不影响图片语义理解模型", null, null),
                // Golden Pair 动态 few-shot：把相似的已审核问答作为参考范例注入提示词
                build("golden", "golden.fewshot.enabled",          "1",    "integer", "范例注入 · 开关",   "1=开启 0=关闭：将相似的已审核问答作为参考范例注入", "0",   "1"),
                build("golden", "golden.fewshot.top_k",            "2",    "integer", "范例条数上限",       "每次最多注入几条参考范例",                       "1",   "5"),
                build("golden", "golden.fewshot.min_score",        "0.75", "float",   "相似度下限",         "相似度低于此值不作为范例；命中阈值 0.92 以上走直接返回", "0.0", "1.0"),
                build("golden", "golden.fewshot.max_answer_chars", "600",  "integer", "单条范例答案截断",   "范例答案超过该字符数将被截断，用于控制 token 开销",   "100", "2000"),
                // 语音通话 · VAD 降噪/打断灵敏度（前端实时使用；管理员调完对「新发起的通话」即时生效）
                build("voice", "voice.vad_rms_thresh",      "0.04", "float",   "人声门限(RMS)",    "麦克风音量超过此值才算人声；越大越抗环境杂音，但对轻声越迟钝", "0.01", "0.2"),
                build("voice", "voice.speak_detect_ms",     "350",  "integer", "开始说话判定(ms)", "持续多久的人声才判定为「用户开始说话」",                  "100",  "1500"),
                build("voice", "voice.interrupt_detect_ms", "450",  "integer", "打断判定(ms)",     "AI 说话时，用户持续多久人声才触发打断",                    "100",  "2000")
        );
    }

    private SysAiConfig build(String group, String key, String value, String type,
                               String label, String desc, String min, String max) {
        SysAiConfig cfg = new SysAiConfig();
        cfg.setGroupName(group);
        cfg.setConfigKey(key);
        cfg.setConfigValue(value);
        cfg.setDefaultValue(value);
        cfg.setValueType(type);
        cfg.setLabel(label);
        cfg.setDescription(desc);
        cfg.setMinValue(min);
        cfg.setMaxValue(max);
        cfg.setDeleted(0);
        return cfg;
    }
}
