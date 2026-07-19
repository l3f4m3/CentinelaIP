package com.fm.centinelaip;

/** Intercambio en memoria entre el detector y la capa de render. */
final class RoadPerceptionStore {
    private static volatile RoadPerception latest = RoadPerception.EMPTY;

    private RoadPerceptionStore() { }

    static void publish(RoadPerception value) {
        latest = value == null ? RoadPerception.EMPTY : value;
    }

    static RoadPerception latest(int sourceWidth, int sourceHeight) {
        RoadPerception value = latest;
        if (value.sourceWidth != sourceWidth || value.sourceHeight != sourceHeight) {
            return RoadPerception.EMPTY;
        }
        long age = System.nanoTime() - value.timestampNanos;
        return age >= 0L && age <= 1_800_000_000L ? value : RoadPerception.EMPTY;
    }

    static void clear() {
        latest = RoadPerception.EMPTY;
    }
}
