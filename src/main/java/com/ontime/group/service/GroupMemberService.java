package com.ontime.group.service;

import com.ontime.global.common.CursorResponse;
import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.group.domain.GroupMember;
import com.ontime.group.domain.InviteCode;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.dto.GroupMemberSummary;
import com.ontime.group.dto.InviteCodeResponse;
import com.ontime.group.dto.MeetingGroupResponse;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.InviteCodeRepository;
import com.ontime.user.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 초대 코드, 참여, 나가기, 멤버 목록. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupMemberService {

  /** 이미 쓰는 코드와 겹칠 때 다시 만드는 횟수. 31^8 가지라 실제로는 거의 한 번에 끝난다. */
  static final int MAX_CODE_ATTEMPTS = 5;

  private final InviteCodeRepository inviteCodeRepository;
  private final GroupMemberRepository groupMemberRepository;
  private final GroupMembershipReader groupMembershipReader;
  private final InviteCodeGenerator inviteCodeGenerator;
  private final UserService userService;
  private final Clock clock;

  /** 초대 코드를 발급한다. 방장만 할 수 있다. 이미 있으면 코드와 만료 시각을 새로 바꿔 이전 코드는 무효가 된다. */
  @Transactional
  public InviteCodeResponse issueInviteCode(Long userId, Long groupId) {
    GroupMember membership = groupMembershipReader.getMembership(userId, groupId);
    membership.validateOwner();
    MeetingGroup group = membership.getGroup();
    String code = newUniqueCode();
    Instant now = Instant.now(clock);
    try {
      InviteCode inviteCode =
          inviteCodeRepository
              .findByGroupId(groupId)
              .map(
                  existing -> {
                    existing.reissue(code, now);
                    return existing;
                  })
              .orElseGet(() -> inviteCodeRepository.save(InviteCode.issue(group, code, now)));
      // 제약 위반을 여기서 잡으려고 커밋 전에 flush 한다. (재발급의 버전 충돌은 GlobalExceptionHandler 가 409로 바꾼다)
      inviteCodeRepository.flush();
      return InviteCodeResponse.from(inviteCode);
    } catch (DataIntegrityViolationException e) {
      // 같은 그룹 첫 발급이 동시에 왔거나, 다른 그룹과 동시에 같은 코드를 만든 경우. 다시 시도하면 된다.
      if (violates(e, InviteCode.UNIQUE_GROUP) || violates(e, InviteCode.UNIQUE_CODE)) {
        throw new BusinessException(ErrorCode.CONCURRENT_UPDATE);
      }
      throw e;
    }
  }

  /**
   * 초대 코드로 그룹에 참여한다.
   *
   * <p>같은 사용자의 요청이 동시에 오면 둘 다 "아직 멤버 아님"을 볼 수 있으므로, 최종 방어는 (group_id, user_id) 유니크
   * 제약이다. 그 위반도 {@code ALREADY_MEMBER} 로 응답한다.
   */
  @Transactional
  public MeetingGroupResponse join(Long userId, String code) {
    InviteCode inviteCode =
        inviteCodeRepository
            .findWithGroupByCode(code)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INVITE_CODE));
    MeetingGroup group = inviteCode.getGroup();
    if (group.getDeletedAt() != null) {
      throw new BusinessException(ErrorCode.INVALID_INVITE_CODE);
    }
    inviteCode.validateUsableAt(Instant.now(clock));
    if (groupMemberRepository.findByGroupIdAndUserId(group.getId(), userId).isPresent()) {
      throw new BusinessException(ErrorCode.ALREADY_MEMBER);
    }
    GroupMember member = GroupMember.member(group, userService.getReference(userId));
    try {
      groupMemberRepository.saveAndFlush(member);
    } catch (DataIntegrityViolationException e) {
      if (violates(e, GroupMember.UNIQUE_GROUP_USER)) {
        throw new BusinessException(ErrorCode.ALREADY_MEMBER);
      }
      throw e;
    }
    return MeetingGroupResponse.of(member);
  }

  /** 그룹을 나간다. 멤버 행을 지우므로 다시 참여할 수 있다. 방장은 나갈 수 없다. */
  @Transactional
  public void leave(Long userId, Long groupId) {
    GroupMember membership = groupMembershipReader.getMembership(userId, groupId);
    membership.validateCanLeave();
    groupMemberRepository.delete(membership);
  }

  public CursorResponse<GroupMemberSummary> listMembers(
      Long userId, Long groupId, Long cursor, int size) {
    groupMembershipReader.getMembership(userId, groupId);
    List<GroupMemberSummary> fetched =
        groupMemberRepository.findPageByGroupId(groupId, cursor, Limit.of(size + 1));
    return CursorResponse.of(fetched, size, GroupMemberSummary::memberId);
  }

  private String newUniqueCode() {
    for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
      String code = inviteCodeGenerator.generate();
      if (!inviteCodeRepository.existsByCode(code)) {
        return code;
      }
    }
    // 31^8(약 8,500억) 가지라 5번 연속으로 겹치는 일은 사실상 없다. 일어났다면 난수 생성이 고장 난 것이므로
    // 사용자 에러(BusinessException)가 아닌 서버 오류로 남긴다.
    throw new IllegalStateException("초대 코드를 " + MAX_CODE_ATTEMPTS + "번 만들었지만 모두 겹쳤습니다.");
  }

  /** 이 예외가 {@code constraintName} 제약 위반 때문인지 본다. */
  private static boolean violates(DataIntegrityViolationException e, String constraintName) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ConstraintViolationException cve
          && constraintName.equalsIgnoreCase(cve.getConstraintName())) {
        return true;
      }
    }
    return false;
  }
}
