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
#include <thread>
#include <chrono>

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
#include <libavutil/opt.h>
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
      playing_(false), playback_speed_(1.0f),
      // --- NEW: Initialize audio pointers to prevent crashes ---
      audio_out_buffer_(nullptr), audio_codec_ctx_(nullptr), 
      swr_ctx_(nullptr), audio_frame_(nullptr) {
    LOGI("FFmpegDecoder created");
}

FFmpegDecoder::~FFmpegDecoder() {
    release();
    delete[] audio_out_buffer_;
}

bool FFmpegDecoder::init(const char* path, DecoderCallback callback, AudioCallback audio_callback) {
    std::lock_guard<std::mutex> lock(mutex_);
    callback_ = callback;
    audio_callback_ = audio_callback;

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
    openAudioCodec();

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

bool FFmpegDecoder::openAudioCodec() {
    if (audio_stream_idx_ < 0) return false;

    AVStream* stream = fmt_ctx_->streams[audio_stream_idx_];
    const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) {
        LOGE("openAudioCodec: no decoder found for codec_id=%d", stream->codecpar->codec_id);
        return false;
    }

    audio_codec_ctx_ = avcodec_alloc_context3(codec);
    if (!audio_codec_ctx_) { LOGE("openAudioCodec: avcodec_alloc_context3 failed"); return false; }

    int ret = avcodec_parameters_to_context(audio_codec_ctx_, stream->codecpar);
    if (ret < 0) { LOGE("openAudioCodec: avcodec_parameters_to_context failed"); return false; }

    ret = avcodec_open2(audio_codec_ctx_, codec, nullptr);
    if (ret < 0) {
        char errbuf[256]; av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("openAudioCodec: avcodec_open2 failed: %s", errbuf);
        return false;
    }

    LOGI("Audio codec opened: %s  sr=%d  ch=%d  fmt=%s",
         codec->name, audio_codec_ctx_->sample_rate,
         audio_codec_ctx_->ch_layout.nb_channels,
         av_get_sample_fmt_name(audio_codec_ctx_->sample_fmt));

    // Pre-allocate a safe output buffer (256KB covers ~1.5s of stereo 16-bit 44.1kHz PCM)
    delete[] audio_out_buffer_;
    audio_out_buffer_ = new uint8_t[256000];

    // Build resampler using the modern FFmpeg 6.0 API
    AVChannelLayout out_ch_layout = AV_CHANNEL_LAYOUT_STEREO;
    av_channel_layout_default(&out_ch_layout, 2);  // Canonical stereo

    ret = swr_alloc_set_opts2(
        &swr_ctx_,
        &out_ch_layout,            // output: stereo
        AV_SAMPLE_FMT_S16,         // output: 16-bit signed PCM
        44100,                     // output: 44100 Hz
        &audio_codec_ctx_->ch_layout,  // input: from stream
        audio_codec_ctx_->sample_fmt,  // input: from stream
        audio_codec_ctx_->sample_rate, // input: from stream
        0, nullptr
    );
    if (ret < 0 || !swr_ctx_) {
        LOGE("openAudioCodec: swr_alloc_set_opts2 failed");
        return false;
    }

    ret = swr_init(swr_ctx_);
    if (ret < 0) {
        char errbuf[256]; av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("openAudioCodec: swr_init failed: %s", errbuf);
        swr_free(&swr_ctx_);
        return false;
    }

    audio_frame_ = av_frame_alloc();
    if (!audio_frame_) { LOGE("openAudioCodec: av_frame_alloc failed"); return false; }

    LOGI("Audio resampler ready: %d Hz stereo -> 44100 Hz stereo S16",
         audio_codec_ctx_->sample_rate);
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

void FFmpegDecoder::setMasterClock(MasterClock* clock) {
    clock_ = clock;
}

void FFmpegDecoder::release() {
    playing_ = false;
    cv_.notify_all();

    if (decode_thread_.joinable()) {
        decode_thread_.join();
    }

    if (sws_ctx_) { sws_freeContext(sws_ctx_); sws_ctx_ = nullptr; }
    if (audio_frame_) { av_frame_free(&audio_frame_); }
    if (audio_codec_ctx_) { avcodec_free_context(&audio_codec_ctx_); }
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

        if (packet_->stream_index == video_stream_idx_) {
            processVideoPacket();
        } else if (packet_->stream_index == audio_stream_idx_) {
            processAudioPacket();
        }
        av_packet_unref(packet_);
    }

    LOGI("Decode thread finished");
}

void FFmpegDecoder::setAudioStream(int relative_audio_index) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    // Find the N-th audio stream in the file that matches the UI click
    int current_audio_count = 0;
    int absolute_stream_idx = -1;
    
    for (unsigned int i = 0; i < fmt_ctx_->nb_streams; i++) {
        if (fmt_ctx_->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            if (current_audio_count == relative_audio_index) {
                absolute_stream_idx = i;
                break;
            }
            current_audio_count++;
        }
    }

    if (absolute_stream_idx == -1 || absolute_stream_idx == audio_stream_idx_) return;

    LOGI("Seamless Fallback: Switching audio stream to absolute index %d", absolute_stream_idx);
    audio_stream_idx_ = absolute_stream_idx;
    
    // Close the old codec and open the new one
    if (audio_codec_ctx_) { avcodec_free_context(&audio_codec_ctx_); }
    if (swr_ctx_) { swr_free(&swr_ctx_); }
    if (audio_frame_) { av_frame_free(&audio_frame_); }
    
    openAudioCodec();
}

void FFmpegDecoder::processAudioPacket() {
    if (!audio_codec_ctx_ || !swr_ctx_ || !audio_frame_ || !audio_out_buffer_) return;

    int ret = avcodec_send_packet(audio_codec_ctx_, packet_);
    if (ret < 0) return;

    while (ret >= 0) {
        ret = avcodec_receive_frame(audio_codec_ctx_, audio_frame_);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
        if (ret < 0) break;

        // Calculate exact output sample count to avoid buffer overread
        int64_t delay_samples = swr_get_delay(swr_ctx_, audio_codec_ctx_->sample_rate);
        int max_out_samples = static_cast<int>(
            av_rescale_rnd(delay_samples + audio_frame_->nb_samples,
                           44100, audio_codec_ctx_->sample_rate, AV_ROUND_UP));

        uint8_t* out_ptr = audio_out_buffer_;
        int out_frames = swr_convert(swr_ctx_,
                                     &out_ptr, max_out_samples,
                                     (const uint8_t**)audio_frame_->data,
                                     audio_frame_->nb_samples);

        if (out_frames > 0) {
            // 2 channels * 2 bytes per sample (S16)
            int data_size = out_frames * 2 * 2;

            int64_t pts_us = 0;
            if (audio_frame_->pts != AV_NOPTS_VALUE) {
                pts_us = av_rescale_q(audio_frame_->pts,
                                      fmt_ctx_->streams[audio_stream_idx_]->time_base,
                                      AV_TIME_BASE_Q);
            }

            // --- Master Clock: update audio PTS so the video thread can sync to it ---
            if (clock_) {
                int64_t frame_duration_us = static_cast<int64_t>(out_frames) * 1000000LL / 44100;
                clock_->audio_pts_us.store(pts_us + frame_duration_us, std::memory_order_relaxed);
                clock_->has_audio.store(true, std::memory_order_relaxed);
            }

            if (audio_callback_) {
                // Pass frames (out_frames), not bytes, to blocking write path
                audio_callback_(audio_out_buffer_, out_frames, pts_us);
            }

            (void)data_size; // used by decodeAudio() pull path; kept for reference
        }
        av_frame_unref(audio_frame_);
    }
}

// Pull-based audio: called directly from the Oboe onAudioReady callback.
// Reads one packet, decodes it, resamples it, and returns the PCM byte count.
int FFmpegDecoder::decodeAudio(uint8_t** out_buffer) {
    if (!audio_codec_ctx_ || !swr_ctx_ || !audio_frame_ || !audio_out_buffer_) return 0;

    // Try to receive a pending frame first
    int ret = avcodec_receive_frame(audio_codec_ctx_, audio_frame_);

    // If the codec needs more input, read a packet and send it
    if (ret == AVERROR(EAGAIN)) {
        // Read packets until we get an audio packet or hit an error
        while (true) {
            AVPacket* pkt = av_packet_alloc();
            if (!pkt) return 0;

            ret = av_read_frame(fmt_ctx_, pkt);
            if (ret < 0) { av_packet_free(&pkt); return 0; }

            if (pkt->stream_index == audio_stream_idx_) {
                avcodec_send_packet(audio_codec_ctx_, pkt);
                av_packet_free(&pkt);
                ret = avcodec_receive_frame(audio_codec_ctx_, audio_frame_);
                break;
            }
            av_packet_free(&pkt);
        }
    }

    if (ret < 0) return 0;

    // Resample the decoded frame
    int64_t delay_samples = swr_get_delay(swr_ctx_, audio_codec_ctx_->sample_rate);
    int max_out_samples = static_cast<int>(
        av_rescale_rnd(delay_samples + audio_frame_->nb_samples,
                       44100, audio_codec_ctx_->sample_rate, AV_ROUND_UP));

    uint8_t* out_ptr = audio_out_buffer_;
    int actual_out_samples = swr_convert(swr_ctx_,
                                         &out_ptr, max_out_samples,
                                         (const uint8_t**)audio_frame_->data,
                                         audio_frame_->nb_samples);
    av_frame_unref(audio_frame_);

    if (actual_out_samples <= 0) return 0;

    // 2 channels * 2 bytes per S16 sample
    int data_size = actual_out_samples * 2 * 2;
    *out_buffer = audio_out_buffer_;
    return data_size;
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
            // --- A/V SYNC: compare video PTS against audio master clock ---
            if (clock_ && clock_->has_audio.load(std::memory_order_relaxed)) {
                // Thresholds (all in microseconds)
                constexpr int64_t SYNC_THRESHOLD_US = 15000;   // 15 ms  — render normally
                constexpr int64_t DROP_THRESHOLD_US  = 150000;  // 150 ms — drop frame
                constexpr int64_t MAX_SLEEP_US        = 50000;  // 50 ms  — max sleep per frame

                int64_t audio_pts = clock_->audio_pts_us.load(std::memory_order_relaxed);
                int64_t diff      = pts_us - audio_pts; // positive → video ahead

                if (diff > SYNC_THRESHOLD_US) {
                    // Video is running ahead — sleep to let audio catch up.
                    int64_t sleep_us = std::min(diff, MAX_SLEEP_US);
                    LOGI("A/V sync: video ahead by %.1f ms, sleeping %.1f ms",
                         diff / 1000.0, sleep_us / 1000.0);
                    std::this_thread::sleep_for(std::chrono::microseconds(sleep_us));
                } else if (diff < -DROP_THRESHOLD_US) {
                    // Video is too far behind — drop the frame.
                    LOGW("A/V sync: dropping frame, video behind audio by %.1f ms",
                         -diff / 1000.0);
                    av_frame_unref(frame_);
                    continue; // skip callback, move to next packet
                }
                // If diff is between -DROP_THRESHOLD and -SYNC_THRESHOLD the video is
                // slightly behind but within catchup range — render it anyway.
            }

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
