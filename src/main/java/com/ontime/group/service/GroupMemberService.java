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

  /** 초대 코드를 발급한다. 이미 있으면 코드와 만료 시각을 새로 바꿔 이전 코드는 무효가 된다. */
  @Transactional
  public InviteCodeResponse issueInviteCode(Long userId, Long groupId) {
    MeetingGroup group = groupMembershipReader.getMembership(userId, groupId).getGroup();
    String code = newUniqueCode();
    Instant now = Instant.now(clock);
    InviteCode inviteCode =
        inviteCodeRepository
            .findByGroupId(groupId)
            .map(
                existing -> {
                  existing.reissue(code, now);
                  return existing;
                })
            .orElseGet(() -> inviteCodeRepository.save(InviteCode.issue(group, code, now)));
    return InviteCodeResponse.from(inviteCode);
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
      if (isDuplicateMembership(e)) {
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
    throw new IllegalStateException("초대 코드를 " + MAX_CODE_ATTEMPTS + "번 만들었지만 모두 겹쳤습니다.");
  }

  private static boolean isDuplicateMembership(DataIntegrityViolationException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ConstraintViolationException cve
          && GroupMember.UNIQUE_GROUP_USER.equalsIgnoreCase(cve.getConstraintName())) {
        return true;
      }
    }
    return false;
  }
}
