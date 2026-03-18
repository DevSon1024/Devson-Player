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

//  Player session context

struct PlayerContext {
    FFmpegDecoder  decoder;
    VideoRenderer  renderer;
    AudioRenderer  audio_renderer;
    MasterClock    clock;          // Audio-based master clock for A/V sync
    ANativeWindow* window = nullptr;
    bool           renderer_ready = false;
};

//  Helper: pointer ↔ jlong
static PlayerContext* toCtx(jlong handle) {
    return reinterpret_cast<PlayerContext*>(handle);
}

//  JNI Functions

extern "C" {

/**
 * Initialise a new player session.
 * @param path     File path (UTF-8)
 * @param surface  Android Surface object (for software render path)
 * @param width    Surface width
 * @param height   Surface height
 * @return opaque handle (pointer cast to jlong), or 0 on failure
 */
JNIEXPORT jlong JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeInitPlayer(
        JNIEnv* env, jobject /* this */,
        jstring path_jstr, jobject surface, jint width, jint height)
{
    const char* path = env->GetStringUTFChars(path_jstr, nullptr);

    auto* ctx = new PlayerContext();

    // Bind ANativeWindow from Surface
    ctx->window = ANativeWindow_fromSurface(env, surface);
    if (!ctx->window) {
        LOGE("ANativeWindow_fromSurface failed");
        env->ReleaseStringUTFChars(path_jstr, path);
        delete ctx;
        return 0;
    }

    // Init renderer (OpenGL ES / EGL)
    if (!ctx->renderer.init(ctx->window, width, height)) {
        LOGE("VideoRenderer init failed");
        ANativeWindow_release(ctx->window);
        env->ReleaseStringUTFChars(path_jstr, path);
        delete ctx;
        return 0;
    }
    ctx->renderer_ready = true;

    // Init Audio Renderer (Oboe, 44.1kHz, 2 Channels)
    if (!ctx->audio_renderer.init(44100, 2)) {
        LOGE("AudioRenderer init failed");
    }

    // Capture raw ptr for lambdas
    PlayerContext* ctx_ptr = ctx;

    // Video Frame Callback
    auto video_cb = [ctx_ptr](AVFrame* frame, int64_t /*pts_us*/, int w, int h) {
        if (!frame) return;  // EOF signal
        if (!ctx_ptr->renderer_ready) return;

        // If the dimensions changed mid-stream (e.g., after probing), update OpenGL
        if (w > 0 && h > 0 && (w != ctx_ptr->renderer.getFrameWidth() || h != ctx_ptr->renderer.getFrameHeight())) {
            ctx_ptr->renderer.updateSize(w, h);
        }

        // Deliver YUV planes to renderer
        ctx_ptr->renderer.renderFrame(
                frame->data[0], frame->linesize[0],
                frame->data[1], frame->linesize[1],
                frame->data[2], frame->linesize[2]
        );
    };

    // Audio Frame Callback (PCM Data)
    auto audio_cb = [ctx_ptr](uint8_t* pcm_data, int num_frames, int64_t /*pts_us*/) {
        // Send PCM data directly to Oboe stream. This blocks if buffer is full!
        ctx_ptr->audio_renderer.write(pcm_data, num_frames);
    };

    // Init FFmpeg decoder with both callbacks
    bool init_ok = ctx->decoder.init(path, video_cb, audio_cb);

    env->ReleaseStringUTFChars(path_jstr, path);

    if (!init_ok) {
        LOGE("FFmpegDecoder init failed");
        ctx->audio_renderer.release();
        ctx->renderer.release();
        ANativeWindow_release(ctx->window);
        delete ctx;
        return 0;
    }

    // Wire the shared A/V master clock into the decoder
    ctx->decoder.setMasterClock(&ctx->clock);

    LOGI("NativePlayer init OK  handle=%p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Start background decode thread.
 */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeStartDecoding(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
if (!handle) return;
toCtx(handle)->audio_renderer.start();
toCtx(handle)->decoder.startDecoding();
}

/**
 * Pause decoding.
 */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativePause(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
if (!handle) return;
toCtx(handle)->audio_renderer.pause();
toCtx(handle)->decoder.pause();
}

/**
 * Resume decoding.
 */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeResume(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
if (!handle) return;
toCtx(handle)->audio_renderer.start();
toCtx(handle)->decoder.resume();
}

/**
 * Seek to position.
 * @param position_us position in microseconds
 */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeSeek(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle, jlong position_us)
{
if (!handle) return;
toCtx(handle)->clock.reset();          // Discard stale audio PTS before the new position
toCtx(handle)->audio_renderer.flush(); // Clear stale audio from buffers
toCtx(handle)->decoder.seekTo(static_cast<int64_t>(position_us));
}

/**
 * Set playback speed multiplier (0.25 – 4.0).
 */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeSetSpeed(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle, jfloat speed)
{
if (!handle) return;
toCtx(handle)->decoder.setSpeed(speed);
}

/**
 * Query video width.
 */
JNIEXPORT jint JNICALL
        Java_com_devson_devsonplayer_player_NativePlayer_nativeGetWidth(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
if (!handle) return 0;
return toCtx(handle)->decoder.getWidth();
}

/**
 * Query video height.
 */
JNIEXPORT jint JNICALL
        Java_com_devson_devsonplayer_player_NativePlayer_nativeGetHeight(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
if (!handle) return 0;
return toCtx(handle)->decoder.getHeight();
}

/**
 * Query duration in microseconds.
 */
JNIEXPORT jlong JNICALL
        Java_com_devson_devsonplayer_player_NativePlayer_nativeGetDurationUs(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
if (!handle) return 0;
return static_cast<jlong>(toCtx(handle)->decoder.getDurationUs());
}

/**
 * Set the active audio stream index.
 */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeSetAudioStream(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle, jint stream_index)
{
if (!handle) return;
toCtx(handle)->decoder.setAudioStream(stream_index);
}

/**
 * Release all resources and destroy the context.
 */
JNIEXPORT void JNICALL
Java_com_devson_devsonplayer_player_NativePlayer_nativeRelease(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle)
{
if (!handle) return;

auto* ctx = toCtx(handle);
ctx->renderer_ready = false;

ctx->decoder.release();
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