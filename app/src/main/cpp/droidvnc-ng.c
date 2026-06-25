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
#include <ifaddrs.h>
#include <net/if.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include "rfb/rfb.h"

#define TAG "droidvnc-ng (native)"

rfbScreenInfoPtr theScreen;
jclass theInputService;
jclass theMainService;
JavaVM *theVM;
/* Back buffer that is rendered to, swapped with the screen's framebuffer when done */
char *backBuffer;

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

/*
* Resolve interface name iface_name to its best IPv4/IPv6 addresses and write them
* into screen, disabling whichever family is absent. port is the base TCP port
* used for both families. A NULL or empty iface_name means "bind to any address".
* Returns 0 on success, -1 if a named interface has no usable address, -2 if screen is NULL.
*/
static int set_listener_addresses(rfbScreenInfoPtr screen, const char *iface_name, int port) {
    if (!screen) {
        return -2;
    }

    /* reset to "bind to any" defaults; the block below narrows from there */
    screen->port = port;
    screen->ipv6port = port;
    screen->listenInterface = htonl(INADDR_ANY);
    free(screen->listen6Interface); // must be freed by us if previously set via strdup(), free(NULL) is also safe
    screen->listen6Interface = NULL;

    if (!iface_name || !strlen(iface_name))
        return 0; // bind to any

    __android_log_print(ANDROID_LOG_INFO, TAG, "set_listener_addresses: resolving IPs for interface: %s", iface_name);
    struct ifaddrs *ifaddr, *ifa;
    int foundIpv4 = 0;
    int bestIpv6Prio = -1;  /* Track best IPv6 priority for this interface */
    if (getifaddrs(&ifaddr) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "set_listener_addresses: getifaddrs failed for interface %s: %s", iface_name, strerror(errno));
        return -1;
    }
    for (ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
        if (ifa->ifa_addr == NULL)
            continue;
        if (strcmp(ifa->ifa_name, iface_name) == 0) {
            if (ifa->ifa_addr->sa_family == AF_INET) {
                struct sockaddr_in *sa = (struct sockaddr_in *)ifa->ifa_addr;
                screen->listenInterface = sa->sin_addr.s_addr;
                __android_log_print(ANDROID_LOG_INFO, TAG, "set_listener_addresses: resolved interface %s to IPv4 %s", iface_name, inet_ntoa(sa->sin_addr));
                foundIpv4 = 1;
            }
#ifdef LIBVNCSERVER_IPv6
            if (ifa->ifa_addr->sa_family == AF_INET6) {
                struct sockaddr_in6 *sa6 = (struct sockaddr_in6 *)ifa->ifa_addr;
                const uint8_t *addr = sa6->sin6_addr.s6_addr;
                int prio = -1;  /* -1 = skip this address */

                if (IN6_IS_ADDR_MULTICAST(&sa6->sin6_addr) ||
                    IN6_IS_ADDR_UNSPECIFIED(&sa6->sin6_addr)) {
                    continue;  /* Skip multicast and unspecified */
                } else if ((addr[0] & 0xe0) == 0x20) {          /* 2000::/3 (bits 0-2=001) - global unicast, prio 4 */
                    prio = 4;
                } else if ((addr[0] & 0xfe) == 0xfc) {           /* fc00::/7 (bits 0-6=1111110) - ULA, prio 3 */
                    prio = 3;
                } else if (addr[0] == 0xfe && (addr[1] & 0xc0) == 0xc0) { /* fec0::/10 (bits 0-9=1111111000) - site-local, prio 2 */
                    prio = 2;
                } else if (IN6_IS_ADDR_LINKLOCAL(&sa6->sin6_addr)) {  /* fe80::/10 - link-local, prio 1 */
                    prio = 1;
                } else if (IN6_IS_ADDR_LOOPBACK(&sa6->sin6_addr)) {  /* ::1 - loopback for SSH tunneling, prio 0 */
                    prio = 0;
                }

                if (prio < 0) continue;  /* Skip unwanted address types */

                if (prio > bestIpv6Prio) {
                    /* Found a better IPv6 address */
                    char ipv6Addr[INET6_ADDRSTRLEN];
                    inet_ntop(AF_INET6, &(sa6->sin6_addr), ipv6Addr, INET6_ADDRSTRLEN);
                    free(screen->listen6Interface);  /* Free previous best */
                    screen->listen6Interface = strdup(ipv6Addr);
                    bestIpv6Prio = prio;
                    __android_log_print(ANDROID_LOG_INFO, TAG, "set_listener_addresses: resolved interface %s to IPv6 %s with prio %d", iface_name, ipv6Addr, prio);
                }
            }
#endif
        }
    }
    freeifaddrs(ifaddr);

    /* Handle single-family binding: disable the other family */
    if (foundIpv4 && bestIpv6Prio < 0) {
        /* Interface has only IPv4 - disable IPv6 */
        screen->ipv6port = -1;
        __android_log_print(ANDROID_LOG_INFO, TAG, "set_listener_addresses: interface %s has only IPv4, disabling IPv6", iface_name);
    } else if (!foundIpv4 && bestIpv6Prio >= 0) {
        /* Interface has only IPv6 - disable IPv4 */
        screen->port = -1;
        __android_log_print(ANDROID_LOG_INFO, TAG, "set_listener_addresses: interface %s has only IPv6, disabling IPv4", iface_name);
    }
    /* If interface was specified but no addresses found, fail */
    if (!foundIpv4 && bestIpv6Prio < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "set_listener_addresses: no IP addresses found for interface %s", iface_name);
        return -1;
    }

    return 0;
}


static void onPointerEvent(int buttonMask,int x,int y,rfbClientPtr cl)
{
    JNIEnv *env = NULL;
    if ((*theVM)->AttachCurrentThread(theVM, &env, NULL) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "onPointerEvent: could not attach thread, there will be no input");
        return;
    }

    /* needed to allow multiple dragging actions at once */
    cl->screen->pointerClient = NULL;

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

    //Charset charset = Charset.forName("ISO-8859-1")
    jclass clsCharset =  (*env)->FindClass(env,"java/nio/charset/Charset");
    jmethodID midCharsetForName = (*env)->GetStaticMethodID(env, clsCharset, "forName", "(Ljava/lang/String;)Ljava/nio/charset/Charset;");
    jobject charset = (*env)->CallStaticObjectMethod(env, clsCharset, midCharsetForName, (*env)->NewStringUTF(env, "ISO-8859-1"));

    //CharBuffer charBuffer = charset.decode(byteBuffer)
    jobject byteBuffer = (*env)->NewDirectByteBuffer(env, (jbyte *)text, len);
    jmethodID midCharsetDecode = (*env)->GetMethodID(env, clsCharset, "decode", "(Ljava/nio/ByteBuffer;)Ljava/nio/CharBuffer;");
    jobject charBuffer = (*env)->CallObjectMethod(env, charset, midCharsetDecode, byteBuffer);
    (*env)->DeleteLocalRef(env, byteBuffer);

    //String jText = charBuffer.toString();
    jclass clsCharBuffer = (*env)->FindClass(env, "java/nio/CharBuffer");
    jmethodID midCharBufferToString = (*env)->GetMethodID(env, clsCharBuffer, "toString", "()Ljava/lang/String;");
    jstring jText = (*env)->CallObjectMethod(env, charBuffer, midCharBufferToString);
    (*env)->DeleteLocalRef(env, charBuffer);

    jmethodID mid = (*env)->GetStaticMethodID(env, theInputService, "onCutText", "(Ljava/lang/String;J)V");
    (*env)->CallStaticVoidMethod(env, theInputService, mid, jText, (jlong)cl);

    (*env)->DeleteLocalRef(env, jText);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionDescribe(env);

    (*theVM)->DetachCurrentThread(theVM);
}

static void onCutTextUTF8(char *text, int len, rfbClientPtr cl)
{
    JNIEnv *env = NULL;
    if ((*theVM)->AttachCurrentThread(theVM, &env, NULL) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "onCutTextUTF8: could not attach thread, there will be no input");
        return;
    }

    //Charset charset = Charset.forName("UTF-8")
    jclass clsCharset =  (*env)->FindClass(env,"java/nio/charset/Charset");
    jmethodID midCharsetForName = (*env)->GetStaticMethodID(env, clsCharset, "forName", "(Ljava/lang/String;)Ljava/nio/charset/Charset;");
    jobject charset = (*env)->CallStaticObjectMethod(env, clsCharset, midCharsetForName, (*env)->NewStringUTF(env, "UTF-8"));

    //CharBuffer charBuffer = charset.decode(byteBuffer)
    jobject byteBuffer = (*env)->NewDirectByteBuffer(env, (jbyte *)text, len);
    jmethodID midCharsetDecode = (*env)->GetMethodID(env, clsCharset, "decode", "(Ljava/nio/ByteBuffer;)Ljava/nio/CharBuffer;");
    jobject charBuffer = (*env)->CallObjectMethod(env, charset, midCharsetDecode, byteBuffer);
    (*env)->DeleteLocalRef(env, byteBuffer);

    //String jText = charBuffer.toString();
    jclass clsCharBuffer = (*env)->FindClass(env, "java/nio/CharBuffer");
    jmethodID midCharBufferToString = (*env)->GetMethodID(env, clsCharBuffer, "toString", "()Ljava/lang/String;");
    jstring jText = (*env)->CallObjectMethod(env, charBuffer, midCharBufferToString);
    (*env)->DeleteLocalRef(env, charBuffer);

    jmethodID mid = (*env)->GetStaticMethodID(env, theInputService, "onCutText", "(Ljava/lang/String;J)V");
    (*env)->CallStaticVoidMethod(env, theInputService, mid, jText, (jlong)cl);

    (*env)->DeleteLocalRef(env, jText);

    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionDescribe(env);

    (*theVM)->DetachCurrentThread(theVM);
}

void onClientDisconnected(rfbClientPtr cl)
{
    JNIEnv *env = NULL;
    // check if already attached. happens on reverse connections
    (*theVM)->GetEnv(theVM, (void **) &env, JNI_VERSION_1_6);
    int wasAlreadyAttached = env != NULL;
    // AttachCurrentThread() on an already attached thread is a no-op. https://developer.android.com/training/articles/perf-jni#threads
    if ((*theVM)->AttachCurrentThread(theVM, &env, NULL) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "onClientDisconnected: could not attach thread, not calling MainService.onClientDisconnected()");
        return;
    }

    jmethodID mid = (*env)->GetStaticMethodID(env, theMainService, "onClientDisconnected", "(J)V");
    (*env)->CallStaticVoidMethod(env, theMainService, mid, (jlong)cl);

    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionDescribe(env);

    // only detach if not attached before
    if (!wasAlreadyAttached)
        (*theVM)->DetachCurrentThread(theVM);
}

#pragma clang diagnostic push
#pragma ide diagnostic ignored "ConstantFunctionResult"
static enum rfbNewClientAction onClientConnected(rfbClientPtr cl)
{
    // connect clientGoneHook
    cl->clientGoneHook = onClientDisconnected;

    /*
     * call the managed version of this function
     */
    JNIEnv *env = NULL;
    // check if already attached. happens on reverse connections
    (*theVM)->GetEnv(theVM, (void **) &env, JNI_VERSION_1_6);
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
#pragma clang diagnostic pop


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
    (*theVM)->GetEnv(theVM, (void**) &env, JNI_VERSION_1_6); // this will always succeed in JNI_OnLoad()
    theInputService = (*env)->NewGlobalRef(env, (*env)->FindClass(env, "net/christianbeier/droidvnc_ng/InputService"));
    theMainService = (*env)->NewGlobalRef(env, (*env)->FindClass(env, "net/christianbeier/droidvnc_ng/MainService"));

    rfbLog = logcat_info;
    rfbErr = logcat_err;
    rfbMaxClientWait = 5000;

    return JNI_VERSION_1_6;
}


JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncStopServer(__unused JNIEnv *env, __unused jobject thiz) {

    if(!theScreen)
        return JNI_FALSE;

    rfbShutdownServer(theScreen, TRUE);
    free(theScreen->frameBuffer);
    theScreen->frameBuffer = NULL;
    free(backBuffer);
    backBuffer = NULL;
    free((char*)theScreen->desktopName); // always malloc'ed by us
    free(theScreen->httpDir); // always malloc'ed by us
    theScreen->desktopName = NULL;
    free(theScreen->listen6Interface); // freed by us if we set it via strdup()
    theScreen->listen6Interface = NULL;
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


JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncStartServer(JNIEnv *env, jobject thiz, jint width, jint height, jstring listenIf, jint port, jstring desktopname, jstring password, jstring httpRootDir) {

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
    backBuffer = (char*)calloc(width * height * 4, 1);
    if(!theScreen->frameBuffer || !backBuffer) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "vncStartServer: failed allocating framebuffer");
        Java_net_christianbeier_droidvnc_1ng_MainService_vncStopServer(env, thiz);
        return JNI_FALSE;
    }
    theScreen->ptrAddEvent = onPointerEvent;
    theScreen->kbdAddEvent = onKeyEvent;
    theScreen->setXCutText = onCutText;
    theScreen->setXCutTextUTF8 = onCutTextUTF8;
    theScreen->newClientHook = onClientConnected;

    theScreen->port = port;
    theScreen->ipv6port = port;

    /* Handle binding to specific interface */
    if (listenIf) {
        const char *cListenIf = (*env)->GetStringUTFChars(env, listenIf, NULL);
        int rc = set_listener_addresses(theScreen, cListenIf, port);
        (*env)->ReleaseStringUTFChars(env, listenIf, cListenIf);
        if (rc != 0) {
            Java_net_christianbeier_droidvnc_1ng_MainService_vncStopServer(env, thiz);
            return JNI_FALSE;
        }
    }

    // don't show X cursor
    theScreen->cursor = NULL;
    // needed to allow multiple dragging actions at once
    theScreen->deferPtrUpdateTime = 0;

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

    if(httpRootDir) { // string arg to GetStringUTFChars() must not be NULL
        const char *cHttpRootDir = (*env)->GetStringUTFChars(env, httpRootDir, NULL);
        if(!cHttpRootDir) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "vncStartServer: failed getting http root dir from JNI");
            Java_net_christianbeier_droidvnc_1ng_MainService_vncStopServer(env, thiz);
            return JNI_FALSE;
        }
        theScreen->httpDir = strdup(cHttpRootDir);
        (*env)->ReleaseStringUTFChars(env, httpRootDir, cHttpRootDir);
    }

    rfbInitServer(theScreen);

    if (port != -1) {
        /* Only fail if both address families failed to initialize */
        if (theScreen->listenSock == RFB_INVALID_SOCKET && theScreen->listen6Sock == RFB_INVALID_SOCKET) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "vncStartServer: failed starting (%s)", strerror(errno));
            Java_net_christianbeier_droidvnc_1ng_MainService_vncStopServer(env, thiz);
            return JNI_FALSE;
        }
    }

    rfbRunEventLoop(theScreen, -1, TRUE);

    __android_log_print(ANDROID_LOG_INFO, TAG, "vncStartServer: successfully started");

    return JNI_TRUE;
}

// The MainService run this on a worker thread, in the worst case blocking for rfbMaxClientWait
JNIEXPORT jlong JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncConnectReverse(JNIEnv *env, __unused jobject thiz, jstring host, jint port)
{
    if(!theScreen || !theScreen->frameBuffer)
        return 0;

    if(host) { // string arg to GetStringUTFChars() must not be NULL
        char *cHost = (char*)(*env)->GetStringUTFChars(env, host, NULL);
        if(!cHost) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "vncConnectReverse: failed getting desktop name from JNI");
            return 0;
        }
        rfbClientPtr cl = rfbReverseConnection(theScreen, cHost, port);
        (*env)->ReleaseStringUTFChars(env, host, cHost);
        return (jlong) cl;
    }
    return 0;
}

// The MainService run this on a worker thread, in the worst case blocking for rfbMaxClientWait
JNIEXPORT jlong JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncConnectRepeater(JNIEnv *env, __unused jobject thiz, jstring host, jint port, jstring repeaterIdentifier)
{
    if(!theScreen || !theScreen->frameBuffer)
        return 0;

    if(host && repeaterIdentifier) { // string arg to GetStringUTFChars() must not be NULL
        char *cHost = (char*)(*env)->GetStringUTFChars(env, host, NULL);
        if(!cHost) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "vncConnectRepeater: failed getting desktop name from JNI");
            return 0;
        }
        char *cRepeaterIdentifier = (char*)(*env)->GetStringUTFChars(env, repeaterIdentifier, NULL);
        if(!cRepeaterIdentifier) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "vncConnectRepeater: failed getting repeater ID from JNI");
            return 0;
        }
        rfbClientPtr cl = rfbUltraVNCRepeaterMode2Connection(theScreen, cHost, port, cRepeaterIdentifier);
        (*env)->ReleaseStringUTFChars(env, host, cHost);
        (*env)->ReleaseStringUTFChars(env, repeaterIdentifier, cRepeaterIdentifier);
        return (jlong) cl;
    }
    return 0;
}


JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncNewFramebuffer(__unused JNIEnv *env, __unused jobject thiz, jint width, jint height)
{
    char *oldfb, *newfb;

    if(!theScreen || !theScreen->frameBuffer || !backBuffer)
        return JNI_FALSE;

    /* screen's framebuffer */
    oldfb = theScreen->frameBuffer;
    newfb = calloc(width * height * 4, 1);
    if(!newfb) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "vncNewFramebuffer: failed allocating new framebuffer");
        return JNI_FALSE;
    }

    rfbNewFramebuffer(theScreen, (char*)newfb, width, height, 8, 3, 4);

    free(oldfb);

    /* back buffer */
    free(backBuffer);
    backBuffer = calloc(width * height * 4, 1);

    __android_log_print(ANDROID_LOG_INFO, TAG, "vncNewFramebuffer: allocated new framebuffer, %dx%d", width, height);

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncUpdateFramebuffer(JNIEnv *env, jobject  __unused thiz, jobject buf, jint rowStride)
{
    void *cBuf = (*env)->GetDirectBufferAddress(env, buf);
    jlong bufSize = (*env)->GetDirectBufferCapacity(env, buf);

    if(!theScreen || !theScreen->frameBuffer || !backBuffer || !cBuf || bufSize < 0)
        return JNI_FALSE;

    /*
      Copy new frame to back buffer.
    */
    // only comment in when needed
    //double t0 = getTime();

    // Copy row by row, skipping padding bytes
    char *src = (char *)cBuf;
    char *dest = backBuffer;
    int rowSize = theScreen->width * 4; // pixelStride is always 4 for us
    for(int y = 0; y < theScreen->height; y++) {
        memcpy(dest, src, rowSize);
        src += rowStride;
        dest += rowSize;
    }

    // only comment in when needed
    //__android_log_print(ANDROID_LOG_DEBUG, TAG, "vncUpdateFramebuffer: copy took %.3f ms", (getTime()-t0)*1000);

    /* Lock out client reads. */
    rfbClientIteratorPtr iterator;
    rfbClientPtr cl;
    iterator = rfbGetClientIterator(theScreen);
    while ((cl = rfbClientIteratorNext(iterator))) {
        LOCK(cl->sendMutex);
    }
    rfbReleaseClientIterator(iterator);

    /* Swap frame buffers. */
    char *tmp = theScreen->frameBuffer;
    theScreen->frameBuffer = backBuffer;
    backBuffer = tmp;

    iterator = rfbGetClientIterator(theScreen);
    while ((cl = rfbClientIteratorNext(iterator))) {
        UNLOCK(cl->sendMutex);
    }
    rfbReleaseClientIterator(iterator);

    rfbMarkRectAsModified(theScreen, 0, 0, theScreen->width, theScreen->height);

    return JNI_TRUE;
}

JNIEXPORT jint JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncGetFramebufferWidth(__unused JNIEnv *env, jobject __unused thiz)
{
    if(!theScreen || !theScreen->frameBuffer)
        return -1;

    return theScreen->width;
}

JNIEXPORT jint JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncGetFramebufferHeight(__unused JNIEnv *env, jobject __unused thiz)
{
    if(!theScreen || !theScreen->frameBuffer)
        return -1;

    return theScreen->height;
}

JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncIsActive(__unused JNIEnv *env, __unused jobject thiz) {
    return theScreen && rfbIsActive(theScreen);
}

/*
 * Return the bound IPv4 address as a tri-state:
 *   NULL          -- IPv4 is disabled (port == -1)
 *   empty string  -- IPv4 enabled but the listening socket is no longer in LISTEN
 *                    state, e.g. some Android versions (12) drop it when the bound
 *                    interface goes down while others (16) keep it
 *   an IP string  -- IPv4 enabled and actually listening
 */
JNIEXPORT jstring JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncGetBoundIPv4(JNIEnv *env, __unused jobject thiz) {
    if (!theScreen || theScreen->port == -1) return NULL;  // IPv4 disabled
    int val = 0;
    socklen_t len = sizeof(val);
    if (getsockopt(theScreen->listenSock, SOL_SOCKET, SO_ACCEPTCONN, &val, &len) != 0 || val == 0)
        return (*env)->NewStringUTF(env, "");  // enabled but not listening
    struct in_addr addr;
    addr.s_addr = theScreen->listenInterface;
    return (*env)->NewStringUTF(env, inet_ntoa(addr));
}

/*
 * Return the bound IPv6 address as a tri-state:
 *   NULL          -- IPv6 is disabled (port == -1)
 *   empty string  -- IPv6 enabled but the listening socket is no longer in LISTEN
 *                    state, e.g. some Android versions (12) drop it when the bound
 *                    interface goes down while others (16) keep it
 *   an IP string  -- IPv6 enabled and actually listening
 */
JNIEXPORT jstring JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncGetBoundIPv6(JNIEnv *env, __unused jobject thiz) {
    if (!theScreen || theScreen->ipv6port == -1) return NULL;  // IPv6 disabled
    int val = 0;
    socklen_t len = sizeof(val);
    if (getsockopt(theScreen->listen6Sock, SOL_SOCKET, SO_ACCEPTCONN, &val, &len) != 0 || val == 0)
        return (*env)->NewStringUTF(env, "");  // enabled but not listening
    // listen6Interface NULL means libvncserver binds the wildcard; report it as such for symmetry with v4 returning "0.0.0.0"
    return (*env)->NewStringUTF(env, theScreen->listen6Interface ? theScreen->listen6Interface : "::");
}

JNIEXPORT jboolean JNICALL Java_net_christianbeier_droidvnc_1ng_MainService_vncRebindInterface(JNIEnv *env, __unused jobject thiz, jstring listenIf, jint port) {
    if (!theScreen) {
        return JNI_FALSE;
    }

    const char *cListenIf = listenIf ? (*env)->GetStringUTFChars(env, listenIf, NULL) : NULL;
    int rc = set_listener_addresses(theScreen, cListenIf, port);
    if (cListenIf) {
        (*env)->ReleaseStringUTFChars(env, listenIf, cListenIf);
    }

    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "vncRebindInterface: could not resolve listener addresses, keeping current sockets");
        return JNI_FALSE;
    }

    // Hand the actual close()/socket() swap to the listener thread so it cannot race its own select().
    rfbRequestListenRebind(theScreen);
    struct in_addr v4addr = { .s_addr = theScreen->listenInterface };
    __android_log_print(ANDROID_LOG_INFO, TAG, "vncRebindInterface: requested rebind (IPv4 %s port %d, IPv6 %s port %d)",
                        theScreen->port > 0 ? inet_ntoa(v4addr) : "disabled", theScreen->port,
                        theScreen->ipv6port > 0 ? (theScreen->listen6Interface ? theScreen->listen6Interface : "::") : "disabled", theScreen->ipv6port);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_net_christianbeier_droidvnc_1ng_MainService_vncSendCutText(JNIEnv *env, __unused jclass clazz,
                                                                jstring text) {
    if (!theScreen || !text)
        return;

    /*
     * Convert Android's UTF-8 to Latin-1.
     * Some viewers eat UTF-8 payload in the Latin-1 cuttext just well, but some don't, so adhere to
     * the spec and send Latin-1 here.
    */
    // text.getBytes("ISO-8859-1")
    jclass clsString = (*env)->FindClass(env, "java/lang/String");
    jmethodID midGetBytes = (*env)->GetMethodID(env, clsString, "getBytes", "(Ljava/lang/String;)[B");
    jstring jCharsetName = (*env)->NewStringUTF(env, "ISO-8859-1");
    jbyteArray latin1Bytes = (jbyteArray) (*env)->CallObjectMethod(env, text, midGetBytes, jCharsetName);

    // copy byte array contents to C char array on the stack, +1 for null-terminator
    jsize latin1BytesLength = (*env)->GetArrayLength(env, latin1Bytes);
    char cLatin1Text[latin1BytesLength + 1];
    (*env)->GetByteArrayRegion(env, latin1Bytes, 0, latin1BytesLength, (jbyte*)cLatin1Text);
    cLatin1Text[latin1BytesLength] = '\0'; // Null-terminate the C string

    // we can clean up local references here already, cLatin1Text is on the stack
    (*env)->DeleteLocalRef(env, jCharsetName);
    (*env)->DeleteLocalRef(env, latin1Bytes);

    /*
     * Get UTF-8 string, too
     */
    const char *cUTF8Text = (*env)->GetStringUTFChars(env, text, NULL);

    /*
     * Send!
     */
    rfbSendServerCutTextUTF8(theScreen, (char*) cUTF8Text, (int) strlen(cUTF8Text), cLatin1Text, (int) strlen(cLatin1Text));

    /*
     * Clean up
     */
    if (cUTF8Text)
         (*env)->ReleaseStringUTFChars(env, text, cUTF8Text);
}

JNIEXPORT jstring JNICALL
Java_net_christianbeier_droidvnc_1ng_MainService_vncGetRemoteHost(JNIEnv *env, __unused jobject thiz, jlong client) {
    return (*env)->NewStringUTF(env, ((rfbClientPtr)client)->host);
}

JNIEXPORT jboolean JNICALL
Java_net_christianbeier_droidvnc_1ng_MainService_vncDisconnect(__unused JNIEnv *env, __unused jobject thiz, jlong client) {
    rfbBool found = FALSE;
    rfbClientIteratorPtr iterator;
    rfbClientPtr cl;
    iterator = rfbGetClientIterator(theScreen);
    while ((cl = rfbClientIteratorNext(iterator)) != NULL) {
        if (cl == (rfbClientPtr) client) {
            found = TRUE;
            rfbCloseClient((rfbClientPtr) client);
            break;
        }
    }
    rfbReleaseClientIterator(iterator);
    return found;
}

JNIEXPORT jint JNICALL
Java_net_christianbeier_droidvnc_1ng_MainService_vncGetDestinationPort(__unused JNIEnv *env,
                                                                       __unused jobject thiz,
                                                                       jlong client) {
    int port = -1;
    rfbClientIteratorPtr iterator;
    rfbClientPtr cl;
    iterator = rfbGetClientIterator(theScreen);
    while (theScreen && (cl = rfbClientIteratorNext(iterator)) != NULL) {
        if (cl == (rfbClientPtr) client) {
            port = cl->destPort;
            break;
        }
    }
    rfbReleaseClientIterator(iterator);
    return port;
}

JNIEXPORT jstring JNICALL
Java_net_christianbeier_droidvnc_1ng_MainService_vncGetRepeaterId(JNIEnv *env,
                                                                  __unused jobject thiz,
                                                                  jlong client) {
    return (*env)->NewStringUTF(env, ((rfbClientPtr)client)->repeaterId);
}
