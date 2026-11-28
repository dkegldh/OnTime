package com.ontime.group.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@link Weekdays} ↔ {@code days_of_week INT} 비트마스크. 일회성 그룹은 null. */
@Converter
public class WeekdaysConverter implements AttributeConverter<Weekdays, Integer> {

  @Override
  public Integer convertToDatabaseColumn(Weekdays weekdays) {
    return weekdays == null ? null : weekdays.toBits();
  }

  @Override
  public Weekdays convertToEntityAttribute(Integer bits) {
    return bits == null ? null : Weekdays.fromBits(bits);
  }
}
