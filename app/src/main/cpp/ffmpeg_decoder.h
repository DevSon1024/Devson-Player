#pragma once

/**
 * ffmpeg_decoder.h
 *
 * Production-grade FFmpeg-based decoder for DevsonPlayer.
 * Uses three separate threads: demux → packet queues → video decode + audio decode.
 * Video frames are passed directly to the DecoderCallback on the video thread.
 * Audio PCM is delivered to the AudioCallback on the audio thread.
 */

#include <functional>
#include <string>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <queue>
#include <cstdint>

#include "master_clock.h"

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/imgutils.h>
}

// Callback: delivers a decoded YUV420P AVFrame and its PTS in microseconds.
// Frame is nullptr to signal EOF.
using DecoderCallback = std::function<void(AVFrame*, int64_t pts_us, int width, int height)>;
using AudioCallback   = std::function<void(uint8_t* pcm_data, int num_frames, int64_t pts_us)>;

//  Thread-safe packet queue 
class PacketQueue {
public:
    explicit PacketQueue(int max_size = 64) : max_size_(max_size), abort_(false) {}

    // Push packet (blocks if full). Returns false if queue was aborted.
    bool push(AVPacket* pkt) {
        std::unique_lock<std::mutex> lk(mutex_);
        cv_push_.wait(lk, [this]{ return (int)queue_.size() < max_size_ || abort_; });
        if (abort_) { av_packet_free(&pkt); return false; }
        queue_.push(pkt);
        cv_pop_.notify_one();
        return true;
    }

    // Pop packet (blocks until one is available). Returns nullptr on abort.
    AVPacket* pop() {
        std::unique_lock<std::mutex> lk(mutex_);
        cv_pop_.wait(lk, [this]{ return !queue_.empty() || abort_; });
        if (queue_.empty()) return nullptr;
        AVPacket* pkt = queue_.front();
        queue_.pop();
        cv_push_.notify_one();
        return pkt;
    }

    // Drain all packets (call before seek / release).
    void flush() {
        std::unique_lock<std::mutex> lk(mutex_);
        while (!queue_.empty()) {
            AVPacket* p = queue_.front(); queue_.pop();
            av_packet_free(&p);
        }
        cv_push_.notify_all();
    }

    void abort() {
        std::unique_lock<std::mutex> lk(mutex_);
        abort_ = true;
        cv_push_.notify_all();
        cv_pop_.notify_all();
    }

    void reset() {
        flush();
        std::unique_lock<std::mutex> lk(mutex_);
        abort_ = false;
    }

    int size() {
        std::unique_lock<std::mutex> lk(mutex_);
        return (int)queue_.size();
    }

private:
    std::queue<AVPacket*>   queue_;
    std::mutex              mutex_;
    std::condition_variable cv_push_;
    std::condition_variable cv_pop_;
    int                     max_size_;
    bool                    abort_;
};

//  FFmpegDecoder 
class FFmpegDecoder {
public:
    FFmpegDecoder();
    ~FFmpegDecoder();

    bool init(const char* path, DecoderCallback video_callback, AudioCallback audio_callback);
    void setAudioStream(int stream_index);
    void setMasterClock(MasterClock* clock);

    void startDecoding();
    void pause();
    void resume();
    void seekTo(int64_t position_us);
    void setSpeed(float speed);
    void release();

    // Returns interpolated playback position from master clock (microseconds).
    int64_t getCurrentPtsUs() const;

    int     getWidth()      const;
    int     getHeight()     const;
    int64_t getDurationUs() const;
    bool    isEof()         const;

private:
    //  init helpers 
    bool openVideoCodec();
    bool openAudioCodec();
    void setupSwsContext();

    //  thread entry points 
    void demuxLoop();
    void videoDecodeLoop();
    void audioDecodeLoop();

    //  internal audio helpers 
    bool initSwrContext(AVFrame* first_frame);
    void processAudioFrame(AVFrame* frame);

    //  format / codec context 
    AVFormatContext*  fmt_ctx_     = nullptr;
    AVCodecContext*   codec_ctx_   = nullptr;   // video
    AVCodecContext*   audio_codec_ctx_ = nullptr;
    SwsContext*       sws_ctx_     = nullptr;
    SwrContext*       swr_ctx_     = nullptr;

    int video_stream_idx_ = -1;
    int audio_stream_idx_ = -1;

    //  reusable frame & packet 
    AVFrame*  frame_    = nullptr;   // video decode scratch
    AVFrame*  sw_frame_ = nullptr;   // pixel-converted scratch
    AVFrame*  audio_frame_ = nullptr;

    uint8_t*  audio_out_buffer_ = nullptr;

    int     width_  = 0;
    int     height_ = 0;
    int64_t duration_us_ = 0;

    //  queues 
    PacketQueue video_pkt_queue_{32};
    PacketQueue audio_pkt_queue_{64};

    //  threads 
    std::thread demux_thread_;
    std::thread video_thread_;
    std::thread audio_thread_;

    //  sync primitives 
    std::mutex              mutex_;           // guards seek / codec switches
    std::condition_variable cv_;

    //  atomic control flags 
    std::atomic<bool>    playing_{false};
    std::atomic<bool>    paused_{false};
    std::atomic<bool>    eof_{false};
    std::atomic<bool>    seek_requested_{false};
    std::atomic<int64_t> seek_target_us_{0};
    std::atomic<float>   playback_speed_{1.0f};

    //  callbacks & clock 
    DecoderCallback callback_;
    AudioCallback   audio_callback_;
    MasterClock*    clock_ = nullptr;
};
