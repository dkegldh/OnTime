package com.ontime.meeting.domain;

/** 위도/경도 두 지점 사이의 거리. 지구를 반지름 6,371km 구로 보고 하버사인 공식으로 계산한다. */
public final class GeoDistance {

  /** 지구 평균 반지름(m). */
  static final double EARTH_RADIUS_M = 6_371_000;

  private GeoDistance() {}

  /** 두 지점 사이의 대원 거리(m). 좌표는 도(degree) 단위다. */
  public static double meters(double lat1, double lng1, double lat2, double lng2) {
    double phi1 = Math.toRadians(lat1);
    double phi2 = Math.toRadians(lat2);
    double dPhi = Math.toRadians(lat2 - lat1);
    double dLambda = Math.toRadians(lng2 - lng1);
    double a =
        Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
            + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
    // 부동소수 오차로 a가 1을 살짝 넘으면 asin이 NaN이 되므로 잘라 준다 (지구 반대편 지점)
    return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(Math.min(1.0, a)));
  }
}
