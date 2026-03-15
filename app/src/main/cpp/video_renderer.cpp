/**
 * video_renderer.cpp
 *
 * OpenGL ES 2.0 renderer for DevsonPlayer software decode path.
 * Accepts YUV420P frames from FFmpeg and renders them via GLSL shaders.
 * Uses ANativeWindow / EGL for surface binding.
 */

#include "video_renderer.h"

#include <android/log.h>
#include <android/native_window.h>
#include <cstring>

#define LOG_TAG "VideoRenderer"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── GLSL Shaders ─────────────────────────────────────────────────────────────

static const char* kVertexShader = R"glsl(
attribute vec4 a_position;
attribute vec2 a_texCoord;
varying   vec2 v_texCoord;
void main() {
    gl_Position = a_position;
    v_texCoord  = a_texCoord;
}
)glsl";

// YUV420P → RGB (BT.601 limited range)
static const char* kFragmentShader = R"glsl(
precision mediump float;
varying   vec2      v_texCoord;
uniform   sampler2D u_textureY;
uniform   sampler2D u_textureU;
uniform   sampler2D u_textureV;

void main() {
    float y = texture2D(u_textureY, v_texCoord).r;
    float u = texture2D(u_textureU, v_texCoord).r - 0.5;
    float v = texture2D(u_textureV, v_texCoord).r - 0.5;

    float r = y + 1.402 * v;
    float g = y - 0.344136 * u - 0.714136 * v;
    float b = y + 1.772 * u;

    gl_FragColor = vec4(clamp(r, 0.0, 1.0),
                        clamp(g, 0.0, 1.0),
                        clamp(b, 0.0, 1.0),
                        1.0);
}
)glsl";

// Full-screen quad vertices: position (xy) + texcoord (uv)
static const GLfloat kQuadVertices[] = {
    // x      y     u     v
    -1.0f,  1.0f, 0.0f, 0.0f,   // top-left
    -1.0f, -1.0f, 0.0f, 1.0f,   // bottom-left
     1.0f,  1.0f, 1.0f, 0.0f,   // top-right
     1.0f, -1.0f, 1.0f, 1.0f,   // bottom-right
};

// ─── helpers ──────────────────────────────────────────────────────────────────

static GLuint compileShader(GLenum type, const char* src) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    GLint ok;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("Shader compile error: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static GLuint createProgram(const char* vs_src, const char* fs_src) {
    GLuint vs = compileShader(GL_VERTEX_SHADER,   vs_src);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fs_src);
    if (!vs || !fs) return 0;

    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);

    GLint ok;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetProgramInfoLog(prog, sizeof(log), nullptr, log);
        LOGE("Program link error: %s", log);
        glDeleteProgram(prog);
        prog = 0;
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    return prog;
}

// ─── VideoRenderer implementation ────────────────────────────────────────────

VideoRenderer::VideoRenderer()
    : native_window_(nullptr), egl_display_(EGL_NO_DISPLAY),
      egl_context_(EGL_NO_CONTEXT), egl_surface_(EGL_NO_SURFACE),
      program_(0), vbo_(0),
      tex_y_(0), tex_u_(0), tex_v_(0),
      frame_width_(0), frame_height_(0), initialized_(false) {
}

VideoRenderer::~VideoRenderer() {
    release();
}

bool VideoRenderer::init(ANativeWindow* window, int width, int height) {
    native_window_ = window;
    frame_width_   = width;
    frame_height_  = height;

    if (!initEGL()) return false;
    if (!initGL())  return false;

    initialized_ = true;
    LOGI("VideoRenderer initialized %dx%d", width, height);
    return true;
}

bool VideoRenderer::initEGL() {
    egl_display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (egl_display_ == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }

    EGLint major, minor;
    if (!eglInitialize(egl_display_, &major, &minor)) {
        LOGE("eglInitialize failed");
        return false;
    }

    EGLint attribs[] = {
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,   8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE,  8,
        EGL_ALPHA_SIZE, 0,
        EGL_NONE
    };

    EGLConfig config;
    EGLint num_configs;
    if (!eglChooseConfig(egl_display_, attribs, &config, 1, &num_configs) || num_configs == 0) {
        LOGE("eglChooseConfig failed");
        return false;
    }

    EGLint ctx_attribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    egl_context_ = eglCreateContext(egl_display_, config, EGL_NO_CONTEXT, ctx_attribs);
    if (egl_context_ == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed");
        return false;
    }

    egl_surface_ = eglCreateWindowSurface(egl_display_, config, native_window_, nullptr);
    if (egl_surface_ == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed");
        return false;
    }

    if (!eglMakeCurrent(egl_display_, egl_surface_, egl_surface_, egl_context_)) {
        LOGE("eglMakeCurrent failed");
        return false;
    }

    return true;
}

bool VideoRenderer::initGL() {
    // Compile shader program
    program_ = createProgram(kVertexShader, kFragmentShader);
    if (!program_) return false;

    // Vertex buffer
    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVertices), kQuadVertices, GL_STATIC_DRAW);

    // Three YUV plane textures
    glGenTextures(1, &tex_y_);
    glGenTextures(1, &tex_u_);
    glGenTextures(1, &tex_v_);

    auto setupTex = [](GLuint tex) {
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    };
    setupTex(tex_y_);
    setupTex(tex_u_);
    setupTex(tex_v_);

    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    return true;
}

void VideoRenderer::renderFrame(const uint8_t* y_plane, int y_stride,
                                 const uint8_t* u_plane, int u_stride,
                                 const uint8_t* v_plane, int v_stride) {
    if (!initialized_) return;

    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(program_);

    // Upload Y plane (full resolution)
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex_y_);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, y_stride, frame_height_,
                 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, y_plane);

    // Upload U plane (half resolution)
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, tex_u_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, u_stride, frame_height_ / 2,
                 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, u_plane);

    // Upload V plane (half resolution)
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, tex_v_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, v_stride, frame_height_ / 2,
                 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, v_plane);

    // Set uniforms
    glUniform1i(glGetUniformLocation(program_, "u_textureY"), 0);
    glUniform1i(glGetUniformLocation(program_, "u_textureU"), 1);
    glUniform1i(glGetUniformLocation(program_, "u_textureV"), 2);

    // Bind VBO and set attribute pointers
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    GLint pos_loc = glGetAttribLocation(program_, "a_position");
    GLint tex_loc = glGetAttribLocation(program_, "a_texCoord");

    glEnableVertexAttribArray(pos_loc);
    glVertexAttribPointer(pos_loc, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (void*)0);
    glEnableVertexAttribArray(tex_loc);
    glVertexAttribPointer(tex_loc, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (void*)(2 * sizeof(GLfloat)));

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(pos_loc);
    glDisableVertexAttribArray(tex_loc);

    eglSwapBuffers(egl_display_, egl_surface_);
}

void VideoRenderer::release() {
    if (!initialized_) return;
    initialized_ = false;

    if (tex_y_) { glDeleteTextures(1, &tex_y_); tex_y_ = 0; }
    if (tex_u_) { glDeleteTextures(1, &tex_u_); tex_u_ = 0; }
    if (tex_v_) { glDeleteTextures(1, &tex_v_); tex_v_ = 0; }
    if (vbo_)   { glDeleteBuffers(1, &vbo_);    vbo_   = 0; }
    if (program_){ glDeleteProgram(program_);    program_ = 0; }

    if (egl_display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(egl_display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (egl_context_ != EGL_NO_CONTEXT) eglDestroyContext(egl_display_, egl_context_);
        if (egl_surface_ != EGL_NO_SURFACE) eglDestroySurface(egl_display_, egl_surface_);
        eglTerminate(egl_display_);
        egl_display_ = EGL_NO_DISPLAY;
        egl_context_ = EGL_NO_CONTEXT;
        egl_surface_ = EGL_NO_SURFACE;
    }

    LOGI("VideoRenderer released");
}
