package com.caizhitong.common.ratelimit;
import java.time.Duration;
public interface RateCounter { long increment(String bucket, Duration window); }
