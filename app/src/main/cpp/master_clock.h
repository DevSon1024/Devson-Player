#pragma once

/**
 * master_clock.h
 *
 * A lightweight, lock-free shared clock driven by decoded audio PTS.
 * The audio path (processAudioPacket) writes to this; the video path
 * (processVideoPacket) reads from it to decide sleep / drop.
 *
 * All members are std::atomic — safe to read/write from both
 * the decode thread and any future separate render thread without a mutex.
 */

#include <atomic>
#include <cstdint>

struct MasterClock {
    // Latest audio PTS in microseconds, updated each decoded audio frame.
    std::atomic<int64_t> audio_pts_us{0};

    // Set to true once the first audio frame has been decoded.
    // Video sync is skipped until audio has started.
    std::atomic<bool> has_audio{false};

    // Call on seek to discard stale clock values and re-synchronise.
    void reset() {
        audio_pts_us.store(0, std::memory_order_relaxed);
        has_audio.store(false,  std::memory_order_relaxed);
    }
};
