package com.caizhitong.material.files;

import com.caizhitong.identity.IdentityFiles;
import com.caizhitong.security.*;
import org.springframework.stereotype.Component;

@Component
public class IdentityFileAccess implements IdentityFiles,AvatarUrls {
    private final AttachmentService files;
    private final ActorLoader actors;
    public IdentityFileAccess(AttachmentService files,ActorLoader actors){this.files=files;this.actors=actors;}
    public void validateAvatar(Actor actor,long id){if(actor.kind()!=SessionKind.STUDENT)throw FilePurpose.forbidden();var a=files.owner(actor,id,"avatar",true,false);if(!a.mime().startsWith("image/")||a.size()>5*1024*1024)throw FilePurpose.conflict();}
    public void validateRoster(Actor actor,long id){
        Actor fresh=actors.load(actor.userId(),actor.campusId(),SessionKind.ADMIN).filter(a->actor.kind()==SessionKind.ADMIN&&"active".equals(a.status())&&a.permissions().contains("identity.import")).orElseThrow(FilePurpose::forbidden);
        var a=files.owner(fresh,id,"student_roster",true,false);if(!a.mime().equals("text/csv")||a.size()>10*1024*1024)throw FilePurpose.conflict();
    }
    public byte[] roster(Actor actor,long id,int maxBytes){validateRoster(actor,id);if(maxBytes<1||maxBytes>10*1024*1024)throw com.caizhitong.identity.IdentityInputs.invalid();return files.readCleanOwner(actor,id,"student_roster",maxBytes);}
    public String avatarUrl(Actor actor,long id){validateAvatar(actor,id);return files.download(actor,id).url();}
}
