package com.caizhitong.integration.wechat;
public interface WechatGateway {
    record Identity(String appId,String openid,String unionid){}
    record Phone(String appId,String countryCode,String nationalNumber){}
    Identity login(String code);
    Phone phone(String code);
}
