package dev.ngb.booking.catalog.domain.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.booking.catalog.application.calendar.service.CalendarRules;
import dev.ngb.booking.catalog.application.catalog.exception.CatalogValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The rules a wall-clock window follows: how it becomes minutes, and when two windows of a weekday overlap. */
class CalendarRulesTest {

    @Test
    void midnightIsZeroMinutes() {
        assertThat(CalendarRules.minutes("00:00")).isZero();
        assertThat(CalendarRules.text(0)).isEqualTo("00:00");
    }

    @Test
    void theEndOfADayIsOneMinutePastIt() {
        assertThat(CalendarRules.minutes("24:00")).isEqualTo(1440);
        assertThat(CalendarRules.text(1440)).isEqualTo("24:00");
    }

    @Test
    void aWallClockTimeIsItsMinutesFromMidnight() {
        assertThat(CalendarRules.minutes("09:30")).isEqualTo(570);
        assertThat(CalendarRules.text(570)).isEqualTo("09:30");
    }

    @Test
    void aTimeThatIsNotWallClockIsRefused() {
        assertThatThrownBy(() -> CalendarRules.minutes("25:00")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> CalendarRules.minutes("09:60")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> CalendarRules.minutes("9:30")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> CalendarRules.minutes("noon")).isInstanceOf(CatalogValidationException.class);
    }

    @Test
    void aWeekdayOutOfRangeIsRefused() {
        assertThatThrownBy(() -> CalendarRules.workingHour(0, "09:00", "17:00"))
                .isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> CalendarRules.workingHour(8, "09:00", "17:00"))
                .isInstanceOf(CatalogValidationException.class);
    }

    @Test
    void aWindowThatClosesTooEarlyIsRefused() {
        assertThatThrownBy(() -> CalendarRules.workingHour(1, "17:00", "09:00"))
                .isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> CalendarRules.workingHour(1, "09:00", "09:00"))
                .isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> CalendarRules.workingHour(1, "24:00", "24:00"))
                .isInstanceOf(CatalogValidationException.class);
    }

    @Test
    void aWindowMayCloseAtTheEndOfADay() {
        assertThat(CalendarRules.workingHour(7, "09:00", "24:00").closesMinute()).isEqualTo(1440);
    }

    @Test
    void twoWindowsOfOneWeekdayThatOverlapAreRefused() {
        assertThatThrownBy(() -> CalendarRules.weekly(List.of(
                        CalendarRules.workingHour(1, "09:00", "12:00"),
                        CalendarRules.workingHour(1, "11:00", "13:00"))))
                .isInstanceOf(CatalogValidationException.class);
    }

    @Test
    void twoWindowsThatTouchAreNotAnOverlap() {
        List<WorkingHour> hours = CalendarRules.weekly(List.of(
                CalendarRules.workingHour(1, "09:00", "12:00"),
                CalendarRules.workingHour(1, "12:00", "13:00")));

        assertThat(hours).hasSize(2);
    }

    @Test
    void theSameWindowOnTwoWeekdaysDoesNotOverlap() {
        List<WorkingHour> hours = CalendarRules.weekly(List.of(
                CalendarRules.workingHour(1, "09:00", "17:00"),
                CalendarRules.workingHour(2, "09:00", "17:00")));

        assertThat(hours).hasSize(2);
    }
}
