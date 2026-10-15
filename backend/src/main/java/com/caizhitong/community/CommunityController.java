package com.caizhitong.community;

import com.caizhitong.common.*;
import com.caizhitong.identity.IdentityInputs;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static com.caizhitong.identity.IdentityInputs.*;

@RestController @RequestMapping("/api/v1")
public class CommunityController {
    private final CommunityService service;
    public CommunityController(CommunityService service){this.service=service;}

    @GetMapping("/community/posts")
    public ApiResponse<CommunityViews.Page> list(@RequestParam(required=false)String q,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.list(q,cursor,limit),r);}
    @GetMapping("/community/posts/{id}")
    public ApiResponse<CommunityViews.Post> detail(@PathVariable String id,HttpServletRequest r){return ok(service.detail(IdentityInputs.id(id)),r);}
    @PostMapping("/community/posts") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<CommunityViews.Post> create(@RequestBody CommunityInputs.Create input,HttpServletRequest r){return ok(service.create(input),r);}
    @PostMapping("/community/posts/{id}/update")
    public ApiResponse<CommunityViews.Post> update(@PathVariable String id,@RequestBody CommunityInputs.Update input,HttpServletRequest r){return ok(service.update(IdentityInputs.id(id),input),r);}
    @DeleteMapping("/community/posts/{id}")
    public ApiResponse<CommunityViews.Deleted> delete(@PathVariable String id,@RequestParam String version,HttpServletRequest r){
        int v=parseVersion(version);return ok(service.delete(IdentityInputs.id(id),v),r);
    }
    @GetMapping("/community/posts/{id}/comments")
    public ApiResponse<CommunityViews.CommentPage> comments(@PathVariable String id,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.comments(IdentityInputs.id(id),cursor,limit),r);}
    @PostMapping("/community/posts/{id}/comments") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<CommunityViews.Comment> comment(@PathVariable String id,@RequestBody CommunityInputs.Comment input,HttpServletRequest r){return ok(service.comment(IdentityInputs.id(id),input),r);}
    @DeleteMapping("/community/comments/{id}")
    public ApiResponse<CommunityViews.Deleted> deleteComment(@PathVariable String id,HttpServletRequest r){return ok(service.deleteComment(IdentityInputs.id(id)),r);}
    @GetMapping("/me/community/posts")
    public ApiResponse<CommunityViews.Page> myPosts(@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(required=false)Integer limit,HttpServletRequest r){return ok(service.myPosts(status,cursor,limit),r);}

    private int parseVersion(String version){if(version==null||!version.matches("0|[1-9][0-9]{0,9}"))throw invalid();try{return Integer.parseInt(version);}catch(NumberFormatException e){throw invalid();}}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
