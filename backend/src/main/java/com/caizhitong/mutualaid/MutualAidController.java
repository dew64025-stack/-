package com.caizhitong.mutualaid;

import com.caizhitong.campus.CampusService;
import com.caizhitong.common.*;
import com.caizhitong.identity.IdentityInputs;
import com.caizhitong.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static com.caizhitong.identity.IdentityInputs.*;

@RestController @RequestMapping("/api/v1")
public class MutualAidController {
    private final MutualAidService service;
    private final CurrentActor current;
    private final CampusService campuses;
    public MutualAidController(MutualAidService service,CurrentActor current,CampusService campuses){this.service=service;this.current=current;this.campuses=campuses;}

    @PostMapping("/mutual-aid/posts") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<MutualAidViews.Post> create(@RequestBody MutualAidInputs.Create input,HttpServletRequest r){return ok(service.create(input),r);}
    @GetMapping("/mutual-aid/posts")
    public ApiResponse<MutualAidViews.Page> list(@RequestParam(required=false)String q,@RequestParam(required=false)String category,@RequestParam(required=false)String campusCode,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.list(campus(campusCode),q,category,cursor,limit),r);}
    @GetMapping("/mutual-aid/posts/{id}")
    public ApiResponse<MutualAidViews.Detail> detail(@PathVariable String id,@RequestParam(required=false)String campusCode,HttpServletRequest r){return ok(service.detail(IdentityInputs.id(id),campus(campusCode)),r);}
    @PostMapping("/mutual-aid/posts/{id}/update")
    public ApiResponse<MutualAidViews.Post> update(@PathVariable String id,@RequestBody MutualAidInputs.Update input,HttpServletRequest r){return ok(service.update(IdentityInputs.id(id),input),r);}
    @PostMapping("/mutual-aid/posts/{id}/actions")
    public ApiResponse<MutualAidViews.Post> action(@PathVariable String id,@RequestBody MutualAidInputs.Action input,HttpServletRequest r){return ok(service.action(IdentityInputs.id(id),input),r);}
    @PostMapping("/mutual-aid/posts/{id}/ratings") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<MutualAidViews.Rating> rate(@PathVariable String id,@RequestBody MutualAidInputs.Rating input,HttpServletRequest r){return ok(service.rate(IdentityInputs.id(id),input),r);}
    @GetMapping("/mutual-aid/posts/{id}/ratings")
    public ApiResponse<MutualAidViews.RatingPage> ratings(@PathVariable String id,@RequestParam(required=false)String campusCode,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.ratings(IdentityInputs.id(id),campus(campusCode),cursor,limit),r);}
    @GetMapping("/me/mutual-aid/posts")
    public ApiResponse<MutualAidViews.Page> myPosts(@RequestParam(required=false)String category,@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.myPosts(category,status,cursor,limit),r);}
    @GetMapping("/lost-found/matches")
    public ApiResponse<MutualAidViews.MatchPage> matches(@RequestParam(required=false)String postId,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.matches(postId,cursor,limit),r);}
    @PostMapping("/lost-found/matches/{id}/actions")
    public ApiResponse<MutualAidViews.Match> matchAction(@PathVariable String id,@RequestBody MutualAidInputs.MatchAction input,HttpServletRequest r){return ok(service.matchAction(IdentityInputs.id(id),input),r);}
    @PostMapping("/mutual-aid/posts/{id}/borrow-requests") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<MutualAidViews.Borrow> borrow(@PathVariable String id,@RequestBody MutualAidInputs.BorrowStart input,HttpServletRequest r){return ok(service.borrow(IdentityInputs.id(id),input),r);}
    @GetMapping("/me/borrow-requests")
    public ApiResponse<MutualAidViews.BorrowPage> myBorrows(@RequestParam(required=false)String side,@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.myBorrows(side,status,cursor,limit),r);}
    @PostMapping("/borrow-requests/{id}/actions")
    public ApiResponse<MutualAidViews.Borrow> borrowAction(@PathVariable String id,@RequestBody MutualAidInputs.BorrowAction input,HttpServletRequest r){return ok(service.borrowAction(IdentityInputs.id(id),input),r);}

    private long campus(String code){return current.optional().map(Actor::campusId).orElseGet(()->{if(code==null)throw IdentityInputs.invalid();return campuses.open(code);});}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
