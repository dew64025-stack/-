package com.caizhitong.community;

/**
 * Account-deletion erasure for anonymous content. The production implementation calls a
 * SQL SECURITY DEFINER routine because the business account must never SELECT the identity mapping.
 */
public interface AnonymousErasure {
    /** Erases both legacy user_id mappings and HMAC-only mappings; returns the mapping rows removed. */
    int erase(long userId, String userSearchHex);
}
