package io.tapstate.cloud.event;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/** Quiet-window evaluation in the policy's IANA timezone. */
public final class QuietHours {

    private QuietHours() {
    }

    public static boolean isQuiet(AlertPolicy policy, Instant now) {
        String start = policy.getQuietHoursStart();
        String end = policy.getQuietHoursEnd();
        if (start == null || start.isBlank() || end == null || end.isBlank()) {
            return false;
        }
        ZoneId zone = ZoneId.of(policy.getTimezone() == null || policy.getTimezone().isBlank()
            ? "UTC" : policy.getTimezone());
        LocalTime localNow = LocalTime.ofInstant(now, zone);
        LocalTime localStart = LocalTime.parse(start);
        LocalTime localEnd = LocalTime.parse(end);
        if (localStart.equals(localEnd)) {
            return false;
        }
        if (localStart.isBefore(localEnd)) {
            return !localNow.isBefore(localStart) && localNow.isBefore(localEnd);
        }
        return !localNow.isBefore(localStart) || localNow.isBefore(localEnd);
    }
}
