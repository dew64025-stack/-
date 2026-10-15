package com.caizhitong.integration.wechat;
import com.caizhitong.common.BusinessException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class WechatHttpGatewayTest {
    @Test void trustedRequestAndWechatPhoneWatermarkControlReturnedIdentity(){
        List<HttpRequest> requests=new ArrayList<>();WechatTransport transport=request->{requests.add(request);assertEquals("https",request.uri().getScheme());assertEquals("api.weixin.qq.com",request.uri().getHost());assertEquals(Duration.ofSeconds(8),request.timeout().orElseThrow());String body=switch(request.uri().getPath()){case "/sns/jscode2session"->"{\"openid\":\"CaseOpenId\",\"unionid\":\"union\",\"session_key\":\"not-stored\"}";case "/cgi-bin/stable_token"->"{\"access_token\":\"server-token\",\"expires_in\":7200}";case "/wxa/business/getuserphonenumber"->"{\"errcode\":0,\"phone_info\":{\"phoneNumber\":\"13800000000\",\"purePhoneNumber\":\"13800000000\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\"test-app\",\"timestamp\":2000000000}}}";default->throw new AssertionError("Unexpected endpoint");};return new WechatTransport.Response(200,body.getBytes(StandardCharsets.UTF_8));};
        WechatGateway gateway=new WechatHttpGateway("test-app","test-secret",transport,new JsonMapper());
        assertEquals("CaseOpenId",assertDoesNotThrow(()->gateway.login("one-time-code")).openid());
        assertEquals(new WechatGateway.Phone("test-app","86","13800000000"),gateway.phone("phone-code"));
        assertEquals(3,requests.size());
    }
    @Test void upstreamErrorsOversizeMalformedAndRedirectsFailWithoutLeakingSecrets(){
        for(WechatTransport.Response response:List.of(new WechatTransport.Response(302,"redirect".getBytes()),new WechatTransport.Response(200,"{\"errcode\":40029,\"errmsg\":\"sensitive-openid\"}".getBytes()),new WechatTransport.Response(200,new byte[262145]),new WechatTransport.Response(200,"broken".getBytes()))){var gateway=new WechatHttpGateway("test-app","test-secret",request->response,new JsonMapper());BusinessException e=assertThrows(BusinessException.class,()->gateway.login("sensitive-code"));assertEquals(502,e.getStatus());assertFalse(e.getMessage().contains("sensitive"));assertNull(e.getCause());}
    }
    @Test void absentServerCredentialsNeverCallsUpstream(){var gateway=new WechatHttpGateway("","",request->{throw new AssertionError("No request allowed");},new JsonMapper());assertEquals(503,assertThrows(BusinessException.class,()->gateway.login("code")).getStatus());}
}
