package com.caizhitong.material.files;

import com.caizhitong.security.Actor;

/** Future business modules must verify an actual linked object, participant or reviewed public object.
 * Same-campus membership alone is never a grant. Null actor is reserved for explicit public business reads. */
public interface LinkedAttachmentPolicy {
    boolean permitsRead(Actor actor,AttachmentScope attachment);
}
