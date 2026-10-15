package com.caizhitong.material.files;

/** Trusted database metadata; deliberately excludes object keys and credentials. */
public record AttachmentScope(long attachmentId,long campusId,long ownerUserId,String accessDomain,String purpose) {}
