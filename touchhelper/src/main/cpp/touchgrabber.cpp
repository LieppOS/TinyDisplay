#include <jni.h>
#include <linux/input.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <string.h>

static int fdFromJava(JNIEnv* env, jobject fileDescriptor) {
    jclass fdClass = env->FindClass("java/io/FileDescriptor");
    if (fdClass == nullptr) return -2;
    jfieldID descriptorField = env->GetFieldID(fdClass, "descriptor", "I");
    if (descriptorField == nullptr) return -3;
    int fd = env->GetIntField(fileDescriptor, descriptorField);
    return fd >= 0 ? fd : -4;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tinydisplay_touchhelper_TouchGrabber_nativeGrab(JNIEnv* env, jclass, jobject fileDescriptor, jboolean enable) {
    int fd = fdFromJava(env, fileDescriptor);
    if (fd < 0) return fd;
    return ioctl(fd, EVIOCGRAB, enable ? 1 : 0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tinydisplay_touchhelper_TouchGrabber_nativeGetName(JNIEnv* env, jclass, jobject fileDescriptor) {
    int fd = fdFromJava(env, fileDescriptor);
    if (fd < 0) return nullptr;
    char name[256];
    memset(name, 0, sizeof(name));
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) return nullptr;
    return env->NewStringUTF(name);
}
