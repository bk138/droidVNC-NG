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
#include <errno.h>
#include "rfb/rfb.h"

#define TAG "droidvnc-ng (native)"

rfbScreenInfoPtr theScreen;
jclass theInputService;
jclass theMainService;
JavaVM *theVM;

/*
 * Modeled after rfbDefaultLog:
 *  - with Android log functions
 *  - without time stamping as the Android logging does this already
 */
static void logcat_info(const char *format, ...)
{
    va_list args;

    va_start(args, format);
    __android_log_vprint(ANDROID_LOG_INFO, TAG, format, args);
    va_end(args);
}

static void logcat_err(const char *format, ...)
{
    va_list args;

    va_start(args, format);
    __android_log_vprint(ANDROID_LOG_ERROR, TAG, format, args);
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
    JNIEnv *env = NULL;
    if ((*theVM)->AttachCurrentThread(theVM, &env, NULL) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "onPointerEvent: could not attach thread, there will be no input");
        return;
    }

    jmethodID mid = (*env)->GetStaticMethodID(env, theInputService, "onPointerEvent", "(IIIJ)V");
    (*env)->CallStaticVoidMethod(env, theInputService, mid, buttonMask, x, y, (jlong)cl);

    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionDescribe(env);

    (*theVM)->DetachCurrentThread(theVM);
}

static void onKeyEvent(rfbBool down, rfbKeySym key, rfbClientPtr cl)
{
    JNIEnv *env = NULL;
    if ((*theVM)->AttachCurrentThread(theVM, &env, NULL) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "onKeyEvent: could not attach thread, there will be no input");
        return;
    }

    jmethodID mid = (*env)->GetStaticMethodID(env, theInputService, "onKeyEvent", "(IJJ)V");
    (*env)->CallStaticVoidMethod(env, theInputService, mid, down, (jlong)key, (jlong)cl);

    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionDescribe(env);

    (*theVM)->DetachCurrentThread(theVM);
}

static void onCutText(char *text, int len, rfbClientPtr cl)
{
    JNIEnv *env = NULL;
    if ((*theVM)->AttachCurrentThread(theVM, &env, NULL) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "onCutText: could not attach thread, there will be no input");
        return;
    }

    jmethodID mid = (*env)->GetStaticMethodID(env, theInputService, "onCutText", "(Ljava/lang/String;J)V");
    jstring jText = (*env)->NewStringUTF(env, text);
    (*env)->CallStaticVoidMethod(env, theInputService, mid, jText, (jlong)cl);

    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionDescribe(env);

    (*theVM)->DetachCurrentThread(theVM);
}

void onClientDisconnected(rfbClientPtr cl)
{
    JNIEnv *env = NULL;
    if ((*theVM)->AttachCurrentThread(theVM, &env, NULL) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "onClientDisconnected: could not attach thread, not calling MainService.onClientDisconnected()");
        return;
    }

    jmethodID mid = (*env)->GetStaticMethodID(env, theMainService, "onClientDisconnected", "(J)V");
    (*env)->CallStaticVoidMethod(env, theMainService, mid, (jlong)cl);

    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionDescribe(env);

    (*theVM)->DetachCurrentThread(theVM);
}

static enum rfbNewClientAction onClientConnected(rfbClientPtr cl)
{
    // connect clientGoneHook
    cl->clientGoneHook = onClientDisconnected;

    /*
     * call the managed version of this function
     */
    JNIEnv *env = NULL;
    // check if already attached. happens on reverse connections
    (*theVM)->GetEnv(theVM, &env, JNI_VERSION_1_6);
    int wasAlreadyAttached = env != NULL;
    // AttachCurrentThread() on an already attached thread is a no-op. https://developer.android.com/training/articles/perf-jni#threads
    if ((*theVM)->AttachCurrentThread(theVM, &env, NULL) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "onClientConnected: could not attach thread, not calling MainService.onClientConnected()");
        return RFB_CLIENT_ACCEPT;
    }

    jmethodID mid = (*env)->GetStaticMethodID(env, theMainService, "onClientConnected", "(J)V");
    (*env)->CallStaticVoidMethod(env, theMainService, mid, (jlong)cl);

    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionDescribe(env);

    // only detach if not attached before
    if(!wasAlreadyAttached)
        (*theVM)->DetachCurrentThread(theVM);
    return RFB_CLIENT_ACCEPT;
}

static void clientGone(rfbClientPtr cl)
{
    __android_log_print(ANDROID_LOG_INFO, TAG, "Repeater connection closed.");
}

rfbClientPtr
repeaterConnection(rfbScreenInfoPtr rfbScreen,
                   char *repeaterHost,
                   int repeaterPort,
                   const char* repeaterIdentifier)
{
    rfbSocket sock;
    rfbClientPtr cl;
    char id[250];
    __android_log_print(ANDROID_LOG_INFO, TAG, "Connecting to a repeater Host: %s:%d.", repeaterHost, repeaterPort);

    if ((sock = rfbConnect(rfbScreen, repeaterHost, repeaterPort)) < 0)
        return (rfbClientPtr)NULL;

    memset(id, 0, sizeof(id));
    if(snprintf(id, sizeof(id), "ID:%s", repeaterIdentifier) >= (int)sizeof(id)) {
        /* truncated! */
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Error, given ID is too long.\n");
        return (rfbClientPtr)NULL;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Sending a repeater ID: %s.\n", id);
    if (send(sock, id, sizeof(id),0) != sizeof(id)) {
        rfbLog("writing id failed\n");
        return (rfbClientPtr)NULL;
    }
    cl = rfbNewClient(rfbScreen, sock);
    if (!cl) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "New client failed\n");
        return (rfbClientPtr)NULL;
    }

    cl->reverseConnection = 0;
    cl->clientGoneHook = clientGone;
    if (!cl->onHold)
        rfbStartOnHoldClient(cl);
    return cl;
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
    theMainService = (*env)->NewGlobalRef(env, (*env)->FindClass(env, "net/christianbeier/droidvnc_ng/MainService"));

    rfbLog = logcat_info;
    rfbErr = logcat_err;

    return JNI_VERSION_1_6;
}


JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncStopServer(JNIEnv *env, jobject thiz) {

    if(!theScreen)
        return JNI_FALSE;

    rfbShutdownServer(theScreen, TRUE);
    free(theScreen->frameBuffer);
    theScreen->frameBuffer = NULL;
    free(theScreen->desktopName); // always malloc'ed by us
    theScreen->desktopName = NULL;
    if(theScreen->authPasswdData) { // if this is set, it was malloc'ed by us and has one password in there
        char **passwordList = theScreen->authPasswdData;
        free(passwordList[0]); // free the password created by strdup()
        free(theScreen->authPasswdData); // and free the malloc'ed list, theScreen->authPasswdData is NULLed by rfbGetScreen()
    }
    rfbScreenCleanup(theScreen);
    theScreen = NULL;

    __android_log_print(ANDROID_LOG_INFO, TAG, "vncStopServer: successfully stopped");

    return JNI_TRUE;
}


JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncStartServer(JNIEnv *env, jobject thiz, jint width, jint height, jint port, jstring desktopname, jstring password) {

    int argc = 0;

    if(theScreen)
        return JNI_FALSE;

    rfbRegisterTightVNCFileTransferExtension();

    theScreen=rfbGetScreen(&argc, NULL, width, height, 8, 3, 4);
    if(!theScreen) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "vncStartServer: failed allocating rfb screen");
        return JNI_FALSE;
    }

    theScreen->frameBuffer=(char*)calloc(width * height * 4, 1);
    if(!theScreen->frameBuffer) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "vncStartServer: failed allocating framebuffer");
        Java_net_christianbeier_droidvnc_1ng_MainService_vncStopServer(env, thiz);
        return JNI_FALSE;
    }
    theScreen->ptrAddEvent = onPointerEvent;
    theScreen->kbdAddEvent = onKeyEvent;
    theScreen->setXCutText = onCutText;
    theScreen->setXCutTextUTF8 = onCutText;
    theScreen->newClientHook = onClientConnected;

    theScreen->port = port;
    theScreen->ipv6port = port;

    if(desktopname) { // string arg to GetStringUTFChars() must not be NULL
        const char *cDesktopName = (*env)->GetStringUTFChars(env, desktopname, NULL);
        if(!cDesktopName) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "vncStartServer: failed getting desktop name from JNI");
            theScreen->desktopName = strdup("Android"); // vncStopServer() must have something to correctly free()
            Java_net_christianbeier_droidvnc_1ng_MainService_vncStopServer(env, thiz);
            return JNI_FALSE;
        }
        theScreen->desktopName = strdup(cDesktopName);
        (*env)->ReleaseStringUTFChars(env, desktopname, cDesktopName);
    } else
        theScreen->desktopName = strdup("Android");

    if(password && (*env)->GetStringLength(env, password)) { // string arg to GetStringUTFChars() must not be NULL and also do not set an empty password
        char **passwordList = malloc(sizeof(char **) * 2);
        if(!passwordList) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "vncStartServer: failed allocating password list");
            Java_net_christianbeier_droidvnc_1ng_MainService_vncStopServer(env, thiz);
            return JNI_FALSE;
        }
        const char *cPassword = (*env)->GetStringUTFChars(env, password, NULL);
        if(!cPassword) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "vncStartServer: failed getting password from JNI");
            Java_net_christianbeier_droidvnc_1ng_MainService_vncStopServer(env, thiz);
            return JNI_FALSE;
        }
        passwordList[0] = strdup(cPassword);
        passwordList[1] = NULL;
        theScreen->authPasswdData = (void *) passwordList;
        theScreen->passwordCheck = rfbCheckPasswordByList;
        (*env)->ReleaseStringUTFChars(env, password, cPassword);
    }


    rfbInitServer(theScreen);

    if(theScreen->listenSock == RFB_INVALID_SOCKET || theScreen->listen6Sock == RFB_INVALID_SOCKET) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "vncStartServer: failed starting (%s)", strerror(errno));
        Java_net_christianbeier_droidvnc_1ng_MainService_vncStopServer(env, thiz);
        return JNI_FALSE;
    }

    rfbRunEventLoop(theScreen, -1, TRUE);

    __android_log_print(ANDROID_LOG_INFO, TAG, "vncStartServer: successfully started");

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncConnectReverse(JNIEnv *env, jobject thiz, jstring host, jint port)
{
    if(!theScreen || !theScreen->frameBuffer)
        return JNI_FALSE;

    if(host) { // string arg to GetStringUTFChars() must not be NULL
        char *cHost = (*env)->GetStringUTFChars(env, host, NULL);
        if(!cHost) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "vncConnectReverse: failed getting desktop name from JNI");
            return JNI_FALSE;
        }
        rfbClientPtr cl = rfbReverseConnection(theScreen, cHost, port);
        (*env)->ReleaseStringUTFChars(env, host, cHost);
        return cl != NULL;
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncConnectRepeater(JNIEnv *env, jobject thiz, jstring host, jint port, jstring repeaterIdentifier)
{
    if(!theScreen || !theScreen->frameBuffer)
        return JNI_FALSE;

    if(host) { // string arg to GetStringUTFChars() must not be NULL
        char *cHost = (*env)->GetStringUTFChars(env, host, NULL);
        if(!cHost) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "vncConnectRepeater: failed getting desktop name from JNI");
            return JNI_FALSE;
        }
        char *cRepeaterIdentifier = (*env)->GetStringUTFChars(env, repeaterIdentifier, NULL);
        if(!cRepeaterIdentifier) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "vncConnectRepeater: failed getting repeater ID from JNI");
            return JNI_FALSE;
        }
        rfbClientPtr cl = repeaterConnection(theScreen, cHost, port, cRepeaterIdentifier);
        (*env)->ReleaseStringUTFChars(env, host, cHost);
        (*env)->ReleaseStringUTFChars(env, repeaterIdentifier, cRepeaterIdentifier);
        return cl != NULL;
    }
    return JNI_FALSE;
}


JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncNewFramebuffer(JNIEnv *env, jobject thiz, jint width, jint height)
{
    rfbClientIteratorPtr iterator;
    rfbClientPtr cl;

    char *oldfb, *newfb;

    if(!theScreen || !theScreen->frameBuffer)
        return JNI_FALSE;

    oldfb = theScreen->frameBuffer;
    newfb = calloc(width * height * 4, 1);
    if(!newfb) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "vncNewFramebuffer: failed allocating new framebuffer");
        return JNI_FALSE;
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

    return JNI_TRUE;
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

JNIEXPORT jint JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncGetFramebufferWidth(JNIEnv *env, jobject __unused thiz)
{
    if(!theScreen || !theScreen->frameBuffer)
        return -1;

    return theScreen->width;
}

JNIEXPORT jint JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncGetFramebufferHeight(JNIEnv *env, jobject __unused thiz)
{
    if(!theScreen || !theScreen->frameBuffer)
        return -1;

    return theScreen->height;
}
