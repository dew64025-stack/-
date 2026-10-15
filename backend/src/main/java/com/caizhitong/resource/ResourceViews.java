package com.caizhitong.resource;

import java.time.*;
import java.util.List;

public final class ResourceViews {
    private ResourceViews() {}
    public record Resource(String id, String name, String type, Integer capacity, String status, String reservationMode, int version) {}
    public record Page(List<Resource> items, String nextCursor, boolean hasMore) {}
    public record Slot(String id, String resourceId, String slotDate, String startTime, String endTime, Integer capacity, String status, int version) {}
    public record AvailabilityItem(Slot slot, List<Integer> availableSeatNos, boolean canReserve, String unavailableReason) {}
    public record Availability(Resource resource, String date, List<AvailabilityItem> slots) {}
    public record SlotAvailability(Slot slot, String reservationMode, List<Integer> availableSeatNos, boolean canReserve, String unavailableReason) {}
    public record Reservation(String id, String slotId, int seatNo, String status, int version, Instant createdAt, Instant updatedAt) {}
    public record ReservationPage(List<Reservation> items, String nextCursor, boolean hasMore) {}
    public record CheckInToken(String checkInToken, Instant expiresAt) {}
    public record CheckedIn(String id, String status, Instant checkedInAt) {}
}
