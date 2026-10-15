package com.caizhitong.material.files;

/** Future history/AI/business modules register actual retention obligations before binding versions. */
public interface MaterialRetentionPolicy {
    boolean mustRetain(long materialId);
}
