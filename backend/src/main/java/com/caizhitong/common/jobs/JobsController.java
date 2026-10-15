package com.caizhitong.common.jobs;

import com.caizhitong.common.ApiResponse;
import com.caizhitong.common.RequestIdFilter;
import com.caizhitong.opportunity.OpportunityViews;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class JobsController {
    private final JobReadService jobs;
    public JobsController(JobReadService jobs) { this.jobs = jobs; }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<OpportunityViews.AsyncJob> read(@PathVariable String jobId, HttpServletRequest r) {
        return ApiResponse.ok(jobs.read(jobId), RequestIdFilter.requestId(r));
    }
}
