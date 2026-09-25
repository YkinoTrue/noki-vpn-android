//go:build android

/* SPDX-License-Identifier: Apache-2.0
 * Narrow JNI bridge for a single existing AppVpnService/TUN owner.
 */

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <unistd.h>

extern int nokiTurnOn(int tun_fd, char *config);
extern void nokiTurnOff(int handle);
extern char *nokiStats(int handle);
extern int nokiGenerateKeyPair(unsigned char *out);

static pthread_mutex_t guard = PTHREAD_MUTEX_INITIALIZER;
static JavaVM *vm;
static jobject service_ref;
static int active_handle = -1;
static int starting;

int noki_protect_socket(int fd)
{
    JNIEnv *env = NULL;
    int attached = 0;
    if (vm == NULL)
        return 0;
    jint status = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK)
            return 0;
        attached = 1;
    } else if (status != JNI_OK)
        return 0;
    pthread_mutex_lock(&guard);
    jobject service = service_ref ? (*env)->NewLocalRef(env, service_ref) : NULL;
    pthread_mutex_unlock(&guard);
    if (service == NULL) {
        if (attached)
            (*vm)->DetachCurrentThread(vm);
        return 0;
    }
    jclass cls = (*env)->GetObjectClass(env, service);
    jmethodID protect = cls ? (*env)->GetMethodID(env, cls, "protect", "(I)Z") : NULL;
    jboolean ok = protect ? (*env)->CallBooleanMethod(env, service, protect, fd) : JNI_FALSE;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        ok = JNI_FALSE;
    }
    if (cls)
        (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, service);
    if (attached)
        (*vm)->DetachCurrentThread(vm);
    return ok == JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_noki_vpn_vpn_NativeWireGuard_startNative(JNIEnv *env, jobject self,
                                                  jobject service, jint tun_fd,
                                                  jstring config)
{
    (void)self;
    if (tun_fd < 0 || service == NULL || config == NULL) {
        if (tun_fd >= 0)
            close(tun_fd);
        return -1;
    }
    pthread_mutex_lock(&guard);
    if (starting || active_handle >= 0) {
        pthread_mutex_unlock(&guard);
        close(tun_fd);
        return -1;
    }
    (*env)->GetJavaVM(env, &vm);
    service_ref = (*env)->NewGlobalRef(env, service);
    if (service_ref == NULL) {
        pthread_mutex_unlock(&guard);
        close(tun_fd);
        return -1;
    }
    starting = 1;
    pthread_mutex_unlock(&guard);

    const char *raw = (*env)->GetStringUTFChars(env, config, NULL);
    int handle = raw ? nokiTurnOn(tun_fd, (char *)raw) : -1;
    if (raw)
        (*env)->ReleaseStringUTFChars(env, config, raw);
    else
        close(tun_fd);

    pthread_mutex_lock(&guard);
    starting = 0;
    active_handle = handle;
    if (handle < 0) {
        (*env)->DeleteGlobalRef(env, service_ref);
        service_ref = NULL;
    }
    pthread_mutex_unlock(&guard);
    return handle;
}

JNIEXPORT void JNICALL
Java_com_noki_vpn_vpn_NativeWireGuard_stopNative(JNIEnv *env, jobject self, jint handle)
{
    (void)self;
    pthread_mutex_lock(&guard);
    int owned = handle >= 0 && active_handle == handle;
    pthread_mutex_unlock(&guard);
    if (!owned)
        return;
    nokiTurnOff(handle);
    pthread_mutex_lock(&guard);
    if (active_handle == handle) {
        active_handle = -1;
        (*env)->DeleteGlobalRef(env, service_ref);
        service_ref = NULL;
    }
    pthread_mutex_unlock(&guard);
}

JNIEXPORT jstring JNICALL
Java_com_noki_vpn_vpn_NativeWireGuard_statsNative(JNIEnv *env, jobject self, jint handle)
{
    (void)self;
    char *stats = nokiStats(handle);
    if (stats == NULL)
        return NULL;
    jstring result = (*env)->NewStringUTF(env, stats);
    free(stats);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_noki_vpn_data_NativeWireGuardKeys_generateNative(JNIEnv *env, jobject self)
{
    (void)self;
    unsigned char pair[64];
    if (nokiGenerateKeyPair(pair) != 1)
        return NULL;
    jbyteArray result = (*env)->NewByteArray(env, 64);
    if (result != NULL)
        (*env)->SetByteArrayRegion(env, result, 0, 64, (const jbyte *)pair);
    volatile unsigned char *wipe = pair;
    for (size_t i = 0; i < sizeof(pair); i++)
        wipe[i] = 0;
    return result;
}
