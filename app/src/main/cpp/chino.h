#ifndef CHINO_H
#define CHINO_H

#include <jni.h>
#include <cstdint>

#define MY_NR_OPENAT 56
#define MY_NR_READ   63
#define MY_NR_LSEEK  62
#define MY_NR_CLOSE  57

#define XOR_KEY 0x66

// 本仓库自行发布的版本，签名证书为 ~/.android/keystore.jks (alias: hanime)。
// 该值 = 证书 SHA-256 指纹（小写、去冒号）。换签名密钥时必须同步替换，否则正式版视频页会拦截。
#define EXPECTED_SIG_HASH "b3ddc86cac6c6b02ac5d7a2dd40b48e2d1ee8287298dc0355fe0c048644b7a63"

extern "C" {
JNIEXPORT jboolean JNICALL
Java_io_github_daisukikaffuchino_han1meviewer_ui_screen_video_VideoRouteHostScreenKt_svc(
        JNIEnv *env, jclass thiz);

JNIEXPORT jstring JNICALL
Java_io_github_daisukikaffuchino_han1meviewer_ui_screen_video_VideoRouteHostScreenKt_getString(
        JNIEnv *env,
        jclass thiz);
}

#endif // CHINO_H