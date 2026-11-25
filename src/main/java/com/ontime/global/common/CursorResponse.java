package com.ontime.global.common;

import java.util.List;
import java.util.function.Function;

/**
 * 커서 페이지네이션 응답. {@code size + 1} 개를 조회한 결과로 다음 페이지가 있는지 판단한다.
 *
 * @param nextCursor 다음 페이지 요청에 쓸 커서 (마지막 항목의 id). 다음 페이지가 없으면 null
 */
public record CursorResponse<T>(List<T> items, Long nextCursor, boolean hasNext) {

  public static <T> CursorResponse<T> of(List<T> fetched, int size, Function<T, Long> idOf) {
    boolean hasNext = fetched.size() > size;
    List<T> items = hasNext ? fetched.subList(0, size) : fetched;
    Long nextCursor = hasNext ? idOf.apply(items.get(items.size() - 1)) : null;
    return new CursorResponse<>(List.copyOf(items), nextCursor, hasNext);
  }
}
