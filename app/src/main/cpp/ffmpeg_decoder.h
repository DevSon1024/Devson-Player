#pragma once

#include <functional>
#include <string>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
}

// Callback: delivers a decoded YUV420P AVFrame and its PTS in microseconds.
// Frame is nullptr to signal EOF.
using DecoderCallback = std::function<void(AVFrame*, int64_t pts_us, int width, int height)>;

class FFmpegDecoder {
public:
    FFmpegDecoder();
    ~FFmpegDecoder();

    bool  init(const char* path, DecoderCallback callback);
    void  startDecoding();
    void  pause();
    void  resume();
    
    void  seekTo(int64_t position_us);
    void  setSpeed(float speed);
    void  release();

    int     getWidth()      const;
    int     getHeight()     const;
    int64_t getDurationUs() const;
    bool    isEof()         const;

private:
    bool openVideoCodec();
    void setupSwsContext();
    void decodeLoop();
    void processVideoPacket();

    AVFormatContext*  fmt_ctx_;
    AVCodecContext*   codec_ctx_;
    SwsContext*       sws_ctx_;

    int video_stream_idx_;
    int audio_stream_idx_;

    AVFrame*    frame_;
    AVFrame*    sw_frame_;
    AVPacket*   packet_;

    int     width_, height_;
    int64_t duration_us_;
    bool paused_ = false;

    std::atomic<bool>    eof_;
    std::atomic<bool>    seek_requested_;
    std::atomic<int64_t> seek_target_us_;
    std::atomic<bool>    playing_;
    std::atomic<float>   playback_speed_;

    std::thread              decode_thread_;
    std::mutex               mutex_;
    std::condition_variable  cv_;

    DecoderCallback callback_;
};
