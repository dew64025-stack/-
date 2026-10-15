package com.caizhitong.opportunity;

import com.caizhitong.common.ApiResponse;
import com.caizhitong.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class OpportunityController {
    private final OpportunityService opportunities;
    private final FavoriteService favorites;
    private final ReminderService reminders;
    private final SubmissionService submissions;
    private final EligibilityService eligibility;
    public OpportunityController(OpportunityService opportunities, FavoriteService favorites, ReminderService reminders,
                                 SubmissionService submissions, EligibilityService eligibility) {
        this.opportunities = opportunities;
        this.favorites = favorites;
        this.reminders = reminders;
        this.submissions = submissions;
        this.eligibility = eligibility;
    }

    public record ReminderCreate(String targetType, String targetId, String remindAt) {}

    public record SubmissionCreate(String submittedAt) {}

    @GetMapping("/opportunities")
    public ApiResponse<OpportunityViews.Page> list(@RequestParam(required = false) String campusCode,
            @RequestParam(required = false) String q, @RequestParam(required = false) String category,
            @RequestParam(required = false) String deadlineFrom, @RequestParam(required = false) String deadlineTo,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit, HttpServletRequest r) {
        return ok(opportunities.list(campusCode, q, category, deadlineFrom, deadlineTo, cursor, limit), r);
    }

    @GetMapping("/opportunities/{id}")
    public ApiResponse<OpportunityViews.Detail> detail(@PathVariable String id,
            @RequestParam(required = false) String campusCode, HttpServletRequest r) {
        return ok(opportunities.detail(id, campusCode), r);
    }

    @PostMapping("/opportunities/{id}/eligibility-checks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<OpportunityViews.AsyncJob> check(@PathVariable String id, @RequestBody(required = false) Object ignored, HttpServletRequest r) {
        return ok(eligibility.enqueue(id), r);
    }

    @GetMapping("/opportunities/{id}/eligibility-checks/latest")
    public ApiResponse<OpportunityViews.EligibilityResult> latest(@PathVariable String id, HttpServletRequest r) {
        return ok(eligibility.latest(id), r);
    }

    @PutMapping("/opportunities/{id}/submission")
    public ApiResponse<OpportunityViews.Submission> submit(@PathVariable String id, @RequestBody SubmissionCreate input, HttpServletRequest r) {
        Instant submittedAt = OpportunityInputs.rfc3339(input.submittedAt());
        return ok(submissions.submit(id, submittedAt), r);
    }

    @PutMapping("/favorites/{targetType}/{targetId}")
    public ApiResponse<OpportunityViews.Favorited> favorite(@PathVariable String targetType, @PathVariable String targetId, HttpServletRequest r) {
        return ok(favorites.put(targetType, targetId), r);
    }

    @DeleteMapping("/favorites/{targetType}/{targetId}")
    public ApiResponse<OpportunityViews.Favorited> unfavorite(@PathVariable String targetType, @PathVariable String targetId, HttpServletRequest r) {
        return ok(favorites.delete(targetType, targetId), r);
    }

    @GetMapping("/me/favorites")
    public ApiResponse<OpportunityViews.FavoritePage> myFavorites(@RequestParam(required = false) String targetType,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit, HttpServletRequest r) {
        return ok(favorites.list(targetType, cursor, limit), r);
    }

    @PostMapping("/reminders")
    public ApiResponse<OpportunityViews.Reminder> reminder(@RequestBody ReminderCreate input, HttpServletRequest r) {
        Instant remindAt = OpportunityInputs.rfc3339(input.remindAt());
        return ok(reminders.create(input.targetType(), input.targetId(), remindAt), r);
    }

    @DeleteMapping("/reminders/{id}")
    public ApiResponse<OpportunityViews.Cancelled> cancelReminder(@PathVariable String id, HttpServletRequest r) {
        return ok(reminders.cancel(id), r);
    }

    @GetMapping("/me/reminders")
    public ApiResponse<OpportunityViews.ReminderPage> myReminders(@RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit, HttpServletRequest r) {
        return ok(reminders.list(status, cursor, limit), r);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest r) { return ApiResponse.ok(data, RequestIdFilter.requestId(r)); }
}
