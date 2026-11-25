package com.ontime.group.service;

import com.ontime.group.domain.InviteCode;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 초대 코드 문자열을 만든다. 헷갈리기 쉬운 문자(0, O, 1, I, L)는 쓰지 않는다. */
@Component
public class InviteCodeGenerator {

  static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

  private final SecureRandom random = new SecureRandom();

  public String generate() {
    StringBuilder code = new StringBuilder(InviteCode.CODE_LENGTH);
    for (int i = 0; i < InviteCode.CODE_LENGTH; i++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }
}
