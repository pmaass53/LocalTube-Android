#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include <stdio.h>
#include "node.h"
#include "rn-bridge.h"

#define TAG "NodeBridge-Native"
#define ADBTAG "NODEJS-MOBILE"

// Cache for JNI environment and class references
static JavaVM* g_vm = nullptr;
static jclass g_nodeBridgeClass = nullptr;

// Helper to get JNIEnv for the current thread
JNIEnv* getJniEnv() {
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

// Callback from rn-bridge when Node sends a message to Java
void onNodeMessage(const char* channel_name, const char* msg) {
    JNIEnv* env = getJniEnv();
    if (!env || !g_nodeBridgeClass) return;

    jmethodID method = env->GetStaticMethodID(g_nodeBridgeClass, "onMessageReceived", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (method) {
        jstring jChannel = env->NewStringUTF(channel_name);
        jstring jMsg = env->NewStringUTF(msg);
        env->CallStaticVoidMethod(g_nodeBridgeClass, method, jChannel, jMsg);
        env->DeleteLocalRef(jChannel);
        env->DeleteLocalRef(jMsg);
    }
}

// Redirection logic for stdout/stderr
int pipe_stdout[2];
int pipe_stderr[2];
pthread_t thread_stdout;
pthread_t thread_stderr;

void *thread_stderr_func(void*) {
    ssize_t redirect_size;
    char buf[2048];
    while((redirect_size = read(pipe_stderr[0], buf, sizeof buf - 1)) > 0) {
        if(buf[redirect_size - 1] == '\n') --redirect_size;
        buf[redirect_size] = 0;
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, buf);
    }
    return 0;
}

void *thread_stdout_func(void*) {
    ssize_t redirect_size;
    char buf[2048];
    while((redirect_size = read(pipe_stdout[0], buf, sizeof buf - 1)) > 0) {
        if(buf[redirect_size - 1] == '\n') --redirect_size;
        buf[redirect_size] = 0;
        __android_log_write(ANDROID_LOG_INFO, ADBTAG, buf);
    }
    return 0;
}

int start_redirecting_stdout_stderr() {
    setvbuf(stdout, 0, _IONBF, 0);
    pipe(pipe_stdout);
    dup2(pipe_stdout[1], STDOUT_FILENO);

    setvbuf(stderr, 0, _IONBF, 0);
    pipe(pipe_stderr);
    dup2(pipe_stderr[1], STDERR_FILENO);

    if(pthread_create(&thread_stdout, 0, thread_stdout_func, 0) == -1) return -1;
    pthread_detach(thread_stdout);

    if(pthread_create(&thread_stderr, 0, thread_stderr_func, 0) == -1) return -1;
    pthread_detach(thread_stderr);

    return 0;
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass cls = env->FindClass("xyz/sunkastudios/localtube/nodejs/NodeBridge");
    g_nodeBridgeClass = (jclass)env->NewGlobalRef(cls);

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_xyz_sunkastudios_localtube_nodejs_NodeBridge_sendMessageToNode(
        JNIEnv *env,
        jclass /* clazz */,
        jstring channelName,
        jstring msg) {
    const char* nativeChannelName = env->GetStringUTFChars(channelName, 0);
    const char* nativeMessage = env->GetStringUTFChars(msg, 0);

    rn_bridge_notify(nativeChannelName, nativeMessage);

    env->ReleaseStringUTFChars(channelName, nativeChannelName);
    env->ReleaseStringUTFChars(msg, nativeMessage);
}

extern "C" JNIEXPORT jint JNICALL
Java_xyz_sunkastudios_localtube_nodejs_NodeBridge_startNodeWithArguments(
        JNIEnv *env,
        jclass /* clazz */,
        jobjectArray arguments,
        jstring nodePath) {

    // Set NODE_PATH environment variable
    const char* nativeNodePath = env->GetStringUTFChars(nodePath, 0);
    setenv("NODE_PATH", nativeNodePath, 1);
    env->ReleaseStringUTFChars(nodePath, nativeNodePath);

    jsize argument_count = env->GetArrayLength(arguments);

    // Compute total buffer length for contiguous memory
    int c_arguments_size = 0;
    for (int i = 0; i < argument_count; i++) {
        c_arguments_size += strlen(env->GetStringUTFChars((jstring)env->GetObjectArrayElement(arguments, i), 0)) + 1;
    }

    char* args_buffer = (char*) calloc(c_arguments_size, sizeof(char));
    char* argv[argument_count];
    char* current_args_position = args_buffer;

    for (int i = 0; i < argument_count; i++) {
        const char* current_argument = env->GetStringUTFChars((jstring)env->GetObjectArrayElement(arguments, i), 0);
        __android_log_print(ANDROID_LOG_INFO, TAG, "argv[%d]: %s", i, current_argument);
        strncpy(current_args_position, current_argument, strlen(current_argument));
        argv[i] = current_args_position;
        current_args_position += strlen(current_args_position) + 1;
    }

    // Start stdout/stderr redirection
    __android_log_write(ANDROID_LOG_INFO, TAG, "Starting redirection...");
    start_redirecting_stdout_stderr();

    // Register the bridge callback before starting Node
    __android_log_write(ANDROID_LOG_INFO, TAG, "Registering bridge callback...");
    rn_register_bridge_cb(&onNodeMessage);

    // Start Node.js engine
    __android_log_write(ANDROID_LOG_INFO, TAG, "Calling node::Start...");
    int result = node::Start(argument_count, argv);
    __android_log_print(ANDROID_LOG_INFO, TAG, "node::Start returned: %d", result);

    free(args_buffer);
    return jint(result);
}
