package com.caizhitong.material.checklist;

import com.caizhitong.common.ApiResponse;
import com.caizhitong.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
public class ChecklistController {
    private final ChecklistService checklists;
    public ChecklistController(ChecklistService checklists) { this.checklists = checklists; }

    @PutMapping("/opportunities/{id}/checklist")
    public ApiResponse<ChecklistService.Checklist> getOrCreate(@PathVariable String id, @RequestBody(required = false) Object ignored, HttpServletRequest r) {
        return ok(checklists.getOrCreate(id), r);
    }

    @GetMapping("/checklists/{id}")
    public ApiResponse<ChecklistService.Checklist> get(@PathVariable String id, HttpServletRequest r) {
        return ok(checklists.get(id), r);
    }

    @PostMapping("/checklists/{id}/items/{itemId}/update")
    public ApiResponse<ChecklistService.Checklist> updateItem(@PathVariable String id, @PathVariable String itemId,
            @RequestBody JsonNode input, HttpServletRequest r) {
        return ok(checklists.updateItem(id, itemId, input), r);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest r) { return ApiResponse.ok(data, RequestIdFilter.requestId(r)); }
}
