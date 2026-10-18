package com.caizhitong.me;

import com.caizhitong.common.ApiResponse;
import com.caizhitong.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MeController {
    private final MeService me;

    public MeController(MeService me) {
        this.me = me;
    }

    @GetMapping("/me/dashboard")
    public ApiResponse<MeViews.Dashboard> dashboard(HttpServletRequest request) {
        return ok(me.dashboard(), request);
    }

    @GetMapping("/me/todos")
    public ApiResponse<MeViews.TodoPage> todos(@RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit,
                                               HttpServletRequest request) {
        return ok(me.todos(cursor, limit), request);
    }

    @GetMapping("/me/history")
    public ApiResponse<MeViews.HistoryPage> history(@RequestParam(required = false) String cursor,
                                                    @RequestParam(required = false) Integer limit,
                                                    HttpServletRequest request) {
        return ok(me.history(cursor, limit), request);
    }

    @DeleteMapping("/me/history")
    public ApiResponse<MeViews.Cleared> clearHistory(HttpServletRequest request) {
        return ok(me.clearHistory(), request);
    }

    @GetMapping("/me/growth-records")
    public ApiResponse<MeViews.GrowthPage> growth(@RequestParam(required = false) String type,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer limit,
                                                  HttpServletRequest request) {
        return ok(me.growthRecords(type, cursor, limit), request);
    }

    @PostMapping("/me/growth-records")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MeViews.GrowthRecord> createGrowth(@RequestBody MeInputs.GrowthCreate input,
                                                          HttpServletRequest request) {
        return ok(me.createGrowth(input), request);
    }

    @PostMapping("/me/growth-records/{id}/update")
    public ApiResponse<MeViews.GrowthRecord> updateGrowth(@PathVariable String id,
                                                          @RequestBody MeInputs.GrowthUpdate input,
                                                          HttpServletRequest request) {
        return ok(me.updateGrowth(id, input), request);
    }

    @DeleteMapping("/me/growth-records/{id}")
    public ApiResponse<MeViews.Deleted> deleteGrowth(@PathVariable String id,
                                                     @RequestParam int version,
                                                     HttpServletRequest request) {
        return ok(me.deleteGrowth(id, version), request);
    }

    @GetMapping("/me/credit-events")
    public ApiResponse<MeViews.CreditPage> credit(@RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer limit,
                                                  HttpServletRequest request) {
        return ok(me.creditEvents(cursor, limit), request);
    }

    @PostMapping("/me/credit-events/{id}/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MeViews.Dispute> dispute(@PathVariable String id,
                                                @RequestBody MeInputs.CreditDispute input,
                                                HttpServletRequest request) {
        return ok(me.dispute(id, input), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, RequestIdFilter.requestId(request));
    }
}
