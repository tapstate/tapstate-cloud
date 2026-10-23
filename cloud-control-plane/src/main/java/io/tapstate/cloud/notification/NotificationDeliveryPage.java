package io.tapstate.cloud.notification;

import java.util.List;

/** One page of delivery rows plus the counters a pager needs. */
public record NotificationDeliveryPage(
    List<NotificationDeliveryResponse> items,
    int page,
    int size,
    long total
) {
}
