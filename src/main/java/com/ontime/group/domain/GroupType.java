package com.ontime.group.domain;

/** 그룹 유형. 일회성 약속은 회차 1개, 정기 모임은 기간 안의 고른 요일마다 회차가 있다. */
public enum GroupType {
  ONE_TIME,
  RECURRING
}
