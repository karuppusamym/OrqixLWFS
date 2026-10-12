package com.orqix.compiler;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {
    private BusinessCalendar office(Set<LocalDate> holidays) {
        return new BusinessCalendar(ZoneId.of("America/New_York"), Set.of(1,2,3,4,5), holidays,
                LocalTime.of(9,0), LocalTime.of(17,0));
    }
    @Test void carriesFridayWorkPastWeekendAndHoliday() {
        assertThat(office(Set.of(LocalDate.of(2026,10,12))).deadline(Instant.parse("2026-10-09T20:00:00Z"),2))
                .isEqualTo(Instant.parse("2026-10-13T14:00:00Z"));
    }
    @Test void advancesBeforeOpeningAndAfterClosing() {
        assertThat(office(Set.of()).deadline(Instant.parse("2026-10-13T10:00:00Z"),8))
                .isEqualTo(Instant.parse("2026-10-13T21:00:00Z"));
        assertThat(office(Set.of()).deadline(Instant.parse("2026-10-13T21:00:00Z"),1))
                .isEqualTo(Instant.parse("2026-10-14T14:00:00Z"));
    }
    @Test void observesDaylightSavingAcrossWeekend() {
        assertThat(office(Set.of()).deadline(Instant.parse("2026-10-30T20:00:00Z"),2))
                .isEqualTo(Instant.parse("2026-11-02T15:00:00Z"));
    }
    @Test void rejectsEmptyOrOvernightSchedulesAndUnboundedHours() {
        assertThatThrownBy(() -> new BusinessCalendar(ZoneOffset.UTC,Set.of(),Set.of(),LocalTime.NOON,LocalTime.MAX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessCalendar(ZoneOffset.UTC,Set.of(1),Set.of(),LocalTime.NOON,LocalTime.MIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> office(Set.of()).deadline(Instant.EPOCH,721)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsCalendarsTooSparseToScheduleWithinTheHorizon() {
        var sparse=new BusinessCalendar(ZoneOffset.UTC,Set.of(1),Set.of(),LocalTime.of(9,0),LocalTime.of(9,1));
        assertThatThrownBy(() -> sparse.deadline(Instant.EPOCH,720)).isInstanceOf(IllegalArgumentException.class);
    }
}
