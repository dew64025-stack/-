package com.caizhitong.integration.wechat;
import com.caizhitong.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
@Component public class WechatHttpGateway implements WechatGateway {
    private final String appId;private final String secret;private final WechatTransport transport;private final JsonMapper mapper;
    public WechatHttpGateway(@Value("${app.wechat.app-id:}")String appId,@Value("${app.wechat.app-secret:}")String secret,WechatTransport transport,JsonMapper mapper){this.appId=appId;this.secret=secret;this.transport=transport;this.mapper=mapper;}
    public Identity login(String code){configured();var json=exchange(request("/sns/jscode2session?appid="+encode(appId)+"&secret="+encode(secret)+"&js_code="+encode(code)+"&grant_type=authorization_code").GET().build());return new Identity(appId,string(json,"openid",128),json.has("unionid")?string(json,"unionid",128):null);}
    public Phone phone(String code){configured();var token=exchange(request("/cgi-bin/stable_token").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("grant_type","client_credential","appid",appId,"secret",secret,"force_refresh",false)))).build());
        String access=string(token,"access_token",4096);var json=exchange(request("/wxa/business/getuserphonenumber?access_token="+encode(access)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("code",code)))).build());JsonNode info=json.path("phone_info");if(!appId.equals(string(info.path("watermark"),"appid",64)))throw failed();return new Phone(appId,string(info,"countryCode",3),string(info,"purePhoneNumber",14));}
    private JsonNode exchange(HttpRequest request){try{var response=transport.send(request);if(response.status()!=200||response.body()==null||response.body().length>262144)throw failed();var json=mapper.readTree(response.body());if(!json.isObject())throw failed();if(json.has("errcode")&&(!json.path("errcode").isIntegralNumber()||json.path("errcode").asInt()!=0))throw failed();return json;}catch(BusinessException e){throw e;}catch(RuntimeException e){throw failed();}}
    private String string(JsonNode json,String field,int max){JsonNode value=json.path(field);if(!value.isString()||value.asString().isBlank()||value.asString().length()>max)throw failed();return value.asString();}
    private HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create("https://api.weixin.qq.com"+path)).timeout(Duration.ofSeconds(8));}
    private String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private void configured(){if(appId.isBlank()||secret.isBlank())throw unavailable();}
    private BusinessException unavailable(){return new BusinessException(503,"WECHAT_UNCONFIGURED","微信服务尚未配置",null,true);}
    private BusinessException failed(){return new BusinessException(502,"WECHAT_FAILED","微信身份服务暂时不可用",null,true);}
}
