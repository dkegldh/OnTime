package com.ontime.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트 안에서 시각을 옮길 수 있는 UTC Clock. 통합 테스트에서 {@code @Primary} 빈으로 등록해 쓴다. */
public final class MutableClock extends Clock {

  private volatile Instant now;

  public MutableClock(Instant now) {
    this.now = now;
  }

  public void setInstant(Instant now) {
    this.now = now;
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException();
  }
}
