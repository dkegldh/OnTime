package com.ontime.group.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InviteCodeGeneratorTest {

  private final InviteCodeGenerator generator = new InviteCodeGenerator();

  @Test
  @DisplayName("코드는 8자이고 헷갈리는 문자(0, O, 1, I, L)를 쓰지 않는 대문자와 숫자로만 이뤄진다")
  void generate_manyTimes_usesOnlyUnambiguousUppercaseAndDigits() {
    for (int i = 0; i < 1_000; i++) {
      // when
      String code = generator.generate();

      // then
      assertThat(code).matches("[A-HJKMNP-Z2-9]{8}");
    }
  }

  @Test
  @DisplayName("헷갈리는 문자를 뺀 31종 문자를 모두 쓴다")
  void alphabet_hasThirtyOneUnambiguousCharacters() {
    // when
    Set<Character> chars = new HashSet<>();
    for (char c : InviteCodeGenerator.ALPHABET.toCharArray()) {
      chars.add(c);
    }

    // then
    assertThat(chars).hasSize(31).doesNotContain('0', 'O', '1', 'I', 'L');
  }
}
