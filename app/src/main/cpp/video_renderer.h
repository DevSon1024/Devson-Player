#pragma once

#include <GLES2/gl2.h>
#include <EGL/egl.h>
#include <android/native_window.h>
#include <cstdint>
#include <atomic>

class VideoRenderer {
public:
    VideoRenderer();
    ~VideoRenderer();

    bool init(ANativeWindow* window, int width, int height);
    void updateSize(int width, int height);

    /** Upload YUV420P planes and blit to surface. Thread-safe via paused_ guard. */
    void renderFrame(const uint8_t* y_plane, int y_stride,
                     const uint8_t* u_plane, int u_stride,
                     const uint8_t* v_plane, int v_stride);

    void pauseRendering();
    void resumeRendering();
    void release();

    int getFrameWidth()  const { return frame_width_;  }
    int getFrameHeight() const { return frame_height_; }

private:
    bool initEGL();
    bool initGL();

    ANativeWindow* native_window_ = nullptr;
    EGLDisplay     egl_display_   = EGL_NO_DISPLAY;
    EGLContext     egl_context_   = EGL_NO_CONTEXT;
    EGLSurface     egl_surface_   = EGL_NO_SURFACE;

    GLuint program_ = 0;
    GLuint vbo_     = 0;
    GLuint tex_y_   = 0;
    GLuint tex_u_   = 0;
    GLuint tex_v_   = 0;

    // Cached uniform locations — queried once in initGL()
    GLint loc_y_     = -1;
    GLint loc_u_     = -1;
    GLint loc_v_     = -1;
    GLint loc_pos_   = -1;
    GLint loc_tex_   = -1;

    int  frame_width_  = 0;
    int  frame_height_ = 0;
    bool initialized_  = false;

    std::atomic<bool> paused_{false};
};
