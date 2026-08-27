package com.simon.MindCrew.digitalemployee.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.digitalemployee.dto.DigitalEmployeeDetailVO;
import com.simon.MindCrew.digitalemployee.dto.DigitalEmployeeSaveRequest;
import com.simon.MindCrew.digitalemployee.entity.DigitalEmployee;
import com.simon.MindCrew.digitalemployee.scenario.ScenarioTemplateRegistry;
import com.simon.MindCrew.digitalemployee.service.DigitalEmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/digital-employees")
@RequiredArgsConstructor
public class AdminDigitalEmployeeController {

    private final DigitalEmployeeService service;
    private final ScenarioTemplateRegistry scenarioRegistry;

    @GetMapping
    public Result<List<DigitalEmployee>> list(@RequestParam(required = false) String q) {
        return Result.success(service.listAllAdmin(q));
    }

    @GetMapping("/scenario-templates")
    public Result<List<Map<String, Object>>> scenarioTemplates() {
        List<Map<String, Object>> list = scenarioRegistry.listAll().stream()
                .map(t -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", t.id());
                    m.put("name", t.name());
                    m.put("description", t.description());
                    m.put("configFields", t.configFields().stream()
                            .map(f -> Map.<String, Object>of(
                                    "key", f.key(),
                                    "label", f.label(),
                                    "type", f.type(),
                                    "placeholder", f.placeholder() != null ? f.placeholder() : "",
                                    "defaultValue", f.defaultValue() != null ? f.defaultValue() : ""))
                            .toList());
                    return m;
                })
                .toList();
        return Result.success(list);
    }

    @PostMapping
    public Result<DigitalEmployee> create(@RequestBody DigitalEmployeeSaveRequest req) {
        return Result.success(service.createDraft(req));
    }

    @GetMapping("/{id}")
    public Result<DigitalEmployeeDetailVO> detail(@PathVariable Long id) {
        return Result.success(service.getDetail(id, true));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody DigitalEmployeeSaveRequest req) {
        service.update(id, req);
        return Result.success();
    }

    @PostMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable Long id) {
        service.publish(id);
        return Result.success();
    }

    @PostMapping("/{id}/unpublish")
    public Result<Void> unpublish(@PathVariable Long id) {
        service.unpublish(id);
        return Result.success();
    }

    @PostMapping("/{id}/optimize-prompt")
    public Result<String> optimizePrompt(@PathVariable Long id) {
        return Result.success(service.optimizePrompt(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }
}