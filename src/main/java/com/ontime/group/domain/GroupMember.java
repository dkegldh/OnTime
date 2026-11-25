package com.ontime.group.domain;

import com.ontime.global.common.BaseTimeEntity;
import com.ontime.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
    name = "group_members",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_group_members_group_user",
            columnNames = {"group_id", "user_id"}),
    // 내 그룹 목록(user_id 조건, group_id 내림차순)용. 유니크 인덱스는 group_id가 앞이라 쓰이지 않는다.
    indexes = @Index(name = "idx_group_members_user_group", columnList = "user_id, group_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupMember extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "group_id", nullable = false)
  private MeetingGroup group;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private GroupRole role;

  private GroupMember(MeetingGroup group, User user, GroupRole role) {
    this.group = group;
    this.user = user;
    this.role = role;
  }

  public static GroupMember owner(MeetingGroup group, User user) {
    return new GroupMember(group, user, GroupRole.OWNER);
  }
}
