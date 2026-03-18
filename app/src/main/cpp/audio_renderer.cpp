#include "audio_renderer.h"
#include "ffmpeg_decoder.h"
#include <android/log.h>
#include <cstring>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AudioRenderer", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "AudioRenderer", __VA_ARGS__)

AudioRenderer::AudioRenderer() {}
AudioRenderer::~AudioRenderer() { release(); }

bool AudioRenderer::init(int sampleRate, int channelCount) {
    sample_rate_ = sampleRate;
    channel_count_ = channelCount;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Shared);
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setChannelCount(channelCount);
    builder.setSampleRate(sampleRate);
    builder.setCallback(nullptr); // We will use blocking writes instead of a callback loop

    oboe::Result result = builder.openStream(&stream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to create Oboe stream. Error: %s", oboe::convertToText(result));
        return false;
    }

    // Set buffer size to help prevent underruns
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
    LOGI("Oboe AudioStream created successfully");
    return true;
}

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
    if (stream_) stream_->requestFlush();
}

void AudioRenderer::release() {
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_ = nullptr;
    }
}

bool AudioRenderer::write(const uint8_t* pcmData, int numFrames) {
    if (!stream_) return false;

    // Write data to stream. If the stream buffer is full, this will BLOCK the thread.
    // This blocking behavior natively throttles your FFmpeg decode loop to 44.1kHz!
    auto result = stream_->write(pcmData, numFrames, oboe::kUnspecified); // kUnspecified = infinite timeout
    return result.value() == numFrames;
}

void AudioRenderer::setDecoder(FFmpegDecoder* decoder) {
    decoder_ = decoder;
}

oboe::DataCallbackResult AudioRenderer::onAudioReady(oboe::AudioStream* /*audioStream*/,
                                                     void* audioData,
                                                     int32_t numFrames) {
    // Oboe gives us a buffer of numFrames stereo S16 frames.
    // bytesNeeded = frames * 2 channels * 2 bytes/sample
    const int bytesNeeded = numFrames * 2 * 2;
    auto* outputBuffer = static_cast<uint8_t*>(audioData);

    if (!decoder_) {
        // No decoder attached — output silence
        memset(outputBuffer, 0, bytesNeeded);
        return oboe::DataCallbackResult::Continue;
    }

    uint8_t* decodedData = nullptr;
    int bytesDecoded = decoder_->decodeAudio(&decodedData);

    if (bytesDecoded > 0 && decodedData != nullptr) {
        // Copy what we have (never exceed the output buffer)
        int bytesToCopy = (bytesDecoded < bytesNeeded) ? bytesDecoded : bytesNeeded;
        memcpy(outputBuffer, decodedData, bytesToCopy);

        // Silence-fill any remainder to avoid buzzing from uninitialized memory
        if (bytesToCopy < bytesNeeded) {
            memset(outputBuffer + bytesToCopy, 0, bytesNeeded - bytesToCopy);
        }
    } else {
        // No audio available (decoder not ready, seek flush, or EOF) — output silence
        memset(outputBuffer, 0, bytesNeeded);
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioRenderer::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) {
    LOGE("Oboe stream error: %s", oboe::convertToText(error));
}