package com.caizhitong.identity;
import com.caizhitong.common.BusinessException;
import com.caizhitong.security.Actor;
import org.springframework.stereotype.Component;
@Component
public class UnavailableIdentityFiles implements IdentityFiles {
    public void validateAvatar(Actor actor,long attachmentId){throw unavailable();}
    public void validateRoster(Actor actor,long attachmentId){throw unavailable();}
    public byte[] roster(Actor actor,long attachmentId,int maxBytes){throw unavailable();}
    private BusinessException unavailable(){return new BusinessException(503,"IDENTITY_FILES_UNAVAILABLE","身份文件服务尚未接入",null,true);}
}
