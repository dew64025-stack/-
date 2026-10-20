package com.caizhitong.material.files;

import java.time.Instant;
import java.util.List;

public final class FileViews {
    private FileViews() {}
    public record Intent(String uploadId,String uploadUrl,String uploadToken,Instant expiresAt,long maxSizeBytes) {}
    public record Received(String uploadId,boolean received) {}
    public record Completed(String attachmentId,String scanStatus,String jobId) {}
    public record Metadata(String id,String fileName,String mimeType,long sizeBytes,String scanStatus) {}
    public record Download(String url,Instant expiresAt) {}
    public record Version(String id,int versionNo,String attachmentId) {}
    public record Material(String id,String fileName,String mimeType,long sizeBytes,String scanStatus,String status,
            Instant expiresAt,List<String> tags,Version currentVersion,int version) {}
    public record MaterialDetail(String id,String fileName,String mimeType,long sizeBytes,String scanStatus,String status,
            Instant expiresAt,List<String> tags,Version currentVersion,int version,List<Version> versions) {}
    public record Page(List<Material> items,String nextCursor,boolean hasMore) {}
    public record Deleted(boolean deleted,String cleanupStatus) {}
}
