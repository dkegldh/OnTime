package com.ontime.group.dto;

import com.ontime.group.domain.GroupRole;
import java.time.Instant;

/**
 * 멤버 목록의 한 항목.
 *
 * @param memberId 멤버 목록 커서로 쓰는 id
 * @param joinedAt 그룹에 참여한 시각
 */
public record GroupMemberSummary(
    Long memberId,
    Long userId,
    String nickname,
    String profileImageUrl,
    GroupRole role,
    Instant joinedAt) {}
