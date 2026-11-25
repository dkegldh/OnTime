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
}
