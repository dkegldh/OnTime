package com.ontime.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 테스트 안에서 시각을 옮길 수 있는 UTC Clock. 공용 {@link MutableClockConfig} 는 이 Clock 을 운영과 같은 마이크로초 래퍼로
 * 감싸 {@code @Primary} Clock 으로 등록한다.
 */
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
