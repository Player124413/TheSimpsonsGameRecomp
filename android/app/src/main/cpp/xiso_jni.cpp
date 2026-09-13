/**
 * simpsons-recomp-android - on-device ISO extraction bridge.
 *
 * Wraps the repository's extract-xiso tool (tools/extract-xiso, GPL-style
 * extract-xiso by in__so_mny_wys - see LICENSE.TXT there) as a small JNI
 * library so the app can install the game from the player's own ISO without
 * a PC. extract-xiso.c is compiled unmodified with its entry point renamed
 * (main -> extract_xiso_main) and is invoked with the same command line the
 * desktop launcher uses:  extract-xiso -x -d <dest> <iso>.
 *
 * The call is BLOCKING and must run on a background thread (Java side runs
 * it on "iso-extract"). extract-xiso writes its progress lines to stdout,
 * which Android routes to logcat; the Java side shows byte progress by
 * polling the destination directory instead of parsing the stream.
 */

#include <jni.h>

#include <string>
#include <vector>

// Provided by extract-xiso.c (compiled with -Dmain=extract_xiso_main).
extern "C" int extract_xiso_main(int argc, char* argv[]);

extern "C" JNIEXPORT jint JNICALL
Java_com_yestermester_simpsons_SetupActivity_nativeExtractIso(
        JNIEnv* env, jclass /*clazz*/, jstring iso_path, jstring dest_dir) {
    const char* iso = env->GetStringUTFChars(iso_path, nullptr);
    const char* dest = env->GetStringUTFChars(dest_dir, nullptr);
    if (iso == nullptr || dest == nullptr) {
        if (iso != nullptr) {
            env->ReleaseStringUTFChars(iso_path, iso);
        }
        if (dest != nullptr) {
            env->ReleaseStringUTFChars(dest_dir, dest);
        }
        return -1;
    }

    // Same flag set the desktop launcher uses (launcher.py: "-x -d <target>
    // <iso>", options strictly before the positional ISO because the Windows
    // getopt does not permute argv - keep that order for the Android/bionic
    // getopt too, it costs nothing).
    std::vector<std::string> storage;
    storage.emplace_back("extract-xiso");
    storage.emplace_back("-x");
    storage.emplace_back("-d");
    storage.emplace_back(dest);
    storage.emplace_back(iso);

    std::vector<char*> argv;
    argv.reserve(storage.size());
    for (auto& arg : storage) {
        argv.push_back(arg.data());
    }

    const int result = extract_xiso_main(static_cast<int>(argv.size()), argv.data());

    env->ReleaseStringUTFChars(iso_path, iso);
    env->ReleaseStringUTFChars(dest_dir, dest);
    return result;
}
