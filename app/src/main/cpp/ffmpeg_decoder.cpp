/**
 * ffmpeg_decoder.cpp  —  Production-grade three-thread decoder
 *
 * Thread model:
 *   demuxLoop()       — reads packets from container, enqueues to video/audio PacketQueues
 *   videoDecodeLoop() — dequeues video packets, decodes, converts, A/V syncs, delivers to callback
 *   audioDecodeLoop() — dequeues audio packets, decodes, resamples → PCM, pushes to AudioCallback
 *
 * Supported containers: MKV, MP4, AVI, MOV, FLV, WEBM, TS
 * Supported video codecs: H.264, H.265/HEVC (8-bit + 10-bit), VP9, AV1, VC-1
 * Supported audio codecs: AAC, AC3, EAC3, MP3, Opus, Vorbis
 */

#include "ffmpeg_decoder.h"
#include <android/log.h>
#include <cstring>
#include <algorithm>
#include <thread>
#include <chrono>

#define LOG_TAG "FFmpegDecoder"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

//  Constructor / Destructor 

FFmpegDecoder::FFmpegDecoder() {
    LOGI("FFmpegDecoder created");
}

FFmpegDecoder::~FFmpegDecoder() {
    release();
}

//  init 

bool FFmpegDecoder::init(const char* path,
                         DecoderCallback video_callback,
                         AudioCallback   audio_callback) {
    std::lock_guard<std::mutex> lock(mutex_);
    callback_       = video_callback;
    audio_callback_ = audio_callback;

    // Allocate scratch frames
    frame_       = av_frame_alloc();
    sw_frame_    = av_frame_alloc();
    audio_frame_ = av_frame_alloc();
    if (!frame_ || !sw_frame_ || !audio_frame_) {
        LOGE("Failed to allocate AVFrames");
        return false;
    }

    // Allocate safe audio PCM output buffer (256KB — enough for any frame)
    audio_out_buffer_ = new uint8_t[262144];

    // Open container
    fmt_ctx_ = avformat_alloc_context();
    int ret  = avformat_open_input(&fmt_ctx_, path, nullptr, nullptr);
    if (ret < 0) {
        char buf[256]; av_strerror(ret, buf, sizeof(buf));
        LOGE("avformat_open_input failed: %s", buf);
        return false;
    }

    ret = avformat_find_stream_info(fmt_ctx_, nullptr);
    if (ret < 0) {
        LOGE("avformat_find_stream_info failed");
        return false;
    }

    duration_us_       = fmt_ctx_->duration; // AV_TIME_BASE (microseconds)
    video_stream_idx_  = av_find_best_stream(fmt_ctx_, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    audio_stream_idx_  = av_find_best_stream(fmt_ctx_, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);

    if (video_stream_idx_ < 0) {
        LOGE("No video stream found");
        return false;
    }

    LOGI("Streams: video=%d  audio=%d", video_stream_idx_, audio_stream_idx_);

    // Attempt video codec — retry once on failure
    if (!openVideoCodec()) {
        LOGW("Video codec open failed, retrying ...");
        avcodec_free_context(&codec_ctx_);
        if (!openVideoCodec()) {
            LOGE("Video codec open failed after retry");
            return false;
        }
    }

    // Audio codec is optional — player can run video-only
    openAudioCodec();

    LOGI("Decoder initialised: %dx%d  duration=%.2fs  codec=%s",
         width_, height_, duration_us_ / 1e6,
         avcodec_get_name(codec_ctx_->codec_id));

    return true;
}

//  openVideoCodec 

bool FFmpegDecoder::openVideoCodec() {
    AVStream*       stream = fmt_ctx_->streams[video_stream_idx_];
    const AVCodec*  codec  = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) {
        LOGE("No decoder found for codec_id=%d", stream->codecpar->codec_id);
        return false;
    }

    codec_ctx_ = avcodec_alloc_context3(codec);
    if (!codec_ctx_) { LOGE("avcodec_alloc_context3 failed"); return false; }

    if (avcodec_parameters_to_context(codec_ctx_, stream->codecpar) < 0) {
        LOGE("avcodec_parameters_to_context failed");
        return false;
    }

    // Multi-threaded decoding: use actual CPU count, cap at 4
    int cores = (int)std::thread::hardware_concurrency();
    if (cores <= 0) cores = 2;
    codec_ctx_->thread_count = std::min(cores, 4);
    codec_ctx_->thread_type  = FF_THREAD_FRAME | FF_THREAD_SLICE;

    if (avcodec_open2(codec_ctx_, codec, nullptr) < 0) {
        LOGE("avcodec_open2 failed");
        return false;
    }

    width_  = codec_ctx_->width;
    height_ = codec_ctx_->height;

    LOGI("Video: %s  %dx%d  pix_fmt=%s  profile=%d  threads=%d",
         codec->name, width_, height_,
         av_get_pix_fmt_name(codec_ctx_->pix_fmt),
         codec_ctx_->profile,
         codec_ctx_->thread_count);

    setupSwsContext();
    return true;
}

//  openAudioCodec 

bool FFmpegDecoder::openAudioCodec() {
    if (audio_stream_idx_ < 0) return false;

    AVStream*       stream = fmt_ctx_->streams[audio_stream_idx_];
    const AVCodec*  codec  = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) { LOGW("No audio decoder for codec_id=%d", stream->codecpar->codec_id); return false; }

    audio_codec_ctx_ = avcodec_alloc_context3(codec);
    if (!audio_codec_ctx_) return false;

    if (avcodec_parameters_to_context(audio_codec_ctx_, stream->codecpar) < 0) {
        avcodec_free_context(&audio_codec_ctx_);
        return false;
    }

    if (avcodec_open2(audio_codec_ctx_, codec, nullptr) < 0) {
        avcodec_free_context(&audio_codec_ctx_);
        return false;
    }

    LOGI("Audio: %s  %dHz  ch=%d", codec->name,
         audio_codec_ctx_->sample_rate, audio_codec_ctx_->ch_layout.nb_channels);

    // swr_ctx_ is lazily initialised on the first decoded frame
    return true;
}

//  setupSwsContext 

void FFmpegDecoder::setupSwsContext() {
    if (sws_ctx_) { sws_freeContext(sws_ctx_); sws_ctx_ = nullptr; }

    AVPixelFormat src_fmt = codec_ctx_->pix_fmt;
    AVPixelFormat dst_fmt = AV_PIX_FMT_YUV420P;

    // Guard: codec_ctx_->pix_fmt is AV_PIX_FMT_NONE until first frame decoded.
    // Called again on first frame via videoDecodeLoop format-change detection.
    if (src_fmt == AV_PIX_FMT_NONE) {
        LOGI("SWS deferred — pix_fmt not yet known");
        return;
    }

    // 10-bit → 8-bit downconversion for OpenGL ES upload
    if (src_fmt == AV_PIX_FMT_YUV420P10LE ||
        src_fmt == AV_PIX_FMT_YUV420P10BE ||
        src_fmt == AV_PIX_FMT_YUV422P10LE ||
        src_fmt == AV_PIX_FMT_YUV444P10LE) {
        LOGI("10-bit input detected (%s) — converting to YUV420P 8-bit",
             av_get_pix_fmt_name(src_fmt));
    } else if (src_fmt == dst_fmt) {
        LOGI("Already YUV420P — no SWS needed");
        // still allocate sw_frame_ for use as output_frame alias
        av_frame_unref(sw_frame_);
        sw_frame_->format = AV_PIX_FMT_YUV420P;
        sw_frame_->width  = width_;
        sw_frame_->height = height_;
        av_frame_get_buffer(sw_frame_, 32);
        return;
    }

    sws_ctx_ = sws_getContext(
        width_, height_, src_fmt,
        width_, height_, dst_fmt,
        SWS_BILINEAR, nullptr, nullptr, nullptr);

    if (!sws_ctx_) {
        LOGE("sws_getContext failed (src=%s)", av_get_pix_fmt_name(src_fmt));
        return;
    }

    // Allocate destination frame buffer (just once)
    av_frame_unref(sw_frame_);
    sw_frame_->format = AV_PIX_FMT_YUV420P;
    sw_frame_->width  = width_;
    sw_frame_->height = height_;
    if (av_frame_get_buffer(sw_frame_, 32) < 0) {
        LOGE("av_frame_get_buffer for sw_frame_ failed");
        sws_freeContext(sws_ctx_);
        sws_ctx_ = nullptr;
    } else {
        LOGI("SWS: %s → YUV420P (%dx%d)",
             av_get_pix_fmt_name(src_fmt), width_, height_);
    }
}

//  initSwrContext (lazy, called from audio thread on first frame) 

bool FFmpegDecoder::initSwrContext(AVFrame* first_frame) {
    if (swr_ctx_) { swr_free(&swr_ctx_); }

    AVChannelLayout out_layout = AV_CHANNEL_LAYOUT_STEREO;
    av_channel_layout_default(&out_layout, 2);

    // Build safe input layout from frame, fallback to codec context
    AVChannelLayout in_layout = first_frame->ch_layout;
    if (in_layout.nb_channels == 0) in_layout = audio_codec_ctx_->ch_layout;
    if (in_layout.nb_channels == 0) av_channel_layout_default(&in_layout, 2);

    // Sample rate: frame first, then codec ctx, then hardcoded fallback
    int in_rate = first_frame->sample_rate > 0
                    ? first_frame->sample_rate
                    : audio_codec_ctx_->sample_rate;
    if (in_rate <= 0) in_rate = 44100;

    // Sample format: frame first, then codec ctx, then float planar fallback
    // AAC often reports AV_SAMPLE_FMT_NONE (-1) on first frame until the
    // codec has decoded enough to know the true format.
    AVSampleFormat in_fmt = (AVSampleFormat)first_frame->format;
    if (in_fmt == AV_SAMPLE_FMT_NONE || in_fmt < 0) {
        in_fmt = audio_codec_ctx_->sample_fmt;
    }
    if (in_fmt == AV_SAMPLE_FMT_NONE || in_fmt < 0) {
        in_fmt = AV_SAMPLE_FMT_FLTP;  // AAC decodes to float planar by default
    }

    LOGI("swr_init: in_rate=%d  in_fmt=%s  in_ch=%d → 44100 S16 stereo",
         in_rate, av_get_sample_fmt_name(in_fmt), in_layout.nb_channels);

    int ret = swr_alloc_set_opts2(
        &swr_ctx_,
        &out_layout, AV_SAMPLE_FMT_S16, 44100,
        &in_layout,  in_fmt, in_rate,
        0, nullptr);

    if (ret < 0 || !swr_ctx_ || swr_init(swr_ctx_) < 0) {
        LOGE("swr_init failed: ret=%d  in_fmt=%s  in_rate=%d",
             ret, av_get_sample_fmt_name(in_fmt), in_rate);
        if (swr_ctx_) { swr_free(&swr_ctx_); }
        return false;
    }
    LOGI("Audio resampler: %dHz → 44100Hz stereo S16", in_rate);
    return true;
}

//  startDecoding 

void FFmpegDecoder::startDecoding() {
    playing_ = true;
    eof_     = false;
    paused_  = false;

    video_pkt_queue_.reset();
    audio_pkt_queue_.reset();

    demux_thread_ = std::thread(&FFmpegDecoder::demuxLoop,       this);
    video_thread_ = std::thread(&FFmpegDecoder::videoDecodeLoop, this);
    audio_thread_ = std::thread(&FFmpegDecoder::audioDecodeLoop, this);
}

//  pause / resume 

void FFmpegDecoder::pause() {
    paused_.store(true, std::memory_order_relaxed);
}

void FFmpegDecoder::resume() {
    paused_.store(false, std::memory_order_relaxed);
    cv_.notify_all();
}

//  seekTo 

void FFmpegDecoder::seekTo(int64_t position_us) {
    seek_target_us_.store(position_us, std::memory_order_relaxed);
    seek_requested_.store(true,        std::memory_order_relaxed);
    // Flush queues so decode threads don't block on full queues during seek
    video_pkt_queue_.flush();
    audio_pkt_queue_.flush();
    cv_.notify_all();
}

//  setSpeed 

void FFmpegDecoder::setSpeed(float speed) {
    playback_speed_.store(speed, std::memory_order_relaxed);
}

//  release 

void FFmpegDecoder::release() {
    playing_.store(false, std::memory_order_relaxed);

    // Abort all queues so blocked threads wake up
    video_pkt_queue_.abort();
    audio_pkt_queue_.abort();
    cv_.notify_all();

    // Join in order: demux → video → audio
    if (demux_thread_.joinable()) demux_thread_.join();
    if (video_thread_.joinable()) video_thread_.join();
    if (audio_thread_.joinable()) audio_thread_.join();

    // Free FFmpeg resources under mutex
    {
        std::lock_guard<std::mutex> lk(mutex_);
        if (sws_ctx_)         { sws_freeContext(sws_ctx_);          sws_ctx_         = nullptr; }
        if (swr_ctx_)         { swr_free(&swr_ctx_);                swr_ctx_         = nullptr; }
        if (audio_frame_)     { av_frame_free(&audio_frame_);       audio_frame_     = nullptr; }
        if (audio_codec_ctx_) { avcodec_free_context(&audio_codec_ctx_); audio_codec_ctx_ = nullptr; }
        if (frame_)           { av_frame_free(&frame_);             frame_           = nullptr; }
        if (sw_frame_)        { av_frame_free(&sw_frame_);          sw_frame_        = nullptr; }
        if (codec_ctx_)       { avcodec_free_context(&codec_ctx_);  codec_ctx_       = nullptr; }
        if (fmt_ctx_)         { avformat_close_input(&fmt_ctx_);    fmt_ctx_         = nullptr; }
        delete[] audio_out_buffer_;  audio_out_buffer_ = nullptr;
    }

    LOGI("FFmpegDecoder released");
}

//  demuxLoop 

void FFmpegDecoder::demuxLoop() {
    LOGI("Demux thread started");

    while (playing_.load(std::memory_order_relaxed)) {

        //  Handle pause 
        if (paused_.load(std::memory_order_relaxed)) {
            std::unique_lock<std::mutex> lk(mutex_);
            cv_.wait(lk, [this]{ return !paused_ || !playing_ || seek_requested_; });
        }
        if (!playing_) break;

        //  Handle seek 
        if (seek_requested_.load(std::memory_order_relaxed)) {
            seek_requested_.store(false, std::memory_order_relaxed);

            std::lock_guard<std::mutex> lk(mutex_);
            int64_t target_us = seek_target_us_.load(std::memory_order_relaxed);
            int64_t seek_ts   = av_rescale_q(target_us,
                                             AV_TIME_BASE_Q,
                                             fmt_ctx_->streams[video_stream_idx_]->time_base);
            int ret = av_seek_frame(fmt_ctx_, video_stream_idx_, seek_ts, AVSEEK_FLAG_BACKWARD);
            if (ret < 0) LOGW("Seek failed: %d", ret);

            if (codec_ctx_)       avcodec_flush_buffers(codec_ctx_);
            if (audio_codec_ctx_) avcodec_flush_buffers(audio_codec_ctx_);
            eof_.store(false, std::memory_order_relaxed);
            continue;   // re-read after seek
        }

        //  Read next packet 
        AVPacket* pkt = av_packet_alloc();
        if (!pkt) { LOGE("av_packet_alloc failed"); break; }

        int ret = av_read_frame(fmt_ctx_, pkt);
        if (ret == AVERROR_EOF) {
            av_packet_free(&pkt);
            eof_.store(true, std::memory_order_relaxed);
            LOGI("EOF — demux finished");
            // Signal both decode queues with nullptr sentinel
            video_pkt_queue_.push(nullptr);   // nullptr == EOF signal
            audio_pkt_queue_.push(nullptr);
            break;
        }
        if (ret < 0) {
            av_packet_free(&pkt);
            LOGE("av_read_frame error: %d", ret);
            break;
        }

        if (pkt->stream_index == video_stream_idx_) {
            if (!video_pkt_queue_.push(pkt)) av_packet_free(&pkt);
        } else if (pkt->stream_index == audio_stream_idx_) {
            if (!audio_pkt_queue_.push(pkt)) av_packet_free(&pkt);
        } else {
            av_packet_free(&pkt);
        }
    }

    LOGI("Demux thread finished");
}

//  videoDecodeLoop 

void FFmpegDecoder::videoDecodeLoop() {
    LOGI("Video decode thread started");

    // A/V sync thresholds (microseconds)
    constexpr int64_t SYNC_THRESHOLD_US = 15000;    // 15ms — render normally
    constexpr int64_t DROP_THRESHOLD_US = 150000;   // 150ms — drop frame
    constexpr int64_t MAX_SLEEP_US      = 50000;    // 50ms max sleep per frame

    while (playing_.load(std::memory_order_relaxed)) {

        // Pause handling
        if (paused_.load(std::memory_order_relaxed)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }

        AVPacket* pkt = video_pkt_queue_.pop();
        if (!pkt) {
            // nullptr = EOF or abort
            if (playing_) {
                // Signal EOF to UI via callback
                if (callback_) callback_(nullptr, -1, 0, 0);
            }
            break;
        }

        // Decode under mutex (codec_ctx_ may be accessed by seekTo flush)
        {
            std::lock_guard<std::mutex> lk(mutex_);
            if (!codec_ctx_) { av_packet_free(&pkt); continue; }

            int ret = avcodec_send_packet(codec_ctx_, pkt);
            av_packet_free(&pkt);

            if (ret < 0 && ret != AVERROR(EAGAIN)) {
                LOGE("avcodec_send_packet video: %d", ret);
                continue;
            }

            while (true) {
                // Use local frame_ for decoding
                ret = avcodec_receive_frame(codec_ctx_, frame_);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                if (ret < 0) { LOGE("avcodec_receive_frame video: %d", ret); break; }

                // Resize SWS if frame format changed (e.g., first 10-bit frame)
                if (frame_->format != (int)sw_frame_->format ||
                    frame_->width  != width_   ||
                    frame_->height != height_) {
                    width_  = frame_->width;
                    height_ = frame_->height;
                    codec_ctx_->pix_fmt = (AVPixelFormat)frame_->format;
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

                //  A/V Sync: audio is master clock 
                if (clock_ && clock_->has_audio.load(std::memory_order_relaxed)) {
                    int64_t audio_pts = clock_->getCurrentTimeUs();
                    int64_t diff      = pts_us - audio_pts; // >0 means video ahead

                    if (diff > SYNC_THRESHOLD_US) {
                        // Video is ahead — sleep to let audio catch up
                        int64_t sleep_us = std::min(diff, MAX_SLEEP_US);
                        // Allow seek/pause to interrupt sleep
                        std::this_thread::sleep_for(std::chrono::microseconds(sleep_us));
                        // Re-check after sleep
                        if (!playing_ || seek_requested_) { av_frame_unref(frame_); continue; }
                    } else if (diff < -DROP_THRESHOLD_US) {
                        // Video is too far behind — drop this frame
                        LOGW("A/V sync: dropping frame (behind %.1f ms)", -diff / 1000.0);
                        av_frame_unref(frame_);
                        continue;
                    }
                }

                // Deliver frame
                if (callback_) {
                    callback_(output_frame, pts_us, width_, height_);
                }

                av_frame_unref(frame_);
            }
        } // end mutex lock
    }

    LOGI("Video decode thread finished");
}

//  audioDecodeLoop 

void FFmpegDecoder::audioDecodeLoop() {
    LOGI("Audio decode thread started");

    while (playing_.load(std::memory_order_relaxed)) {

        if (paused_.load(std::memory_order_relaxed)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }

        AVPacket* pkt = audio_pkt_queue_.pop();
        if (!pkt) break;  // EOF or abort

        std::lock_guard<std::mutex> lk(mutex_);
        if (!audio_codec_ctx_ || !audio_frame_) { av_packet_free(&pkt); continue; }

        int ret = avcodec_send_packet(audio_codec_ctx_, pkt);
        av_packet_free(&pkt);
        if (ret < 0 && ret != AVERROR(EAGAIN)) continue;

        while (true) {
            ret = avcodec_receive_frame(audio_codec_ctx_, audio_frame_);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (ret < 0) break;

            processAudioFrame(audio_frame_);
            av_frame_unref(audio_frame_);
        }
    }

    LOGI("Audio decode thread finished");
}

//  processAudioFrame 

void FFmpegDecoder::processAudioFrame(AVFrame* audio_frame) {
    if (!audio_out_buffer_) return;

    // Guard: only skip frames where format is completely uninitialized.
    // AAC from some containers emits sample_rate=0 even in valid decoded frames —
    // initSwrContext handles that with a 44100 fallback, so DON'T guard on rate.
    // We only need to reject frames where format is truly AV_SAMPLE_FMT_NONE
    // (before the codec has parsed the stream header at all).
    AVSampleFormat frame_fmt = (AVSampleFormat)audio_frame->format;
    if (frame_fmt == AV_SAMPLE_FMT_NONE || frame_fmt < 0) {
        // Try codec context's known format as last resort
        frame_fmt = audio_codec_ctx_ ? audio_codec_ctx_->sample_fmt : AV_SAMPLE_FMT_NONE;
    }
    if (frame_fmt == AV_SAMPLE_FMT_NONE || frame_fmt < 0) {
        // Truly uninitialized — codec hasn't parsed the header yet, skip silently
        return;
    }

    // Lazy init SWR on first valid frame
    if (!swr_ctx_) {
        if (!initSwrContext(audio_frame)) return;
    }

    // Safe input sample rate
    int in_rate = audio_frame->sample_rate > 0
                    ? audio_frame->sample_rate
                    : (audio_codec_ctx_ ? audio_codec_ctx_->sample_rate : 44100);
    if (in_rate <= 0) in_rate = 44100;

    int64_t delay_samples = swr_get_delay(swr_ctx_, in_rate);
    int max_out_samples   = (int)av_rescale_rnd(
        delay_samples + audio_frame->nb_samples, 44100, in_rate, AV_ROUND_UP);

    uint8_t* out_ptr   = audio_out_buffer_;
    int out_frames     = swr_convert(swr_ctx_,
                                     &out_ptr, max_out_samples,
                                     (const uint8_t**)audio_frame->data,
                                     audio_frame->nb_samples);
    if (out_frames <= 0) return;

    // Compute PTS in microseconds from the frame
    int64_t pts_us = 0;
    if (audio_frame->pts != AV_NOPTS_VALUE && audio_stream_idx_ >= 0) {
        pts_us = av_rescale_q(audio_frame->pts,
                               fmt_ctx_->streams[audio_stream_idx_]->time_base,
                               AV_TIME_BASE_Q);
    }

    // Compute the PTS at end-of-frame (for the master clock)
    int64_t frame_duration_us = (int64_t)out_frames * 1000000LL / 44100;
    int64_t clock_pts         = pts_us + frame_duration_us;

    //  Update master clock 
    if (clock_) {
        clock_->update(clock_pts);
    }

    //  Deliver PCM 
    if (audio_callback_) {
        audio_callback_(audio_out_buffer_, out_frames, pts_us);
    }
}

//  setAudioStream 

void FFmpegDecoder::setAudioStream(int relative_audio_index) {
    // Find the N-th audio stream
    int count = 0;
    int abs_idx = -1;
    for (unsigned i = 0; i < fmt_ctx_->nb_streams; i++) {
        if (fmt_ctx_->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            if (count == relative_audio_index) { abs_idx = (int)i; break; }
            count++;
        }
    }
    if (abs_idx < 0 || abs_idx == audio_stream_idx_) return;

    LOGI("Switching audio stream → absolute index %d", abs_idx);

    // Flush audio queue then swap codec under mutex
    audio_pkt_queue_.flush();
    {
        std::lock_guard<std::mutex> lk(mutex_);
        audio_stream_idx_ = abs_idx;
        if (audio_codec_ctx_) avcodec_free_context(&audio_codec_ctx_);
        if (swr_ctx_)         swr_free(&swr_ctx_);
        openAudioCodec();
    }
}

//  setMasterClock 

void FFmpegDecoder::setMasterClock(MasterClock* clock) {
    clock_ = clock;
}

//  Getters 

int64_t FFmpegDecoder::getCurrentPtsUs() const {
    if (clock_) return clock_->getCurrentTimeUs();
    return 0;
}

int     FFmpegDecoder::getWidth()      const { return width_;       }
int     FFmpegDecoder::getHeight()     const { return height_;      }
int64_t FFmpegDecoder::getDurationUs() const { return duration_us_; }
bool    FFmpegDecoder::isEof()         const { return eof_;         }
