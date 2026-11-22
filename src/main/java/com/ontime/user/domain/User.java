package com.ontime.user.domain;

import com.ontime.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

  public static final int NICKNAME_MAX_LENGTH = 20;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = NICKNAME_MAX_LENGTH)
  private String nickname;

  private String profileImageUrl;

  private User(String nickname) {
    this.nickname = nickname;
  }

  public static User create(String nickname) {
    return new User(nickname);
  }
}
