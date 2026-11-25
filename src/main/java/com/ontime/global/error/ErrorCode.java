package com.ontime.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
  NOT_GROUP_MEMBER(HttpStatus.FORBIDDEN, "그룹 멤버만 할 수 있어요."),
  OWNER_ONLY(HttpStatus.FORBIDDEN, "방장만 할 수 있어요."),
  INVALID_INVITE_CODE(HttpStatus.NOT_FOUND, "초대 코드를 찾을 수 없어요."),
  ARRIVE_TOO_FAR(HttpStatus.BAD_REQUEST, "약속 장소에서 너무 멀리 있어요."),
  INVALID_STATE(HttpStatus.CONFLICT, "지금은 할 수 없는 동작이에요."),
  MEETING_NOT_OPEN(HttpStatus.CONFLICT, "아직 출발/도착을 누를 수 없는 시간이에요."),
  EXCUSE_DEADLINE_PASSED(HttpStatus.CONFLICT, "약속 24시간 전까지만 빠질 수 있어요."),
  ALREADY_MEMBER(HttpStatus.CONFLICT, "이미 참여한 그룹이에요."),
  GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "그룹을 찾을 수 없어요."),
  MEETING_TIME_IN_PAST(HttpStatus.BAD_REQUEST, "약속 시간은 지금 이후로 정해 주세요."),
  CONCURRENT_UPDATE(HttpStatus.CONFLICT, "다른 요청과 동시에 바뀌었어요. 다시 시도해 주세요."),
  INVITE_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "만료된 초대 코드예요. 새 코드를 받아 주세요."),
  OWNER_CANNOT_LEAVE(HttpStatus.CONFLICT, "방장은 그룹을 나갈 수 없어요. 그룹을 삭제해 주세요.");

  private final HttpStatus status;
  private final String message;
}
