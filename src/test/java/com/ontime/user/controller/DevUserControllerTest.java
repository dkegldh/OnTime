package com.ontime.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ontime.TestcontainersConfiguration;
import com.ontime.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DevUserControllerTest {

  private static final String NICKNAME_20 = "가나다라마바사아자차카타파하가나다라마바";
  private static final String NICKNAME_21 = NICKNAME_20 + "사";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;

  @AfterEach
  void tearDown() {
    userRepository.deleteAllInBatch();
  }

  private ResultActions createUser(String nickname) throws Exception {
    return mockMvc.perform(
        post("/api/v1/dev/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"" + nickname + "\"}"));
  }

  @Test
  @DisplayName("사용자를 생성하면 201과 id, 닉네임을 돌려주고 목록에 나온다")
  void create_validNickname_returnsCreatedAndAppearsInList() throws Exception {
    // given
    String nickname = "지각대장";

    // when
    ResultActions result = createUser(nickname);

    // then
    result
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNumber())
        .andExpect(jsonPath("$.nickname").value("지각대장"));
    mockMvc
        .perform(get("/api/v1/dev/users"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].nickname").value("지각대장"));
  }

  @Test
  @DisplayName("닉네임이 최대 길이인 20자면 생성된다")
  void create_nicknameAtMaxLength_returnsCreated() throws Exception {
    // given
    assertThat(NICKNAME_20).hasSize(20);

    // when
    ResultActions result = createUser(NICKNAME_20);

    // then
    result.andExpect(status().isCreated()).andExpect(jsonPath("$.nickname").value(NICKNAME_20));
    assertThat(userRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("닉네임이 21자면 VALIDATION_FAILED로 거절되고 사용자가 생기지 않는다")
  void create_nicknameOverMaxLength_rejectedWithValidationFailed() throws Exception {
    // given
    assertThat(NICKNAME_21).hasSize(21);

    // when
    ResultActions result = createUser(NICKNAME_21);

    // then
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(userRepository.count()).isZero();
  }

  @Test
  @DisplayName("닉네임이 공백뿐이면 VALIDATION_FAILED로 거절되고 사용자가 생기지 않는다")
  void create_blankNickname_rejectedWithValidationFailed() throws Exception {
    // given
    String nickname = "   ";

    // when
    ResultActions result = createUser(nickname);

    // then
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(userRepository.count()).isZero();
  }
}
