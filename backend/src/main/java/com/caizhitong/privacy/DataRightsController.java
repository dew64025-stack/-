package com.caizhitong.privacy;

import com.caizhitong.common.ApiResponse;
import com.caizhitong.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
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
public class DataRightsController {
    private final DataRightsService dataRights;

    public DataRightsController(DataRightsService dataRights) {
        this.dataRights = dataRights;
    }

    @GetMapping("/me/authorizations")
    public ApiResponse<List<PrivacyViews.Authorization>> authorizations(HttpServletRequest request) {
        return ok(dataRights.authorizations(), request);
    }

    @PostMapping("/me/authorizations")
    public ApiResponse<PrivacyViews.Authorization> grant(@RequestBody PrivacyInputs.Grant input,
                                                         HttpServletRequest request) {
        return ok(dataRights.grant(input), request);
    }

    @PostMapping("/me/authorizations/{id}/revoke")
    public ApiResponse<PrivacyViews.Authorization> revoke(@PathVariable String id,
                                                          @RequestBody(required = false) PrivacyInputs.Empty input,
                                                          HttpServletRequest request) {
        return ok(dataRights.revoke(id), request);
    }

    @GetMapping("/me/privacy-settings")
    public ApiResponse<PrivacyViews.PrivacySettings> privacySettings(HttpServletRequest request) {
        return ok(dataRights.privacySettings(), request);
    }

    @PostMapping("/me/privacy-settings/update")
    public ApiResponse<PrivacyViews.PrivacySettings> updatePrivacy(@RequestBody PrivacyInputs.PrivacyUpdate input,
                                                                   HttpServletRequest request) {
        return ok(dataRights.updatePrivacy(input), request);
    }

    @PostMapping("/me/data-exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<PrivacyViews.DataRequest> exportData(HttpServletRequest request) {
        return ok(dataRights.exportData(), request);
    }

    @PostMapping("/me/account-deletion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<PrivacyViews.DataRequest> deleteAccount(@RequestBody PrivacyInputs.Deletion input,
                                                               HttpServletRequest request) {
        return ok(dataRights.deleteAccount(input), request);
    }

    @GetMapping("/me/data-requests")
    public ApiResponse<PrivacyViews.DataRequestPage> dataRequests(@RequestParam(required = false) String cursor,
                                                                  @RequestParam(required = false) Integer limit,
                                                                  HttpServletRequest request) {
        return ok(dataRights.dataRequests(cursor, limit), request);
    }

    @GetMapping("/me/data-requests/{id}")
    public ApiResponse<PrivacyViews.DataRequest> dataRequest(@PathVariable String id, HttpServletRequest request) {
        return ok(dataRights.dataRequest(id), request);
    }

    @PostMapping("/me/data-requests/{id}/cancel")
    public ApiResponse<PrivacyViews.DataRequest> cancel(@PathVariable String id,
                                                        @RequestBody(required = false) PrivacyInputs.Empty input,
                                                        HttpServletRequest request) {
        return ok(dataRights.cancel(id), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, RequestIdFilter.requestId(request));
    }
}
