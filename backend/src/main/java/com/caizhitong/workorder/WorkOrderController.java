package com.caizhitong.workorder;

import com.caizhitong.common.*;
import com.caizhitong.identity.IdentityInputs;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class WorkOrderController {
    private final WorkOrderService service;
    public WorkOrderController(WorkOrderService service){this.service=service;}

    @PostMapping("/work-orders") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<WorkOrderViews.WorkOrder> create(@RequestBody WorkOrderInputs.Create input,HttpServletRequest r){return ok(service.create(input),r);}
    @GetMapping("/me/work-orders")
    public ApiResponse<WorkOrderViews.Page> myOrders(@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.myOrders(status,cursor,limit),r);}
    @GetMapping("/work-orders/{id}")
    public ApiResponse<WorkOrderViews.Detail> detail(@PathVariable String id,HttpServletRequest r){return ok(service.detail(IdentityInputs.id(id)),r);}
    @PostMapping("/work-orders/{id}/ratings") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<WorkOrderViews.Rating> rate(@PathVariable String id,@RequestBody WorkOrderInputs.Rating input,HttpServletRequest r){return ok(service.rate(IdentityInputs.id(id),input),r);}

    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
