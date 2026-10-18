package com.caizhitong.common.messaging;

import java.util.List;

/** Participant set resolved by the owning module. Callers must not accept member lists from clients. */
public record ConversationContext(String contextType, String contextId, String title, List<Long> participantIds) {}
