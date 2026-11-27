package com.ontime.meeting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeoDistanceTest {

  // 반지름 6,371km 구에서 위도/경도 1도에 해당하는 대원 거리 = 6,371,000 × π / 180 (손으로 계산한 상수)
  private static final double ONE_DEGREE_M = 111_194.93;

  @Test
  @DisplayName("같은 지점 사이의 거리는 0m다")
  void meters_samePoint_zero() {
    // when
    double distance = GeoDistance.meters(37.4979, 127.0276, 37.4979, 127.0276);

    // then
    assertThat(distance).isZero();
  }

  @Test
  @DisplayName("같은 경도에서 위도 1도 차이는 약 111,195m다")
  void meters_oneDegreeLatitude_about111km() {
    // when
    double distance = GeoDistance.meters(37.0, 127.0, 38.0, 127.0);

    // then
    assertThat(distance).isCloseTo(ONE_DEGREE_M, within(0.1));
  }

  @Test
  @DisplayName("위도 60도에서 경도 1도 차이는 적도의 약 절반인 55,597m다 (위도에 따른 경도 간격 축소 반영)")
  void meters_oneDegreeLongitudeAt60_aboutHalf() {
    // when
    double distance = GeoDistance.meters(60.0, 10.0, 60.0, 11.0);

    // then: 2R·asin(cos60°·sin0.5°) = 55,596.93m
    assertThat(distance).isCloseTo(55_596.93, within(0.1));
  }

  @Test
  @DisplayName("날짜변경선을 사이에 둔 두 지점은 짧은 쪽 거리(1도)로 계산한다")
  void meters_acrossAntimeridian_shortWay() {
    // when
    double distance = GeoDistance.meters(0.0, 179.5, 0.0, -179.5);

    // then
    assertThat(distance).isCloseTo(ONE_DEGREE_M, within(0.1));
  }

  @Test
  @DisplayName("지구 반대편 두 지점 사이는 반 바퀴(π × 6,371,000 = 20,015,086.80m)이고 NaN이 아니다")
  void meters_antipodalPoints_halfCircumference() {
    // when
    double distance = GeoDistance.meters(0.0, 0.0, 0.0, 180.0);

    // then
    assertThat(distance).isCloseTo(20_015_086.80, within(0.1));
  }

  @Test
  @DisplayName("두 지점의 순서를 바꿔도 거리는 같다")
  void meters_swappedPoints_sameDistance() {
    // when
    double forward = GeoDistance.meters(37.4979, 127.0276, 37.5665, 126.9780);
    double backward = GeoDistance.meters(37.5665, 126.9780, 37.4979, 127.0276);

    // then
    assertThat(forward).isCloseTo(backward, within(1e-6));
  }
}
