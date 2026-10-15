package com.caizhitong.workorder;

import com.caizhitong.common.*;
import com.caizhitong.identity.IdentityInputs;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin")
public class AdminWorkOrderController {
    private final WorkOrderService service;
    public AdminWorkOrderController(WorkOrderService service){this.service=service;}

    @GetMapping("/work-orders")
    public ApiResponse<WorkOrderViews.Page> orders(@RequestParam(required=false)String status,@RequestParam(required=false)String assigneeId,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.adminOrders(status,assigneeId,cursor,limit),r);}
    @PostMapping("/work-orders/{id}/assign")
    public ApiResponse<WorkOrderViews.WorkOrder> assign(@PathVariable String id,@RequestBody WorkOrderInputs.Assign input,HttpServletRequest r){return ok(service.assign(IdentityInputs.id(id),input),r);}
    @PostMapping("/work-orders/{id}/transition")
    public ApiResponse<WorkOrderViews.WorkOrder> transition(@PathVariable String id,@RequestBody WorkOrderInputs.Transition input,HttpServletRequest r){return ok(service.transition(IdentityInputs.id(id),input),r);}

    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
