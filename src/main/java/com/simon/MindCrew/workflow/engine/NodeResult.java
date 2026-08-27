package com.simon.MindCrew.workflow.engine;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** 节点执行结果：写回上下文的输出变量 + （仅 condition）选择的分支值。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodeResult {

    /** 要写回工作流变量上下文的键值（普通节点用） */
    private Map<String, Object> outputs = new LinkedHashMap<>();

    /** 条件分支节点解析出的分支值；普通节点为 null。引擎据此选择走哪条出边。 */
    private String branch;

    public static NodeResult of(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (key != null) m.put(key, value);
        return new NodeResult(m, null);
    }

    public static NodeResult branch(String branchValue) {
        return new NodeResult(new LinkedHashMap<>(), branchValue);
    }

    public static NodeResult empty() {
        return new NodeResult(new LinkedHashMap<>(), null);
    }
}
