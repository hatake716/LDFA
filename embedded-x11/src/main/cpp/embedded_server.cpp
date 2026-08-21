#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_termux_x11_CmdEntryPoint_start(JNIEnv*, jclass, jobjectArray);

extern "C" JNIEXPORT jobject JNICALL
Java_com_termux_x11_CmdEntryPoint_getXConnection(JNIEnv*, jobject);

extern "C" JNIEXPORT jboolean JNICALL
Java_com_termux_x11_CmdEntryPoint_connected(JNIEnv*, jclass);

extern "C" JNIEXPORT jboolean JNICALL
Java_com_termux_x11_EmbeddedX11ServerBridge_start(JNIEnv* env, jclass clazz, jobjectArray args) {
    return Java_com_termux_x11_CmdEntryPoint_start(env, clazz, args);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_termux_x11_EmbeddedX11ServerBridge_getXConnection(JNIEnv* env, jclass clazz) {
    return Java_com_termux_x11_CmdEntryPoint_getXConnection(env, clazz);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_termux_x11_EmbeddedX11ServerBridge_connected(JNIEnv* env, jclass clazz) {
    return Java_com_termux_x11_CmdEntryPoint_connected(env, clazz);
}
