package com.caizhitong.admin.identity;
public interface AdminProvisioning {
    record Input(long operatorId,long userId,long campusId,String roleCode,String username,String password,String mfaSeed){}
    void initialize(Input input);
}
