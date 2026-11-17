package dev.ngb.booking.gate.application.queue.port;

/** The instance's cached admission watermark; a poll reads here, never a ticket row. */
public interface WatermarkCache {

    long of(String targetId);
}
