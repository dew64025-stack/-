package com.caizhitong.campus;
import com.caizhitong.common.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1") public class CampusController {
    private final CampusService service;public CampusController(CampusService service){this.service=service;}
    @GetMapping("/campuses") public ApiResponse<?> campuses(HttpServletRequest r){return ApiResponse.ok(service.campuses(),RequestIdFilter.requestId(r));}
    @GetMapping("/campus/regions") public ApiResponse<?> regions(@RequestParam(required=false)String campusCode,HttpServletRequest r){return ApiResponse.ok(service.regions(campusCode),RequestIdFilter.requestId(r));}
    @GetMapping("/campus/organizations") public ApiResponse<?> organizations(@RequestParam(required=false)String campusCode,@RequestParam(required=false)String type,HttpServletRequest r){return ApiResponse.ok(service.organizations(campusCode,type),RequestIdFilter.requestId(r));}
}
