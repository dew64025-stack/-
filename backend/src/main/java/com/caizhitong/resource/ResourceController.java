package com.caizhitong.resource;

import com.caizhitong.campus.CampusService;
import com.caizhitong.common.*;
import com.caizhitong.identity.IdentityInputs;
import com.caizhitong.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class ResourceController {
    private final ResourceService service;
    private final CurrentActor current;
    private final CampusService campuses;
    public ResourceController(ResourceService service,CurrentActor current,CampusService campuses){this.service=service;this.current=current;this.campuses=campuses;}

    @GetMapping("/resources")
    public ApiResponse<ResourceViews.Page> list(@RequestParam(required=false)String type,@RequestParam(required=false)String regionId,@RequestParam(required=false)String campusCode,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.list(campus(campusCode),type,regionId,cursor,limit),r);}
    @GetMapping("/resources/{id}")
    public ApiResponse<ResourceViews.Resource> detail(@PathVariable String id,@RequestParam(required=false)String campusCode,HttpServletRequest r){return ok(service.detail(IdentityInputs.id(id),campus(campusCode)),r);}
    @GetMapping("/resources/{id}/slots")
    public ApiResponse<List<ResourceViews.Slot>> slots(@PathVariable String id,@RequestParam String date,@RequestParam(required=false)String campusCode,HttpServletRequest r){return ok(service.slots(IdentityInputs.id(id),campus(campusCode),date),r);}
    @GetMapping("/resources/{id}/availability")
    public ApiResponse<ResourceViews.Availability> availability(@PathVariable String id,@RequestParam String date,@RequestParam(required=false)String slotId,HttpServletRequest r){return ok(service.resourceAvailability(IdentityInputs.id(id),date,slotId),r);}
    @GetMapping("/resource-slots/{id}/availability")
    public ApiResponse<ResourceViews.SlotAvailability> slotAvailability(@PathVariable String id,HttpServletRequest r){return ok(service.slotAvailability(IdentityInputs.id(id)),r);}
    @PostMapping("/reservations") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<ResourceViews.Reservation> reserve(@RequestBody ResourceInputs.Reserve input,HttpServletRequest r){return ok(service.reserve(input),r);}
    @GetMapping("/me/reservations")
    public ApiResponse<ResourceViews.ReservationPage> myReservations(@RequestParam(required=false)String status,@RequestParam(required=false)String dateFrom,@RequestParam(required=false)String dateTo,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.myReservations(status,dateFrom,dateTo,cursor,limit),r);}
    @GetMapping("/reservations/{id}")
    public ApiResponse<ResourceViews.Reservation> reservationDetail(@PathVariable String id,HttpServletRequest r){return ok(service.reservationDetail(IdentityInputs.id(id)),r);}
    @PostMapping("/reservations/{id}/cancel")
    public ApiResponse<ResourceViews.Reservation> cancel(@PathVariable String id,@RequestBody ResourceInputs.Cancel input,HttpServletRequest r){return ok(service.cancel(IdentityInputs.id(id),input),r);}
    @PostMapping("/reservations/{id}/check-in")
    public ApiResponse<ResourceViews.CheckedIn> checkIn(@PathVariable String id,@RequestBody ResourceInputs.CheckIn input,HttpServletRequest r){return ok(service.checkIn(IdentityInputs.id(id),input),r);}

    private long campus(String code){return current.optional().map(Actor::campusId).orElseGet(()->code==null?Long.parseLong(campuses.campuses().stream().findFirst().orElseThrow(IdentityInputs::invalid).id()):campuses.open(code));}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
