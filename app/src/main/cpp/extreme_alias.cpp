#include <jni.h>

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_e2bspeedlab_ExtremeNative_nativeBenchmark(
    JNIEnv* env,
    jobject thiz,
    jstring model_path,
    jstring cache_dir,
    jint decode_steps_per_sync,
    jboolean enable_mtp);

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_e2bspeedlab_ExtremeNativeLocal_nativeBenchmark(
    JNIEnv* env,
    jobject thiz,
    jstring model_path,
    jstring cache_dir,
    jint decode_steps_per_sync,
    jboolean enable_mtp) {
  return Java_com_e2bspeedlab_ExtremeNative_nativeBenchmark(
      env, thiz, model_path, cache_dir, decode_steps_per_sync, enable_mtp);
}
