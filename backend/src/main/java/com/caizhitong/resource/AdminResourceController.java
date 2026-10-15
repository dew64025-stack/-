package com.caizhitong.resource;

import com.caizhitong.common.*;
import com.caizhitong.identity.IdentityInputs;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin")
public class AdminResourceController {
    private final ResourceService service;
    public AdminResourceController(ResourceService service){this.service=service;}

    @GetMapping("/resources")
    public ApiResponse<ResourceViews.Page> resources(@RequestParam(required=false)String type,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.adminResources(type,cursor,limit),r);}
    @PostMapping("/resources") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<ResourceViews.Resource> create(@RequestBody ResourceInputs.Create input,HttpServletRequest r){return ok(service.create(input),r);}
    @PostMapping("/resources/{id}/update")
    public ApiResponse<ResourceViews.Resource> update(@PathVariable String id,@RequestBody ResourceInputs.Update input,HttpServletRequest r){return ok(service.update(IdentityInputs.id(id),input),r);}
    @PostMapping("/resources/{id}/slots") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<ResourceViews.Slot> createSlot(@PathVariable String id,@RequestBody ResourceInputs.SlotCreate input,HttpServletRequest r){return ok(service.createSlot(IdentityInputs.id(id),input),r);}
    @PostMapping("/resource-slots/{id}/update")
    public ApiResponse<ResourceViews.Slot> updateSlot(@PathVariable String id,@RequestBody ResourceInputs.SlotUpdate input,HttpServletRequest r){return ok(service.updateSlot(IdentityInputs.id(id),input),r);}
    @GetMapping("/reservations")
    public ApiResponse<ResourceViews.ReservationPage> reservations(@RequestParam(required=false)String status,@RequestParam(required=false)String resourceId,@RequestParam(required=false)String date,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.adminReservations(status,resourceId,date,cursor,limit),r);}
    @PostMapping("/reservations/{id}/decision")
    public ApiResponse<ResourceViews.Reservation> decide(@PathVariable String id,@RequestBody ResourceInputs.Decision input,HttpServletRequest r){return ok(service.decide(IdentityInputs.id(id),input),r);}
    @PostMapping("/reservations/{id}/check-in-token")
    public ApiResponse<ResourceViews.CheckInToken> checkInToken(@PathVariable String id,@RequestBody ResourceInputs.Empty input,HttpServletRequest r){return ok(service.checkInToken(IdentityInputs.id(id)),r);}

    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
