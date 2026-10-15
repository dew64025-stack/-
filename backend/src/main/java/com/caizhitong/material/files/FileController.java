package com.caizhitong.material.files;

import com.caizhitong.common.*;
import com.caizhitong.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static com.caizhitong.identity.IdentityInputs.*;

@RestController @RequestMapping("/api/v1")
public class FileController {
    private final UploadService uploads;
    private final CurrentActor current;
    private final UploadCompletionService completions;
    private final AttachmentService files;
    private final MaterialService materials;
    public FileController(UploadService uploads,CurrentActor current,UploadCompletionService completions,AttachmentService files,MaterialService materials){this.uploads=uploads;this.current=current;this.completions=completions;this.files=files;this.materials=materials;}
    @PostMapping("/materials/upload-intents")
    public ApiResponse<FileViews.Intent> materialIntent(@RequestBody FileInputs.MaterialIntent input,HttpServletRequest r) {
        Actor actor=current.require();if(actor.kind()!=SessionKind.STUDENT)throw FilePurpose.forbidden();
        return ok(uploads.intent(actor,"material",text(input.fileName(),255,false),text(input.mimeType(),128,false),input.sizeBytes().asLong(),hash(input.sha256())),r);
    }
    @PostMapping({"/files/upload-intents","/admin/files/upload-intents"})
    public ApiResponse<FileViews.Intent> intent(@RequestBody FileInputs.Intent input,HttpServletRequest r) {
        Actor actor=r.getRequestURI().contains("/admin/")?current.requirePermission("file.upload"):current.require();
        return ok(uploads.intent(actor,text(input.purpose(),32,false),text(input.fileName(),255,false),text(input.mimeType(),128,false),input.sizeBytes().asLong(),hash(input.sha256())),r);
    }
    private String hash(tools.jackson.databind.JsonNode node){return node==null||node.isNull()?null:node.asString();}
    @PostMapping("/files/uploads/{uploadId}") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<FileViews.Received> receive(@PathVariable String uploadId,@RequestParam("file") org.springframework.web.multipart.MultipartFile file,HttpServletRequest r) {
        var claim=(UploadClaim)r.getAttribute(UploadClaim.class.getName());
        if(claim==null||claim.id()!=id(uploadId))throw UploadCredentialFilter.invalid();
        var multipart=org.springframework.web.util.WebUtils.getNativeRequest(r,org.springframework.web.multipart.MultipartHttpServletRequest.class);
        if(multipart==null||!multipart.getMultiFileMap().keySet().equals(java.util.Set.of("file"))
                ||multipart.getMultiFileMap().get("file").size()!=1)throw invalid();
        return ok(uploads.receive(claim,file),r);
    }
    @PostMapping({"/files/uploads/{uploadId}/complete","/admin/files/uploads/{uploadId}/complete"}) @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<FileViews.Completed> complete(@PathVariable String uploadId,@RequestBody FileInputs.Empty input,HttpServletRequest r){
        long id=id(uploadId);boolean admin=r.getRequestURI().contains("/admin/");Actor actor=admin?current.requirePermission("file.upload"):current.require();
        completions.authorize(actor,id);return ok(admin?completions.admin(id):completions.student(id),r);
    }
    @GetMapping({"/files/{attachmentId}","/admin/files/{attachmentId}"})
    public ApiResponse<FileViews.Metadata> metadata(@PathVariable String attachmentId,HttpServletRequest r){return ok(files.metadata(reader(r),id(attachmentId)),r);}
    @PostMapping({"/files/{attachmentId}/download-url","/admin/files/{attachmentId}/download-url"})
    public ApiResponse<FileViews.Download> download(@PathVariable String attachmentId,@RequestBody FileInputs.Empty input,HttpServletRequest r){return ok(files.download(reader(r),id(attachmentId)),r);}
    private Actor reader(HttpServletRequest r){return r.getRequestURI().contains("/admin/")?current.requirePermission("file.read"):current.require();}
    @PostMapping("/materials") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<FileViews.Material> create(@RequestBody FileInputs.CreateMaterial input,HttpServletRequest r){files.owner(current.require(),id(input.attachmentId()),"material",true,false);return ok(materials.create(input),r);}
    @GetMapping("/materials")
    public ApiResponse<FileViews.Page> list(@RequestParam(required=false) String q,@RequestParam(required=false) String tag,@RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit,HttpServletRequest r){return ok(materials.list(q,tag,cursor,limit),r);}
    @GetMapping("/materials/{materialId}")
    public ApiResponse<FileViews.MaterialDetail> detail(@PathVariable String materialId,HttpServletRequest r){return ok(materials.detail(id(materialId)),r);}
    @PostMapping("/materials/{materialId}/update")
    public ApiResponse<FileViews.Material> update(@PathVariable String materialId,@RequestBody FileInputs.UpdateMaterial input,HttpServletRequest r){long id=id(materialId);materials.authorize(id,false);return ok(materials.update(id,input),r);}
    @PostMapping("/materials/{materialId}/versions") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<FileViews.Material> append(@PathVariable String materialId,@RequestBody FileInputs.Append input,HttpServletRequest r){long id=id(materialId);materials.authorize(id,false);files.owner(current.require(),id(input.attachmentId()),"material",true,false);return ok(materials.append(id,input),r);}
    @DeleteMapping("/materials/{materialId}")
    public ApiResponse<FileViews.Deleted> delete(@PathVariable String materialId,@RequestParam String version,HttpServletRequest r){
        if(!version.matches("0|[1-9][0-9]{0,9}"))throw invalid();int v;try{v=Integer.parseInt(version);}catch(NumberFormatException e){throw invalid();}
        long id=id(materialId);materials.authorize(id,true);return ok(materials.delete(id,v),r);
    }
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,RequestIdFilter.requestId(r));}
}
