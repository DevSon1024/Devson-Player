/**
 * audio_renderer.cpp
 *
 * Oboe-backed audio output with a lock-free PCM ring buffer.
 * The decode thread calls pushPcm() — the Oboe callback drains it.
 * No synchronisation between decode and output threads needed.
 */

#include "audio_renderer.h"
#include <android/log.h>
#include <cstring>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AudioRenderer", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "AudioRenderer", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "AudioRenderer", __VA_ARGS__)

AudioRenderer::AudioRenderer()  = default;
AudioRenderer::~AudioRenderer() { release(); }

//  init 

bool AudioRenderer::init(int sampleRate, int channelCount) {
    sample_rate_   = sampleRate;
    channel_count_ = channelCount;
    return openStream(sampleRate, channelCount);
}

bool AudioRenderer::openStream(int sampleRate, int channelCount) {
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_ = nullptr;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::I16)
           ->setChannelCount(channelCount)
           ->setSampleRate(sampleRate)
           ->setCallback(this);   // callback-driven: onAudioReady() drains ring buffer

    oboe::Result result = builder.openStream(&stream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
        stream_ = nullptr;
        return false;
    }

    // Double burst size to reduce underruns
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 4);
    LOGI("Oboe stream opened: %dHz %dch burst=%d",
         sampleRate, channelCount, stream_->getFramesPerBurst());
    return true;
}

//  start / pause / stop / flush / release 

void AudioRenderer::start() {
    if (stream_ && stream_->getState() != oboe::StreamState::Started) {
        stream_->requestStart();
    }
}

void AudioRenderer::pause() {
    if (stream_ && stream_->getState() == oboe::StreamState::Started) {
        stream_->requestPause();
    }
}

void AudioRenderer::stop() {
    if (stream_) stream_->requestStop();
}

void AudioRenderer::flush() {
    // Always clear the ring buffer immediately (non-blocking)
    ring_buffer_.clear();
    // Only send requestFlush() when stream is in a state that allows it
    // (Oboe/AAudio only allows flush in PAUSED, OPEN, or STOPPED states)
    if (stream_) {
        auto state = stream_->getState();
        if (state == oboe::StreamState::Paused  ||
            state == oboe::StreamState::Open     ||
            state == oboe::StreamState::Stopped) {
            stream_->requestFlush();
        }
        // If STARTING or STARTED, ring_buffer_.clear() already handled it —
        // the Oboe callback will naturally return silence for cleared buffer.
    }
}

void AudioRenderer::release() {
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_ = nullptr;
    }
}

//  pushPcm (called from audio decode thread) 

bool AudioRenderer::pushPcm(const uint8_t* pcmData, int numFrames) {
    if (!pcmData || numFrames <= 0) return false;
    // Each frame is 2 channels × 2 bytes = 4 bytes
    int bytes   = numFrames * channel_count_ * 2;
    int written = ring_buffer_.push(pcmData, bytes);
    if (written < bytes) {
        LOGW("Ring buffer overflow: dropped %d bytes", bytes - written);
    }
    return written > 0;
}

//  onAudioReady (called from Oboe high-priority thread) 

oboe::DataCallbackResult AudioRenderer::onAudioReady(oboe::AudioStream* /*stream*/,
                                                      void* audioData,
                                                      int32_t numFrames) {
    int bytesNeeded = numFrames * channel_count_ * 2; // S16 stereo
    auto* out       = static_cast<uint8_t*>(audioData);

    int got = ring_buffer_.pull(out, bytesNeeded);
    if (got < bytesNeeded) {
        // Underrun — silence-fill the remainder
        memset(out + got, 0, bytesNeeded - got);
    }
    return oboe::DataCallbackResult::Continue;
}

//  onErrorAfterClose 

void AudioRenderer::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    LOGE("Oboe stream error: %s — attempting restart", oboe::convertToText(error));
    // Attempt to reopen the stream (covers BT headset connect/disconnect etc.)
    if (openStream(sample_rate_, channel_count_)) {
        start();
        LOGI("Oboe stream restarted successfully");
    }
}