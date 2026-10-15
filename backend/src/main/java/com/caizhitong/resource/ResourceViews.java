package com.caizhitong.resource;

import java.time.*;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import tools.jackson.databind.JsonNode;

public final class ResourceViews {
    private ResourceViews() {}
    public record BookingRules(String timezone,int pendingHoldMinutes,boolean checkInDuringSlotOnly) {}
    public record Resource(String id,String name,String type,String regionId,Integer capacity,JsonNode equipment,String status,String reservationMode,int version,@JsonUnwrapped ServiceSources.Source sourceFields,BookingRules bookingRules) {}
    public record Page(List<Resource> items, String nextCursor, boolean hasMore) {}
    public record Slot(String id,String resourceId,String slotDate,String startTime,String endTime,Integer capacity,String status,int remaining,int version,@JsonUnwrapped ServiceSources.Source sourceFields) {}
    public record AvailabilityItem(Slot slot, List<Integer> availableSeatNos, boolean canReserve, String unavailableReason) {}
    public record Availability(Resource resource, String date, List<AvailabilityItem> slots) {}
    public record SlotAvailability(Slot slot, String reservationMode, List<Integer> availableSeatNos, boolean canReserve, String unavailableReason) {}
    public record ResourceReference(String id,String name,String type) {}
    public record Reservation(String id,ResourceReference resource,Slot slot,int seatNo,String status,int version,Instant createdAt,@JsonUnwrapped ServiceSources.Source sourceFields,Instant pendingExpiresAt) {}
    public record ReservationPage(List<Reservation> items, String nextCursor, boolean hasMore) {}
    public record CheckInToken(String checkInToken, Instant expiresAt) {}
    public record CheckedIn(@JsonUnwrapped Reservation reservation,Instant checkedInAt) {}
}
