package com.ontime.group.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 약속 장소. 회차를 만들 때 그대로 복사(스냅샷)한다. */
@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Place {

  public static final int NAME_MAX_LENGTH = 50;

  @Column(name = "place_name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Column(nullable = false)
  private double latitude;

  @Column(nullable = false)
  private double longitude;

  private Place(String name, double latitude, double longitude) {
    this.name = name;
    this.latitude = latitude;
    this.longitude = longitude;
  }

  public static Place of(String name, double latitude, double longitude) {
    return new Place(name, latitude, longitude);
  }

  /** 회차 스냅샷용 복사본. 임베디드 값을 두 엔티티가 같은 인스턴스로 공유하지 않도록 새로 만든다. */
  public Place copy() {
    return new Place(name, latitude, longitude);
  }
}
