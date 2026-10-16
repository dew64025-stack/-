package com.caizhitong.material.files;

import com.caizhitong.security.Actor;

/** Future business modules must verify an actual linked object, participant or reviewed public object.
 * Same-campus membership alone is never a grant. Null actor is reserved for explicit public business reads. */
public interface LinkedAttachmentPolicy {
    boolean permitsRead(Actor actor,AttachmentScope attachment);
    /** Business DTO preview: the exact persisted parent must independently authorize this reader. */
    default boolean permitsBusinessRead(Actor actor,AttachmentScope attachment,long targetId){return false;}
}
