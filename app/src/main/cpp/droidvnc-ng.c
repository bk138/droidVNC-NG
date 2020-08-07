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
jclass theInputService;
JavaVM *theVM;

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


static void onPointerEvent(int buttonMask,int x,int y,rfbClientPtr cl)
{
    if (buttonMask) {

        JNIEnv *env = NULL;
        if ((*theVM)->AttachCurrentThread(theVM, &env, NULL) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "onPointerEvent: could not attach thread, there will be no input");
            return;
        }

        // left mouse button
        if (buttonMask & (1 << 0)) {
            jmethodID mid = (*env)->GetStaticMethodID(env, theInputService, "tap", "(II)V");
            (*env)->CallStaticVoidMethod(env, theInputService, mid, x, y);
        }

        // right mouse button
        if (buttonMask & (1 << 2)) {
            jmethodID mid = (*env)->GetStaticMethodID(env, theInputService, "longPress", "(II)V");
            (*env)->CallStaticVoidMethod(env, theInputService, mid, x, y);
        }

        // scroll up
        if (buttonMask & (1 << 3)) {
            jmethodID mid = (*env)->GetStaticMethodID(env, theInputService, "scroll", "(III)V");
            (*env)->CallStaticVoidMethod(env, theInputService, mid, x, y, -cl->screen->height/2);
        }

        // scroll down
        if (buttonMask & (1 << 4)) {
            jmethodID mid = (*env)->GetStaticMethodID(env, theInputService, "scroll", "(III)V");
            (*env)->CallStaticVoidMethod(env, theInputService, mid, x, y, cl->screen->height/2);
        }

        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionDescribe(env);

        (*theVM)->DetachCurrentThread(theVM);

    }

}



/*
 * The VM calls JNI_OnLoad when the native library is loaded (for example, through System.loadLibrary).
 * JNI_OnLoad must return the JNI version needed by the native library.
 * We use this to wire up LibVNCServer logging to logcat.
 */
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void __unused * reserved) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "loading, using LibVNCServer %s\n", LIBVNCSERVER_VERSION);

    theVM = vm;

    /*
     * https://developer.android.com/training/articles/perf-jni#faq_FindClass
     * and
     * https://stackoverflow.com/a/17449108/361413
    */
    JNIEnv *env = NULL;
    (*theVM)->GetEnv(theVM, &env, JNI_VERSION_1_6); // this will always succeed in JNI_OnLoad()
    theInputService = (*env)->NewGlobalRef(env, (*env)->FindClass(env, "net/christianbeier/droidvnc_ng/InputService"));

    rfbLog = logcat_logger;
    rfbErr = logcat_logger;

    return JNI_VERSION_1_6;
}


JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncStartServer(JNIEnv *env, jobject thiz, jint width, jint height) {

    int argc = 0;

    if(theScreen)
        return JNI_FALSE;

    theScreen=rfbGetScreen(&argc, NULL, width, height, 8, 3, 4);
    if(!theScreen)
        return JNI_FALSE;

    theScreen->frameBuffer=(char*)calloc(width * height * 4, 1);
    theScreen->ptrAddEvent = onPointerEvent;

    rfbRegisterTightVNCFileTransferExtension();

    rfbInitServer(theScreen);
    rfbRunEventLoop(theScreen, -1, TRUE);

    __android_log_print(ANDROID_LOG_INFO, TAG, "vncStartServer: successfully started");

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncStopServer(JNIEnv *env, jobject thiz) {

    if(!theScreen)
        return JNI_FALSE;

    rfbShutdownServer(theScreen, TRUE);
    free(theScreen->frameBuffer);
    theScreen->frameBuffer = NULL;
    rfbScreenCleanup(theScreen);
    theScreen = NULL;

    __android_log_print(ANDROID_LOG_INFO, TAG, "vncStopServer: successfully stopped");

    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncNewFramebuffer(JNIEnv *env, jobject thiz, jint width, jint height)
{
    rfbClientIteratorPtr iterator;
    rfbClientPtr cl;

    char *oldfb, *newfb;

    oldfb = theScreen->frameBuffer;
    newfb = calloc(width * height * 4, 1);
    if(!newfb) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "vncNewFramebuffer: failed allocating new framebuffer");
        return;
    }

    /* Lock out client reads. */
    iterator = rfbGetClientIterator(theScreen);
    while ((cl = rfbClientIteratorNext(iterator))) {
        LOCK(cl->sendMutex);
    }
    rfbReleaseClientIterator(iterator);

    rfbNewFramebuffer(theScreen, (char*)newfb, width, height, 8, 3, 4);

    /* Swapping frame buffers finished, re-enable client reads. */
    iterator=rfbGetClientIterator(theScreen);
    while((cl=rfbClientIteratorNext(iterator))) {
        UNLOCK(cl->sendMutex);
    }
    rfbReleaseClientIterator(iterator);

    free(oldfb);
    __android_log_print(ANDROID_LOG_INFO, TAG, "vncNewFramebuffer: allocated new framebuffer, %dx%d", width, height);
}

JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncUpdateFramebuffer(JNIEnv *env, jobject  __unused thiz, jobject buf)
{
    void *cBuf = (*env)->GetDirectBufferAddress(env, buf);
    jlong bufSize = (*env)->GetDirectBufferCapacity(env, buf);

    if(!theScreen || !theScreen->frameBuffer || !cBuf || bufSize < 0)
        return JNI_FALSE;

    double t0 = getTime();
    memcpy(theScreen->frameBuffer, cBuf, bufSize);
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "vncUpdateFramebuffer: copy took %.3f ms", (getTime()-t0)*1000);

    rfbMarkRectAsModified(theScreen, 0, 0, theScreen->width, theScreen->height);

    return JNI_TRUE;
}