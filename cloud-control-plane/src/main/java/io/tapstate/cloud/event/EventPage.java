package io.tapstate.cloud.event;

import java.util.List;

/** One page of events plus the cursor for the next page (null when exhausted). */
public record EventPage(List<PlatformEvent> items, String nextCursor) {
}
