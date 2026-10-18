package com.caizhitong.discovery;

import com.caizhitong.common.ApiResponse;
import com.caizhitong.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DiscoveryController {
    private final HomeService home;
    private final SearchService search;

    public DiscoveryController(HomeService home, SearchService search) {
        this.home = home;
        this.search = search;
    }

    @GetMapping("/home/overview")
    public ApiResponse<DiscoveryViews.Overview> overview(@RequestParam(required = false) String campusCode,
            @RequestParam(required = false) String regionId, @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude, @RequestParam(required = false) Integer radiusMeters,
            HttpServletRequest request) {
        return ok(home.overview(campusCode, regionId, latitude, longitude, radiusMeters), request);
    }

    @GetMapping("/search")
    public ApiResponse<DiscoveryViews.SearchPage> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> types, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String campusCode,
            HttpServletRequest request) {
        return ok(search.search(campusCode, q, types, cursor, limit), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, RequestIdFilter.requestId(request));
    }
}
