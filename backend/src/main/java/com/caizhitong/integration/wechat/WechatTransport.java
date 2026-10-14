package com.caizhitong.integration.wechat;
import java.net.http.HttpRequest;
public interface WechatTransport {
    record Response(int status,byte[] body){}
    Response send(HttpRequest request);
}
