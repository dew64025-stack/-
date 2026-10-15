package com.caizhitong.material.files;

import java.io.*;
import java.security.*;
import java.util.HexFormat;
import com.caizhitong.common.BusinessException;

final class BoundedDigestStream extends FilterInputStream {
    private final long max;
    private final MessageDigest digest;
    private long count;
    BoundedDigestStream(InputStream input,long max){super(input);this.max=max;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @Override public int read()throws IOException {int b=in.read();if(b>=0){check(1);digest.update((byte)b);}return b;}
    @Override public int read(byte[] bytes,int offset,int length)throws IOException {
        int n=in.read(bytes,offset,(int)Math.min(length,max-count+1));if(n>0){check(n);digest.update(bytes,offset,n);}return n;
    }
    private void check(int n){count+=n;if(count>max)throw new BusinessException(413,"FILE_TOO_LARGE","文件超过大小限制");}
    long count(){return count;}
    String sha256(){return HexFormat.of().formatHex(digest.digest());}
}
