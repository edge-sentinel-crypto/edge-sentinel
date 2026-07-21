/*
 * Edge Sentinel — Native DIAG Interface
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 * Proprietary and confidential.
 *
 * Clean-room implementation based on Qualcomm DIAG protocol specification.
 * No third-party code.
 *
 * ---------------------------------------------------------------------------
 * Qualcomm DIAG userspace interface for Android (JNI)
 *
 * Provides native access to /dev/diag on rooted Qualcomm-based Android
 * devices. The Qualcomm DIAG driver exposes a character device that carries
 * baseband diagnostic messages including radio signaling logs for
 * GSM, WCDMA, and LTE.
 *
 * Protocol reference:
 *   - Qualcomm document 80-V1294-1 (DIAG ICD)
 *   - Linux kernel: drivers/char/diag/ (MSM DIAG driver source)
 *   - DIAG ioctl interface defined in diagchar.h kernel header
 *
 * Architecture:
 *   Kotlin (DiagBridge.kt) --JNI--> this library --> /dev/diag (kernel)
 *
 * Safe to load on non-rooted devices; all functions validate state before
 * performing I/O and return error codes rather than crashing.
 * ---------------------------------------------------------------------------
 */

#include <jni.h>
#include <android/log.h>

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

/* ---------- Logging macros ---------- */

#define TAG "EdgeSentinel_Diag"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ---------- Qualcomm DIAG driver constants ---------- */

/*
 * DIAG_IOCTL_SWITCH_LOGGING — switches the DIAG driver between logging
 * modes. Value 7 is the standard ioctl number defined in the MSM kernel
 * DIAG char driver (diagchar.h).
 */
#define DIAG_IOCTL_SWITCH_LOGGING   7

/*
 * MEMORY_DEVICE_MODE (2) — routes DIAG output to a userspace-readable
 * memory buffer instead of the default USB/UART transport.
 */
#define DIAG_MODE_MEMORY_DEVICE     2

/* Read buffer capacity (1 MB — matches typical DIAG transfer limits) */
#define READ_BUF_CAPACITY  (1024 * 1024)

/*
 * Logging mode parameter structure used by newer Qualcomm kernels.
 * Older kernels accept a plain integer; newer kernels (post MSM commit
 * ae92f0b2) require a pointer to this packed struct.
 */
struct diag_log_mode_param {
    uint32_t req_mode;
    uint32_t peripheral_mask;
    uint8_t  mode_param;
} __attribute__((packed));

/* ---------- Module state ---------- */

/* File descriptor for /dev/diag; -1 when closed */
static int g_diag_fd = -1;

/* Static read buffer to avoid repeated heap allocations */
static char g_read_buf[READ_BUF_CAPACITY];

/* =======================================================================
 * JNI: nativeCheckDiagDevice
 *
 * Probes whether /dev/diag exists and is a character device.
 * Does not open the device — no root privileges required.
 *
 * @return JNI_TRUE if the DIAG character device is present
 * ======================================================================= */
JNIEXPORT jboolean JNICALL
Java_com_bp22intel_edgesentinel_diag_DiagBridge_nativeCheckDiagDevice(
    JNIEnv *env __attribute__((unused)),
    jobject thiz __attribute__((unused)))
{
    struct stat sb;

    if (stat("/dev/diag", &sb) != 0) {
        LOGD("checkDiagDevice: /dev/diag not present (%s)", strerror(errno));
        return JNI_FALSE;
    }

    if (!S_ISCHR(sb.st_mode)) {
        LOGW("checkDiagDevice: /dev/diag exists but is not a character device");
        return JNI_FALSE;
    }

    LOGD("checkDiagDevice: /dev/diag character device found");
    return JNI_TRUE;
}

/* =======================================================================
 * JNI: nativeOpenDiag
 *
 * Opens /dev/diag in read-write mode and switches the Qualcomm DIAG
 * driver into memory-device mode so baseband log messages are routed
 * to userspace reads.
 *
 * Two ioctl calling conventions are attempted for kernel compatibility:
 *   1. Integer argument (legacy MSM kernels)
 *   2. Pointer to diag_log_mode_param (newer MSM kernels)
 *
 * @return non-negative file descriptor on success; negative errno on failure
 * ======================================================================= */
JNIEXPORT jint JNICALL
Java_com_bp22intel_edgesentinel_diag_DiagBridge_nativeOpenDiag(
    JNIEnv *env __attribute__((unused)),
    jobject thiz __attribute__((unused)))
{
    if (g_diag_fd >= 0) {
        LOGW("openDiag: already open (fd=%d)", g_diag_fd);
        return g_diag_fd;
    }

    int fd = open("/dev/diag", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        int err = errno;
        LOGE("openDiag: open failed — %s", strerror(err));
        return -err;
    }

    /*
     * Switch to memory-device mode.  Try the simple integer form first
     * (works on older kernels), then fall back to the struct form.
     */
    int rc = ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, DIAG_MODE_MEMORY_DEVICE);
    if (rc < 0) {
        int first_err = errno;

        struct diag_log_mode_param param = {
            .req_mode       = DIAG_MODE_MEMORY_DEVICE,
            .peripheral_mask = 0,
            .mode_param      = 1
        };
        rc = ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &param);

        if (rc < 0) {
            LOGE("openDiag: SWITCH_LOGGING ioctl failed — int: %s, struct: %s",
                 strerror(first_err), strerror(errno));
            close(fd);
            return -errno;
        }
    }

    g_diag_fd = fd;
    LOGI("openDiag: success (fd=%d)", g_diag_fd);
    return g_diag_fd;
}

/* =======================================================================
 * JNI: nativeCloseDiag
 *
 * Closes the DIAG device.  Safe to call when not open.
 * ======================================================================= */
JNIEXPORT void JNICALL
Java_com_bp22intel_edgesentinel_diag_DiagBridge_nativeCloseDiag(
    JNIEnv *env __attribute__((unused)),
    jobject thiz __attribute__((unused)))
{
    if (g_diag_fd >= 0) {
        LOGI("closeDiag: closing fd=%d", g_diag_fd);
        close(g_diag_fd);
        g_diag_fd = -1;
    }
}

/* =======================================================================
 * JNI: nativeReadDiag
 *
 * Performs a blocking read on /dev/diag and returns raw bytes to the
 * caller.  The Qualcomm DIAG device produces frames in the format:
 *
 *   [type:u32] [count:u32] [length:u32] [payload ...] ...
 *
 * Parsing and CRC validation are handled on the Kotlin side
 * (DiagMessageParser).
 *
 * @return byte[] with raw DIAG data; empty array on zero-length read;
 *         null if device is not open or on read error
 * ======================================================================= */
JNIEXPORT jbyteArray JNICALL
Java_com_bp22intel_edgesentinel_diag_DiagBridge_nativeReadDiag(
    JNIEnv *env,
    jobject thiz __attribute__((unused)))
{
    if (g_diag_fd < 0) {
        return NULL;
    }

    ssize_t n = read(g_diag_fd, g_read_buf, READ_BUF_CAPACITY);

    if (n == 0) {
        /* Interrupted / empty — return zero-length array, not null */
        return (*env)->NewByteArray(env, 0);
    }

    if (n < 0) {
        LOGE("readDiag: error — %s", strerror(errno));
        return NULL;
    }

    if (n == READ_BUF_CAPACITY) {
        LOGW("readDiag: buffer full — possible truncation");
    }

    jbyteArray out = (*env)->NewByteArray(env, (jsize)n);
    if (out == NULL) {
        return NULL;  /* JVM OOM */
    }

    (*env)->SetByteArrayRegion(env, out, 0, (jsize)n, (const jbyte *)g_read_buf);
    return out;
}

/* =======================================================================
 * JNI: nativeWriteDiag
 *
 * Sends a pre-framed command to /dev/diag (e.g. log-mask configuration,
 * extended message configuration, or event-mask setup).  The Kotlin
 * layer is responsible for constructing valid DIAG command frames.
 *
 * @param data  byte[] containing the command to send
 * @return number of bytes written on success; negative errno on failure
 * ======================================================================= */
JNIEXPORT jint JNICALL
Java_com_bp22intel_edgesentinel_diag_DiagBridge_nativeWriteDiag(
    JNIEnv *env,
    jobject thiz __attribute__((unused)),
    jbyteArray data)
{
    if (g_diag_fd < 0) {
        return -EBADF;
    }

    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0 || (size_t)len > READ_BUF_CAPACITY) {
        return -EINVAL;
    }

    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (buf == NULL) {
        return -ENOMEM;
    }

    ssize_t written = write(g_diag_fd, buf, (size_t)len);

    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);

    if (written < 0) {
        int err = errno;
        LOGE("writeDiag: error — %s", strerror(err));
        return -err;
    }

    return (jint)written;
}

/* =======================================================================
 * JNI: nativeIsOpen
 *
 * @return JNI_TRUE if the DIAG device is currently open
 * ======================================================================= */
JNIEXPORT jboolean JNICALL
Java_com_bp22intel_edgesentinel_diag_DiagBridge_nativeIsOpen(
    JNIEnv *env __attribute__((unused)),
    jobject thiz __attribute__((unused)))
{
    return (g_diag_fd >= 0) ? JNI_TRUE : JNI_FALSE;
}
