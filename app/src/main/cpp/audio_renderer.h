#pragma once

/**
 * audio_renderer.h
 *
 * Oboe-based audio output for DevsonPlayer.
 * Uses a lock-free PCM ring buffer fed by the audio decode thread
 * and drained by Oboe's high-priority callback thread.
 * This completely decouples the decoder from audio output timing.
 */

#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>

//  Lock-free PCM ring buffer 
// Single producer (decode thread), single consumer (Oboe callback thread).
// Capacity must be a power of 2 for mask-based indexing.
class PcmRingBuffer {
public:
    static constexpr int CAPACITY = 1 << 17;  // 128K bytes (~740ms at 44.1kHz stereo S16)
    static constexpr int MASK     = CAPACITY - 1;

    PcmRingBuffer() { memset(buf_, 0, sizeof(buf_)); }

    // Push bytes from producer (decode thread). Returns bytes actually written.
    int push(const uint8_t* data, int bytes) {
        int space   = available_write();
        int to_copy = (bytes < space) ? bytes : space;
        int wi      = write_pos_.load(std::memory_order_relaxed);
        // Simple linear copy (handles wrap with two copies if needed)
        for (int i = 0; i < to_copy; i++) {
            buf_[(wi + i) & MASK] = data[i];
        }
        write_pos_.store(wi + to_copy, std::memory_order_release);
        return to_copy;
    }

    // Pull bytes from consumer (Oboe callback). Returns bytes actually read.
    int pull(uint8_t* out, int bytes) {
        int avail   = available_read();
        int to_copy = (bytes < avail) ? bytes : avail;
        int ri      = read_pos_.load(std::memory_order_relaxed);
        for (int i = 0; i < to_copy; i++) {
            out[i] = buf_[(ri + i) & MASK];
        }
        read_pos_.store(ri + to_copy, std::memory_order_release);
        return to_copy;
    }

    void clear() {
        read_pos_.store(0,  std::memory_order_relaxed);
        write_pos_.store(0, std::memory_order_relaxed);
    }

    int available_read() const {
        return write_pos_.load(std::memory_order_acquire)
             - read_pos_.load(std::memory_order_relaxed);
    }

    int available_write() const {
        return CAPACITY - available_read();
    }

private:
    alignas(64) uint8_t buf_[CAPACITY];
    std::atomic<int> write_pos_{0};
    std::atomic<int> read_pos_{0};
};

//  AudioRenderer 
class AudioRenderer : public oboe::AudioStreamCallback {
public:
    AudioRenderer();
    ~AudioRenderer();

    bool init(int sampleRate, int channelCount);

    void start();
    void pause();
    void stop();
    void flush();   // Clear ring buffer (call on seek)
    void release();

    /**
     * Push decoded PCM (S16 stereo, 44100 Hz) into the ring buffer.
     * Called by the audio decode thread. Non-blocking — drops oldest data on overflow.
     */
    bool pushPcm(const uint8_t* pcmData, int numFrames);

    // Oboe callbacks
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream,
                                          void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStream(int sampleRate, int channelCount);

    oboe::AudioStream* stream_       = nullptr;
    int                sample_rate_  = 44100;
    int                channel_count_ = 2;
    PcmRingBuffer      ring_buffer_;
};