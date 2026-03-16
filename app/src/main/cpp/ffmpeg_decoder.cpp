/**
 * ffmpeg_decoder.cpp
 *
 * FFmpeg-based software video decoder for DevsonPlayer.
 * Handles demuxing all major container formats and decoding video frames
 * to YUV420P output for OpenGL ES rendering.
 *
 * Supported containers: MKV, MP4, AVI, MOV, FLV, WEBM, TS
 * Supported codecs:     H.264, H.265/HEVC (8+10bit), VP9, AV1, MPEG-4, VC-1
 */

#include "ffmpeg_decoder.h"

#include <android/log.h>
#include <cstring>
#include <stdexcept>

#define LOG_TAG "FFmpegDecoder"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>
}

//  FFmpegDecoder implementation 

FFmpegDecoder::FFmpegDecoder()
    : fmt_ctx_(nullptr), codec_ctx_(nullptr), sws_ctx_(nullptr),
      video_stream_idx_(-1), audio_stream_idx_(-1),
      frame_(nullptr), sw_frame_(nullptr), packet_(nullptr),
      width_(0), height_(0), duration_us_(0),
      eof_(false), seek_requested_(false), seek_target_us_(0),
      playing_(false), playback_speed_(1.0f) {
    LOGI("FFmpegDecoder created");
}

FFmpegDecoder::~FFmpegDecoder() {
    release();
}

bool FFmpegDecoder::init(const char* path, DecoderCallback callback) {
    std::lock_guard<std::mutex> lock(mutex_);
    callback_ = callback;

    // Allocate packet and frames
    packet_ = av_packet_alloc();
    frame_  = av_frame_alloc();
    sw_frame_ = av_frame_alloc();
    if (!packet_ || !frame_ || !sw_frame_) {
        LOGE("Failed to allocate AVPacket/AVFrame");
        return false;
    }

    // Open input
    fmt_ctx_ = avformat_alloc_context();
    int ret = avformat_open_input(&fmt_ctx_, path, nullptr, nullptr);
    if (ret < 0) {
        char errbuf[256];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("avformat_open_input failed: %s", errbuf);
        return false;
    }

    // Retrieve stream info
    ret = avformat_find_stream_info(fmt_ctx_, nullptr);
    if (ret < 0) {
        LOGE("avformat_find_stream_info failed");
        return false;
    }

    duration_us_ = fmt_ctx_->duration; // in AV_TIME_BASE units (microseconds)

    // Find best video stream
    video_stream_idx_ = av_find_best_stream(fmt_ctx_, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    audio_stream_idx_ = av_find_best_stream(fmt_ctx_, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);

    if (video_stream_idx_ < 0) {
        LOGE("No video stream found");
        return false;
    }

    LOGI("Video stream idx=%d  Audio stream idx=%d", video_stream_idx_, audio_stream_idx_);

    if (!openVideoCodec()) return false;

    LOGI("Decoder initialized: %dx%d  duration=%.2fs  codec=%s",
         width_, height_,
         duration_us_ / 1e6,
         avcodec_get_name(codec_ctx_->codec_id));

    return true;
}

bool FFmpegDecoder::openVideoCodec() {
    AVStream* stream = fmt_ctx_->streams[video_stream_idx_];
    const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) {
        LOGE("No decoder found for codec_id=%d", stream->codecpar->codec_id);
        return false;
    }

    codec_ctx_ = avcodec_alloc_context3(codec);
    if (!codec_ctx_) {
        LOGE("Failed to allocate codec context");
        return false;
    }

    int ret = avcodec_parameters_to_context(codec_ctx_, stream->codecpar);
    if (ret < 0) {
        LOGE("avcodec_parameters_to_context failed");
        return false;
    }

    // Enable multi-thread decoding (up to 4 threads)
    codec_ctx_->thread_count = 8;
    codec_ctx_->thread_type  = FF_THREAD_FRAME | FF_THREAD_SLICE;

    // Open codec
    ret = avcodec_open2(codec_ctx_, codec, nullptr);
    if (ret < 0) {
        char errbuf[256];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("avcodec_open2 failed: %s", errbuf);
        return false;
    }

    width_  = codec_ctx_->width;
    height_ = codec_ctx_->height;

    LOGI("Codec: %s  %dx%d  pix_fmt=%s  profile=%d  level=%d",
         codec->name, width_, height_,
         av_get_pix_fmt_name(codec_ctx_->pix_fmt),
         codec_ctx_->profile,
         codec_ctx_->level);

    // Build SwsContext: converts any input pixel format → YUV420P for OpenGL
    setupSwsContext();
    return true;
}

void FFmpegDecoder::setupSwsContext() {
    if (sws_ctx_) {
        sws_freeContext(sws_ctx_);
        sws_ctx_ = nullptr;
    }

    // Only create SWS if we need format conversion
    AVPixelFormat src_fmt = codec_ctx_->pix_fmt;
    AVPixelFormat dst_fmt = AV_PIX_FMT_YUV420P;

    if (src_fmt == dst_fmt) {
        LOGI("No SWS needed — already YUV420P");
        return;
    }

    sws_ctx_ = sws_getContext(
        width_, height_, src_fmt,
        width_, height_, dst_fmt,
        SWS_BILINEAR, nullptr, nullptr, nullptr
    );

    if (!sws_ctx_) {
        LOGE("sws_getContext failed (src_fmt=%d)", src_fmt);
    } else {
        LOGI("SWS: %s → YUV420P (%dx%d)", av_get_pix_fmt_name(src_fmt), width_, height_);
        
        // ADD THIS LINE: Free previous buffer before re-allocating
        av_frame_unref(sw_frame_); 
        
        // Prepare sw_frame buffer
        sw_frame_->format = AV_PIX_FMT_YUV420P;
        sw_frame_->width  = width_;
        sw_frame_->height = height_;
        av_frame_get_buffer(sw_frame_, 32);
    }

    // Prepare sw_frame buffer
    sw_frame_->format = AV_PIX_FMT_YUV420P;
    sw_frame_->width  = width_;
    sw_frame_->height = height_;
    av_frame_get_buffer(sw_frame_, 32);
}

void FFmpegDecoder::startDecoding() {
    playing_ = true;
    eof_ = false;
    decode_thread_ = std::thread(&FFmpegDecoder::decodeLoop, this);
}

void FFmpegDecoder::pause() {
    std::lock_guard<std::mutex> lock(mutex_);
    paused_ = true;
}

void FFmpegDecoder::resume() {
    std::lock_guard<std::mutex> lock(mutex_);
    paused_ = false;
    cv_.notify_all(); // Wake up the thread
}

void FFmpegDecoder::seekTo(int64_t position_us) {
    std::lock_guard<std::mutex> lock(mutex_);
    seek_requested_ = true;
    seek_target_us_ = position_us;
    eof_ = false;
    cv_.notify_all();
}

void FFmpegDecoder::setSpeed(float speed) {
    playback_speed_ = speed;
}

void FFmpegDecoder::release() {
    playing_ = false;
    cv_.notify_all();

    if (decode_thread_.joinable()) {
        decode_thread_.join();
    }

    if (sws_ctx_) { sws_freeContext(sws_ctx_); sws_ctx_ = nullptr; }
    if (packet_)  { av_packet_free(&packet_); }
    if (frame_)   { av_frame_free(&frame_); }
    if (sw_frame_){ av_frame_free(&sw_frame_); }
    if (codec_ctx_){ avcodec_free_context(&codec_ctx_); }
    if (fmt_ctx_) { avformat_close_input(&fmt_ctx_); }

    LOGI("FFmpegDecoder released");
}

void FFmpegDecoder::decodeLoop() {
    LOGI("Decode thread started");

    while (playing_) {
        std::unique_lock<std::mutex> lock(mutex_);

        cv_.wait(lock, [this]() { return !paused_ || !playing_ || seek_requested_; });

        if (!playing_) break;

        // Handle seek
        if (seek_requested_) {
            seek_requested_ = false;
            int64_t seek_ts = av_rescale_q(seek_target_us_,
                                            AV_TIME_BASE_Q,
                                            fmt_ctx_->streams[video_stream_idx_]->time_base);
            int ret = av_seek_frame(fmt_ctx_, video_stream_idx_, seek_ts, AVSEEK_FLAG_BACKWARD);
            if (ret < 0) LOGW("Seek failed");
            avcodec_flush_buffers(codec_ctx_);
        }

        lock.unlock();

        // Read a packet from the container
        int ret = av_read_frame(fmt_ctx_, packet_);
        if (ret == AVERROR_EOF) {
            eof_ = true;
            LOGI("EOF reached");
            if (callback_) callback_(nullptr, -1, 0, 0); // signal EOF
            break;
        }
        if (ret < 0) {
            LOGE("av_read_frame error: %d", ret);
            break;
        }

        // Only process video packets
        if (packet_->stream_index == video_stream_idx_) {
            processVideoPacket();
        }
        av_packet_unref(packet_);
    }

    LOGI("Decode thread finished");
}

void FFmpegDecoder::processVideoPacket() {
    int ret = avcodec_send_packet(codec_ctx_, packet_);
    if (ret < 0 && ret != AVERROR(EAGAIN)) {
        LOGE("avcodec_send_packet failed: %d", ret);
        return;
    }

    while (ret >= 0) {
        ret = avcodec_receive_frame(codec_ctx_, frame_);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
        if (ret < 0) {
            LOGE("avcodec_receive_frame error: %d", ret);
            break;
        }

        if (width_ != frame_->width || height_ != frame_->height || codec_ctx_->pix_fmt != frame_->format) {
            LOGI("Video format updated: %dx%d fmt=%d", frame_->width, frame_->height, frame_->format);
            width_ = frame_->width;
            height_ = frame_->height;
            codec_ctx_->pix_fmt = static_cast<AVPixelFormat>(frame_->format);
            setupSwsContext();
        }

        // Convert pixel format if needed
        AVFrame* output_frame = frame_;
        if (sws_ctx_) {
            sws_scale(sws_ctx_,
                      (const uint8_t* const*)frame_->data, frame_->linesize,
                      0, height_,
                      sw_frame_->data, sw_frame_->linesize);
            output_frame = sw_frame_;
        }

        // Compute PTS in microseconds
        int64_t pts_us = 0;
        if (frame_->pts != AV_NOPTS_VALUE) {
            pts_us = av_rescale_q(frame_->pts,
                                   fmt_ctx_->streams[video_stream_idx_]->time_base,
                                   AV_TIME_BASE_Q);
        }

        // Deliver frame to callback
        if (callback_) {
            callback_(output_frame, pts_us, width_, height_);
        }

        av_frame_unref(frame_);
    }
}

//  Getters 
int     FFmpegDecoder::getWidth()      const { return width_; }
int     FFmpegDecoder::getHeight()     const { return height_; }
int64_t FFmpegDecoder::getDurationUs() const { return duration_us_; }
bool    FFmpegDecoder::isEof()         const { return eof_; }
