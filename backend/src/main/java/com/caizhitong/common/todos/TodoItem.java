package com.caizhitong.common.todos;

import java.time.Instant;

/** Aggregated personal to-do row; each source bridge owns its own domain query. */
public record TodoItem(String type, String targetId, String title, Instant dueAt, String status, String action) {}
