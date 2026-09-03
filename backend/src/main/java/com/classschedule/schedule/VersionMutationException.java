package com.classschedule.schedule;

public class VersionMutationException extends IllegalArgumentException {
    private final String code;
    private final long versionId;
    private final long currentRevision;

    public VersionMutationException(
            String code, long versionId, long currentRevision, String message) {
        super(message);
        this.code = code;
        this.versionId = versionId;
        this.currentRevision = currentRevision;
    }

    public String code() {
        return code;
    }

    public long versionId() {
        return versionId;
    }

    public long currentRevision() {
        return currentRevision;
    }
}
