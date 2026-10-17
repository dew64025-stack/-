package com.caizhitong.team;

import com.caizhitong.campus.CampusService;
import com.caizhitong.common.*;
import com.caizhitong.identity.IdentityInputs;
import com.caizhitong.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class TeamController {
    private final TeamService service;
    private final CurrentActor current;
    private final CampusService campuses;
    public TeamController(TeamService service,CurrentActor current,CampusService campuses){this.service=service;this.current=current;this.campuses=campuses;}

    @GetMapping("/teams")
    public ApiResponse<TeamViews.Page> list(@RequestParam(required=false)String q,@RequestParam(required=false)String opportunityId,@RequestParam(required=false)String skill,@RequestParam(required=false)String campusCode,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.list(campus(campusCode),q,opportunityId,skill,cursor,limit),r);}
    @GetMapping("/teams/{id}")
    public ApiResponse<TeamViews.Detail> detail(@PathVariable String id,@RequestParam(required=false)String campusCode,HttpServletRequest r){return ok(service.detail(IdentityInputs.id(id),campus(campusCode)),r);}
    @PostMapping("/teams") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<TeamViews.Team> create(@RequestBody TeamInputs.Create input,HttpServletRequest r){return ok(service.create(input),r);}
    @PostMapping("/teams/{id}/update")
    public ApiResponse<TeamViews.Team> update(@PathVariable String id,@RequestBody TeamInputs.Update input,HttpServletRequest r){return ok(service.update(IdentityInputs.id(id),input),r);}
    @PostMapping("/team-applications") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<TeamViews.Application> apply(@RequestBody TeamInputs.Apply input,HttpServletRequest r){return ok(service.apply(input),r);}
    @GetMapping("/teams/{id}/applications")
    public ApiResponse<TeamViews.ApplicationPage> applications(@PathVariable String id,@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.applications(IdentityInputs.id(id),status,cursor,limit),r);}
    @PostMapping("/team-applications/{id}/decision")
    public ApiResponse<TeamViews.Application> decide(@PathVariable String id,@RequestBody TeamInputs.Decision input,HttpServletRequest r){return ok(service.decide(IdentityInputs.id(id),input),r);}
    @PostMapping("/teams/{id}/leave")
    public ApiResponse<TeamViews.Left> leave(@PathVariable String id,@RequestBody TeamInputs.Empty input,HttpServletRequest r){return ok(service.leave(IdentityInputs.id(id)),r);}
    @PostMapping("/teams/{id}/close")
    public ApiResponse<TeamViews.Team> close(@PathVariable String id,@RequestBody TeamInputs.Close input,HttpServletRequest r){return ok(service.close(IdentityInputs.id(id),input),r);}
    @GetMapping("/me/teams")
    public ApiResponse<TeamViews.Page> myTeams(@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.myTeams(cursor,limit),r);}
    @GetMapping("/me/team-applications")
    public ApiResponse<TeamViews.ApplicationPage> myApplications(@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.myApplications(status,cursor,limit),r);}

    private long campus(String code){return current.optional().map(Actor::campusId).orElseGet(()->code==null?Long.parseLong(campuses.campuses().stream().findFirst().orElseThrow(IdentityInputs::invalid).id()):campuses.open(code));}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
