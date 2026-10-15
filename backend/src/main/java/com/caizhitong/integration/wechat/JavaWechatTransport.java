package com.caizhitong.integration.wechat;

import com.caizhitong.common.BusinessException;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component public class JavaWechatTransport implements WechatTransport {
    private final HttpClient client;
    @Autowired public JavaWechatTransport(){this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());}
    public JavaWechatTransport(HttpClient client){this.client=client;}
    public Response send(HttpRequest request){var uri=request.uri();if(!"https".equals(uri.getScheme())||!"api.weixin.qq.com".equals(uri.getHost())||(uri.getPort()!=-1&&uri.getPort()!=443)||uri.getUserInfo()!=null)throw failed();
        CompletableFuture<HttpResponse<byte[]>> pending=client.sendAsync(request,info->new LimitedBody());
        try{var response=pending.get(8,TimeUnit.SECONDS);return new Response(response.statusCode(),response.body());}
        catch(InterruptedException e){pending.cancel(true);Thread.currentThread().interrupt();throw failed();}
        catch(ExecutionException|TimeoutException|RuntimeException e){pending.cancel(true);throw failed();}}
    private static BusinessException failed(){return new BusinessException(502,"WECHAT_FAILED","微信身份服务暂时不可用",null,true);}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return body;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> buffers){for(ByteBuffer buffer:buffers){if(bytes.size()+buffer.remaining()>262144){subscription.cancel();body.completeExceptionally(failed());return;}byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
        public void onError(Throwable error){body.completeExceptionally(failed());}
        public void onComplete(){body.complete(bytes.toByteArray());}
    }
}
