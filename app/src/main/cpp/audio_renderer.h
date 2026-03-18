#pragma once

#include <oboe/Oboe.h>
#include <cstdint>

// Forward declaration to avoid circular header dependency
class FFmpegDecoder;

class AudioRenderer : public oboe::AudioStreamCallback {
public:
    AudioRenderer();
    ~AudioRenderer();

    bool init(int sampleRate, int channelCount);

    // Wire in the decoder so onAudioReady can pull PCM frames on demand.
    void setDecoder(FFmpegDecoder* decoder);
    void start();
    void pause();
    void stop();
    void release();
    void flush();

    // Writes PCM data to the audio stream. Blocks if buffer is full.
    bool write(const uint8_t* pcmData, int numFrames);

    // Oboe callback for errors/disconnects
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    oboe::AudioStream* stream_ = nullptr;
    int sample_rate_;
    int channel_count_;
    FFmpegDecoder* decoder_ = nullptr;  // Not owned; set via setDecoder()
};