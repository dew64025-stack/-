package com.caizhitong.material.files;

import com.caizhitong.security.Actor;

public record UploadClaim(long id,Actor actor,String purpose,String fileName,String mime,long size,String expectedSha256,byte[] encryptedKey) {}
