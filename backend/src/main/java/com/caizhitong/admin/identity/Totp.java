package com.caizhitong.admin.identity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
/** RFC 6238 SHA-1, six decimal digits, thirty-second step. */
public final class Totp {
    private Totp(){}
    public static long match(String base32,String code,long epochSeconds){if(code==null||!code.matches("[0-9]{6}"))return -1;byte[] key=decode(base32);long current=epochSeconds/30;for(long step=current-1;step<=current+1;step++){if(MessageDigest.isEqual(generate(key,step).getBytes(StandardCharsets.US_ASCII),code.getBytes(StandardCharsets.US_ASCII)))return step;}return -1;}
    private static String generate(byte[] key,long step){try{Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(key,"HmacSHA1"));byte[] hash=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());int offset=hash[19]&15;int binary=((hash[offset]&127)<<24)|((hash[offset+1]&255)<<16)|((hash[offset+2]&255)<<8)|(hash[offset+3]&255);return String.format(java.util.Locale.ROOT,"%06d",binary%1_000_000);}catch(java.security.GeneralSecurityException e){throw new IllegalStateException("MFA unavailable");}}
    public static byte[] decode(String value){if(value==null||!value.matches("[A-Z2-7]{32,128}"))throw new IllegalArgumentException("Invalid MFA seed");byte[] result=new byte[value.length()*5/8];int bits=0,buffer=0,index=0;for(char c:value.toCharArray()){int digit="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);buffer=(buffer<<5)|digit;bits+=5;if(bits>=8){bits-=8;result[index++]=(byte)(buffer>>bits);}}return result;}
}
