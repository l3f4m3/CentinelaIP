package com.fm.centinelaip;

public final class EventRecord {
    public final String id;
    public final long timestamp;
    public final String cameraName;
    public final String summary;
    public final String imageFile;

    EventRecord(String id, long timestamp, String cameraName, String summary, String imageFile) {
        this.id = id;
        this.timestamp = timestamp;
        this.cameraName = cameraName;
        this.summary = summary;
        this.imageFile = imageFile;
    }
}
