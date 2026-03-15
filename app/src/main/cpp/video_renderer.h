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

    /** Upload YUV420P planes and blit to surface. */
    void renderFrame(const uint8_t* y_plane, int y_stride,
                     const uint8_t* u_plane, int u_stride,
                     const uint8_t* v_plane, int v_stride);

    void release();

private:
    bool initEGL();
    bool initGL();

    ANativeWindow* native_window_;
    EGLDisplay     egl_display_;
    EGLContext     egl_context_;
    EGLSurface     egl_surface_;

    GLuint program_;
    GLuint vbo_;
    GLuint tex_y_, tex_u_, tex_v_;

    int  frame_width_, frame_height_;
    bool initialized_;
};
