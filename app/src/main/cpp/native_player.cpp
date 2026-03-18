/**
 * native_player.cpp
 *
 * JNI bridge between Kotlin (NativePlayer.kt) and the C++ player pipeline.
 * Manages the PlayerContext which owns: FFmpegDecoder, VideoRenderer, AudioRenderer, MasterClock.
 */

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>

#include "ffmpeg_decoder.h"
#include "video_renderer.h"
#include "audio_renderer.h"
#include "master_clock.h"

#define LOG_TAG "NativePlayer"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

//  Player session context 

struct PlayerContext {
    FFmpegDecoder  decoder;
    VideoRenderer  renderer;
    AudioRenderer  audio_renderer;
    MasterClock    clock;
    ANativeWindow* window         = nullptr;
    bool           renderer_ready = false;
};

static PlayerContext* toCtx(jlong handle) {
    return reinterpret_cast<PlayerContext*>(handle);
}

//  JNI Functions 

extern "C" {

/**
 * Initialise a new player session.
 * @param path     File path (UTF-8)
 * @param surface  Android Surface object
 * @param width    Surface width hint
 * @param height   Surface height hint
 * @return opaque handle (pointer cast to jlong), or 0 on failure
 */
JNIEXPORT jlong JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeInitPlayer(
        JNIEnv* env, jobject /*this*/,
        jstring path_jstr, jobject surface, jint width, jint height)
{
    const char* path = env->GetStringUTFChars(path_jstr, nullptr);

    auto* ctx = new PlayerContext();

    // Bind ANativeWindow from Java Surface
    ctx->window = ANativeWindow_fromSurface(env, surface);
    if (!ctx->window) {
        LOGE("ANativeWindow_fromSurface failed");
        env->ReleaseStringUTFChars(path_jstr, path);
        delete ctx;
        return 0;
    }

    // Initialise OpenGL ES renderer
    if (!ctx->renderer.init(ctx->window, width, height)) {
        LOGE("VideoRenderer init failed");
        ANativeWindow_release(ctx->window);
        env->ReleaseStringUTFChars(path_jstr, path);
        delete ctx;
        return 0;
    }
    ctx->renderer_ready = true;

    // Initialise Oboe audio (44.1kHz stereo S16)
    if (!ctx->audio_renderer.init(44100, 2)) {
        LOGE("AudioRenderer init failed — continuing without audio");
    }

    // Wire master clock into decoder
    ctx->decoder.setMasterClock(&ctx->clock);

    //  Video frame callback (runs on decoder's video decode thread) 
    PlayerContext* ctx_ptr = ctx;
    auto video_cb = [ctx_ptr](AVFrame* frame, int64_t /*pts_us*/, int w, int h) {
        if (!frame) return;  // EOF sentinel — handled via isEof()
        if (!ctx_ptr->renderer_ready) return;

        if (w > 0 && h > 0 &&
            (w != ctx_ptr->renderer.getFrameWidth() ||
             h != ctx_ptr->renderer.getFrameHeight())) {
            ctx_ptr->renderer.updateSize(w, h);
        }

        ctx_ptr->renderer.renderFrame(
            frame->data[0], frame->linesize[0],
            frame->data[1], frame->linesize[1],
            frame->data[2], frame->linesize[2]);
    };

    //  Audio PCM callback (runs on decoder's audio decode thread) 
    auto audio_cb = [ctx_ptr](uint8_t* pcm_data, int num_frames, int64_t /*pts_us*/) {
        // Push PCM into ring buffer — non-blocking, never stalls decode thread
        ctx_ptr->audio_renderer.pushPcm(pcm_data, num_frames);
    };

    // Initialise decoder — retry once on failure
    bool init_ok = ctx->decoder.init(path, video_cb, audio_cb);
    if (!init_ok) {
        LOGW("FFmpegDecoder init failed, retrying ...");
        init_ok = ctx->decoder.init(path, video_cb, audio_cb);
    }

    env->ReleaseStringUTFChars(path_jstr, path);

    if (!init_ok) {
        LOGE("FFmpegDecoder init failed after retry");
        ctx->renderer_ready = false;
        ctx->audio_renderer.release();
        ctx->renderer.release();
        ANativeWindow_release(ctx->window);
        delete ctx;
        return 0;
    }

    LOGI("NativePlayer init OK  handle=%p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

/** Start background decode threads. */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeStartDecoding(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
    if (!handle) return;
    auto* ctx = toCtx(handle);
    ctx->audio_renderer.start();
    ctx->decoder.startDecoding();
}

/** Pause decode threads and audio output. */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativePause(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
    if (!handle) return;
    auto* ctx = toCtx(handle);
    ctx->decoder.pause();
    ctx->audio_renderer.pause();
}

/** Resume decode threads and audio output. */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeResume(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
    if (!handle) return;
    auto* ctx = toCtx(handle);
    ctx->audio_renderer.start();
    ctx->decoder.resume();
}

/** Seek to position (microseconds). */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeSeek(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle, jlong position_us)
{
    if (!handle) return;
    auto* ctx = toCtx(handle);
    ctx->clock.reset();            // Discard stale audio PTS before seek
    ctx->audio_renderer.flush();   // Clear ring buffer of pre-seek audio
    ctx->decoder.seekTo((int64_t)position_us);
}

/** Set playback speed multiplier. */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeSetSpeed(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle, jfloat speed)
{
    if (!handle) return;
    toCtx(handle)->decoder.setSpeed(speed);
}

/** Pause the OpenGL renderer (call on Activity.onPause). */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativePauseRenderer(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
    if (!handle) return;
    toCtx(handle)->renderer.pauseRendering();
}

/** Resume the OpenGL renderer (call on Activity.onResume). */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeResumeRenderer(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
    if (!handle) return;
    toCtx(handle)->renderer.resumeRendering();
}

/** Returns the current playback position in microseconds (interpolated from master clock). */
JNIEXPORT jlong JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeGetCurrentPositionUs(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
    if (!handle) return 0;
    return (jlong)toCtx(handle)->decoder.getCurrentPtsUs();
}

/** Query video width. */
JNIEXPORT jint JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeGetWidth(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
    if (!handle) return 0;
    return toCtx(handle)->decoder.getWidth();
}

/** Query video height. */
JNIEXPORT jint JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeGetHeight(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
    if (!handle) return 0;
    return toCtx(handle)->decoder.getHeight();
}

/** Query duration in microseconds. */
JNIEXPORT jlong JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeGetDurationUs(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
    if (!handle) return 0;
    return (jlong)toCtx(handle)->decoder.getDurationUs();
}

/** Set active audio stream (by relative UI index). */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeSetAudioStream(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle, jint stream_index)
{
    if (!handle) return;
    toCtx(handle)->decoder.setAudioStream(stream_index);
}

/** Release all resources and destroy the context. */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeRelease(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
    if (!handle) return;

    auto* ctx = toCtx(handle);

    // Guard renderer before joining decode threads (renderer runs on decode thread)
    ctx->renderer_ready = false;
    ctx->renderer.pauseRendering();

    // Stop decoder threads first (they call renderer callbacks)
    ctx->decoder.release();

    // Then release audio and renderer
    ctx->audio_renderer.release();
    ctx->renderer.release();

    if (ctx->window) {
        ANativeWindow_release(ctx->window);
        ctx->window = nullptr;
    }

    delete ctx;
    LOGI("NativePlayer released");
}

} // extern "C"