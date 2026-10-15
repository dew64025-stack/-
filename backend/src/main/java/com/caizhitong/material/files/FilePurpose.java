package com.caizhitong.material.files;

import com.caizhitong.common.BusinessException;
import com.caizhitong.security.*;
import java.util.Set;
import static com.caizhitong.identity.IdentityInputs.invalid;

public final class FilePurpose {
    private static final Set<String> STUDENT=Set.of("material","avatar","workorder","issue","mutual_aid","community","message");
    private static final Set<String> MIME=Set.of("image/jpeg","image/png","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private FilePurpose() {}
    public static long authorize(Actor actor,String purpose,String mime) {
        if(!"active".equals(actor.status()))throw forbidden();
        if(actor.kind()==SessionKind.ADMIN) {
            if(!actor.permissions().contains("file.upload"))throw forbidden();
            if(purpose.equals("student_roster")) {
                if(!actor.permissions().contains("identity.import"))throw forbidden();
                if(!mime.equals("text/csv"))throw invalid();return 10L*1024*1024;
            }
            if(!purpose.equals("notice_document"))throw forbidden();
            if(!actor.permissions().contains("notice.write"))throw forbidden();
        } else {
            if(!STUDENT.contains(purpose))throw forbidden();
            if(!Set.of("material","avatar").contains(purpose)&&(!actor.phoneBound()||!actor.studentVerified()))throw forbidden();
        }
        if(!MIME.contains(mime))throw invalid();
        boolean image=mime.startsWith("image/");
        if(!Set.of("material","notice_document").contains(purpose)&&!image)throw invalid();
        return image?5L*1024*1024:20L*1024*1024;
    }
    public static BusinessException forbidden(){return new BusinessException(403,"FORBIDDEN","无权执行此操作");}
    public static BusinessException missing(){return new BusinessException(404,"NOT_FOUND","未找到请求的资源");}
    public static BusinessException conflict(){return new BusinessException(409,"FILE_STATE_CONFLICT","文件状态已变化，请刷新后重试");}
    public static BusinessException unavailable(){return new BusinessException(503,"FILE_SERVICE_UNAVAILABLE","文件服务暂时不可用",null,true);}
}
