package com.ontime.global.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 기반 Clock 의 시각을 마이크로초 단위로 내림하는 Clock.
 *
 * <p>{@code Clock.tick(base, 1µs)} 를 쓰지 않는다. JDK 17의 tick Clock 은 1ms보다 작은 단위에서 {@code millis()} 가 {@code
 * ArithmeticException: / by zero} 를 던진다. 밀리초는 마이크로초 내림과 같은 값이므로 기반 Clock 에 그대로 맡긴다.
 */
public final class MicrosecondClock extends Clock {

  private final Clock base;

  public MicrosecondClock(Clock base) {
    this.base = Objects.requireNonNull(base);
  }

  @Override
  public Instant instant() {
    return base.instant().truncatedTo(ChronoUnit.MICROS);
  }

  @Override
  public long millis() {
    return base.millis();
  }

  @Override
  public ZoneId getZone() {
    return base.getZone();
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return new MicrosecondClock(base.withZone(zone));
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof MicrosecondClock other && base.equals(other.base);
  }

  @Override
  public int hashCode() {
    return base.hashCode() ^ 0x4d696372;
  }

  @Override
  public String toString() {
    return "MicrosecondClock[" + base + "]";
  }
}
