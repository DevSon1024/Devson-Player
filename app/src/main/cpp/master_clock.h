#pragma once

/**
 * master_clock.h
 *
 * Audio-driven master clock for A/V synchronisation.
 * The audio path writes audio_pts_us + the wall-clock timestamp of that write.
 * The video path calls getCurrentTimeUs() to get an interpolated "live" time,
 * eliminating the need to poll the audio thread on every video frame.
 *
 * All members are std::atomic — safe to read/write from the
 * decode thread and render thread without a mutex.
 */

#include <atomic>
#include <cstdint>
#include <chrono>

struct MasterClock {
    // Latest audio PTS in microseconds, updated each decoded audio frame.
    std::atomic<int64_t> audio_pts_us{0};

    // Wall-clock time (microseconds since epoch) when audio_pts_us was last written.
    std::atomic<int64_t> wall_time_us{0};

    // Set to true once the first audio frame has been decoded.
    // Video sync is skipped until audio has started.
    std::atomic<bool> has_audio{false};

    /**
     * Update the clock from the audio decode thread.
     * Call this every time a new audio PTS is known.
     */
    void update(int64_t pts_us) {
        int64_t now = nowUs();
        audio_pts_us.store(pts_us,  std::memory_order_relaxed);
        wall_time_us.store(now,     std::memory_order_relaxed);
        has_audio.store(true,       std::memory_order_relaxed);
    }

    /**
     * Returns an interpolated estimate of the current playback position in
     * microseconds, computed as:
     *     audio_pts_us + (now - wall_time_us)
     *
     * Always call this from the video / UI thread — never from the audio thread.
     */
    int64_t getCurrentTimeUs() const {
        if (!has_audio.load(std::memory_order_relaxed)) return 0;
        int64_t pts  = audio_pts_us.load(std::memory_order_relaxed);
        int64_t wall = wall_time_us.load(std::memory_order_relaxed);
        int64_t elapsed = nowUs() - wall;
        // Clamp elapsed to 0..2s to avoid wild jumps on resume/seek.
        if (elapsed < 0)          elapsed = 0;
        if (elapsed > 2000000LL)  elapsed = 2000000LL;
        return pts + elapsed;
    }

    /** Call on seek to discard stale clock values and re-synchronise. */
    void reset() {
        audio_pts_us.store(0,     std::memory_order_relaxed);
        wall_time_us.store(0,     std::memory_order_relaxed);
        has_audio.store(false,    std::memory_order_relaxed);
    }

private:
    static int64_t nowUs() {
        using namespace std::chrono;
        return duration_cast<microseconds>(
            steady_clock::now().time_since_epoch()).count();
    }
};
