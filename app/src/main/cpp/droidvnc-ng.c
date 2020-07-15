/*
 * DroidVNC-NG native libdroidvnc_ng JNI library.
 *
 * Author: Christian Beier <info@christianbeier.net>
 *
 * Copyright (C) 2020 Kitchen Armor.
 *
 * You can redistribute and/or modify this program under the terms of the
 * GNU General Public License version 2 as published by the Free Software
 * Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place Suite 330, Boston, MA 02111-1307, USA.
 */

#include <jni.h>
#include <android/log.h>
#include <time.h>
#include "rfb/rfb.h"

#define TAG "droidvnc-ng (native)"

rfbScreenInfoPtr theScreen;

/*
 * Modeled after rfbDefaultLog:
 *  - with Android log functions
 *  - without time stamping as the Android logging does this already
 */
static void logcat_logger(const char *format, ...)
{
    va_list args;

    va_start(args, format);
    __android_log_vprint(ANDROID_LOG_INFO, TAG, format, args);
    va_end(args);
}


/**
 * @return Current time in floating point seconds.
 */
static double getTime()
{
    struct timeval tv;
    if (gettimeofday(&tv, NULL) < 0)
        return 0.0;
    else
        return (double) tv.tv_sec + ((double) tv.tv_usec / 1000000.);
}


/*
 * The VM calls JNI_OnLoad when the native library is loaded (for example, through System.loadLibrary).
 * JNI_OnLoad must return the JNI version needed by the native library.
 * We use this to wire up LibVNCServer logging to logcat.
 */
JNIEXPORT jint JNI_OnLoad(JavaVM __unused * vm, void __unused * reserved) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "loading, using LibVNCServer %s\n", LIBVNCSERVER_VERSION);

    rfbLog = logcat_logger;
    rfbErr = logcat_logger;

    return JNI_VERSION_1_6;
}


JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainActivity_vncStartServer(JNIEnv *env, jobject thiz, jint width, jint height) {

    int argc = 0;

    theScreen=rfbGetScreen(&argc, NULL, width, height, 8, 3, 4);
    if(!theScreen)
        return JNI_FALSE;

    theScreen->frameBuffer=(char*)malloc(width * height * 4);

    rfbInitServer(theScreen);
    rfbRunEventLoop(theScreen, -1, TRUE);

    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_net_christianbeier_droidvnc_1ng_MainActivity_vncNewFramebuffer(JNIEnv *env, jobject thiz, jint width, jint height) {

    char *oldfb, *newfb;

    oldfb = theScreen->frameBuffer;
    newfb = malloc(width * height * 4);
    if(!newfb) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "vncNewFramebuffer: failed allocating new framebuffer");
        return;
    }

    rfbNewFramebuffer(theScreen, (char*)newfb, width, height, 8, 3, 4);

    free(oldfb);
    __android_log_print(ANDROID_LOG_INFO, TAG, "vncNewFramebuffer: allocated new framebuffer, %dx%d", width, height);
}

JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainActivity_vncUpdateFramebuffer(JNIEnv *env, jobject  __unused thiz, jobject buf)
{
    void *cBuf = (*env)->GetDirectBufferAddress(env, buf);
    jlong bufSize = (*env)->GetDirectBufferCapacity(env, buf);

    if(!cBuf || bufSize < 0)
        return JNI_FALSE;

    double t0 = getTime();
    memcpy(theScreen->frameBuffer, cBuf, bufSize);
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "vncUpdateFramebuffer: copy took %.3f ms", (getTime()-t0)*1000);

    rfbMarkRectAsModified(theScreen, 0, 0, theScreen->width, theScreen->height);

    return JNI_TRUE;
}