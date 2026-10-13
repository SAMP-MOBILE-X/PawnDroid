
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "sc.h"
#include "version.h"

static pthread_mutex_t compile_lock = PTHREAD_MUTEX_INITIALIZER;

static int absolute_path(const char *s) {
    return s && s[0] == '/' && strlen(s) < _MAX_PATH - 64;
}

static char *option(const char *flag, const char *value) {
    size_t len = strlen(flag) + strlen(value) + 1;
    char *buffer = malloc(len);
    if (buffer) snprintf(buffer, len, "%s%s", flag, value);
    return buffer;
}

static char *source_directory(const char *src) {
    char *s = strdup(src);
    if (s) {
        char *slash = strrchr(s, '/');
        if (!slash) { free(s); return NULL; }
        if (slash == s) slash[1] = '\0';
        else *slash = '\0';
    }
    return s;
}


JNIEXPORT jint JNICALL
Java_com_sampmobilex_pawncompiler_PawnNative_compile(
        JNIEnv *env, jclass ignored, jstring input, jstring output,
        jobjectArray includeDirs, jobjectArray compileFlags, jstring errorFile, jstring tmpDir) {
    (void)ignored;
    if (!input || !output || !includeDirs || !compileFlags || !errorFile || !tmpDir) return -10;

    const char *src = (*env)->GetStringUTFChars(env, input, NULL);
    const char *dst = (*env)->GetStringUTFChars(env, output, NULL);
    const char *err = (*env)->GetStringUTFChars(env, errorFile, NULL);
    const char *tmp = (*env)->GetStringUTFChars(env, tmpDir, NULL);
    int result = -10;
    char **args = NULL;
    char **incs = NULL;
    char **extraFlags = NULL;
    char *sourceDir = NULL, *sourceopt = NULL, *outopt = NULL;
    int count = (int)(*env)->GetArrayLength(env, includeDirs);
    int flagCount = (int)(*env)->GetArrayLength(env, compileFlags);
    if (!src || !dst || !err || !tmp) goto cleanup;
    if (!absolute_path(src) || !absolute_path(dst) ||
        !absolute_path(err) || !absolute_path(tmp)) { result = -11; goto cleanup; }
    if (count < 0 || count > 128 || flagCount < 0 || flagCount > 32) { result = -13; goto cleanup; }

    sourceDir = source_directory(src);
    if (sourceDir) sourceopt = option("-i", sourceDir);
    outopt = option("-o", dst);
    /* Selected switches + source, include and output paths. */
    args = calloc((size_t)count + (size_t)flagCount + 16, sizeof(*args));
    incs = calloc((size_t)count + 1, sizeof(*incs));
    extraFlags = calloc((size_t)flagCount + 1, sizeof(*extraFlags));
    if (!sourceDir || !sourceopt || !outopt || !args || !incs || !extraFlags) {
        result = -12; goto cleanup;
    }

    int argc = 0;
    args[argc++] = "pawncc";
    args[argc++] = (char *)src;

    for (int j = 0; j < flagCount; ++j) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, compileFlags, j);
        if (!item) { result = -15; goto cleanup; }
        const char *value = (*env)->GetStringUTFChars(env, item, NULL);
        if (!value || value[0] != '-' || strlen(value) > 80 || strlen(value) < 2
            || value[1] == 'i' || value[1] == 'o' || value[1] == 'r'
            || value[1] == 'e' || value[1] == 'a' || value[1] == 'l') {
            if (value) (*env)->ReleaseStringUTFChars(env, item, value);
            (*env)->DeleteLocalRef(env, item);
            result = -15; goto cleanup;
        }
        extraFlags[j] = option("", value);
        (*env)->ReleaseStringUTFChars(env, item, value);
        (*env)->DeleteLocalRef(env, item);
        if (!extraFlags[j]) { result = -12; goto cleanup; }
        args[argc++] = extraFlags[j];
    }
    for (int j = 0; j < count; ++j) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, includeDirs, j);
        if (!item) { result = -13; goto cleanup; }
        const char *path = (*env)->GetStringUTFChars(env, item, NULL);
        if (!path || !absolute_path(path)) {
            if (path) (*env)->ReleaseStringUTFChars(env, item, path);
            (*env)->DeleteLocalRef(env, item);
            result = -13; goto cleanup;
        }
        incs[j] = option("-i", path);
        (*env)->ReleaseStringUTFChars(env, item, path);
        (*env)->DeleteLocalRef(env, item);
        if (!incs[j]) { result = -12; goto cleanup; }
        args[argc++] = incs[j];
    }
    args[argc++] = outopt;
    args[argc] = NULL;

    pthread_mutex_lock(&compile_lock);
    fflush(stdout);
    fflush(stderr);
    int saved_stderr = dup(STDERR_FILENO);
    int saved_stdout = dup(STDOUT_FILENO);

    char saved_cwd[_MAX_PATH];
    int have_saved_cwd = getcwd(saved_cwd, sizeof(saved_cwd)) != NULL;
    int cwd_changed = 0;
    int source_fallback = 0;
    int log_fd = open(err, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (log_fd >= 0 && saved_stderr >= 0 && saved_stdout >= 0
            && dup2(log_fd, STDERR_FILENO) >= 0 && dup2(log_fd, STDOUT_FILENO) >= 0) {

        if (chdir(sourceDir) == 0) cwd_changed = 1;
        else {
            int cwd_error = errno;
            source_fallback = 1;

            int insert_at = 2 + flagCount;
            for (int j = argc; j >= insert_at; --j) args[j + 1] = args[j];
            args[insert_at] = sourceopt;
            ++argc;
            fprintf(stderr, "Android directory fallback: cannot enter [%s]: %s (errno=%d). "
                    "Compiling with absolute paths and the source include directory.\n",
                    sourceDir, strerror(cwd_error), cwd_error);
        }
        if (setenv("TMPDIR", tmp, 1) == 0) result = pc_compile(argc, args);
        else result = -14;
        if (result >= 0) {
            fprintf(stdout, "\nAndroid compiler build: %s / Android runtime r6\n"
                    "Effective Pawn settings: cells=%u bits, symbol limit=%d, optimization=%d, "
                    "debug symbols=%s, runtime checks=%s, compatibility=%s\n",
                    VERSION_STR, (unsigned)(sizeof(cell) * 8), sNAMEMAX, pc_optimize,
                    (sc_debug & sSYMBOLIC) ? "on" : "off",
                    (sc_debug & sCHKBOUNDS) ? "on" : "off", pc_compat ? "on" : "off");
            #if defined PAWNDROID_PC_TEXT_COMPAT && PAWNDROID_PC_TEXT_COMPAT
                fputs("Source text profile: PC 3.10.11 byte compatibility; explicit BOM UTF-8 preserved\n", stdout);
            #else
                fputs("Source text profile: repaired Unicode autodetection\n", stdout);
            #endif
            #if defined PAWNDROID_PC_CODEGEN_COMPAT && PAWNDROID_PC_CODEGEN_COMPAT
                fputs("Code generation profile: stock PC 3.10.11; legacy forward-array heap behavior retained\n", stdout);
            #else
                fputs("Code generation profile: balanced ternary/forward-array heap repair\n", stdout);
            #endif
            fprintf(stdout, "Source directory: [%s]\nSource lookup: %s\nCompiler arguments:",
                    sourceDir, source_fallback ? "absolute source include directory" : "source working directory");
            for (int j = 1; j < argc; ++j) fprintf(stdout, " [%s]", args[j]);
            fprintf(stdout, "\n");
        }

        if (cwd_changed && have_saved_cwd && chdir(saved_cwd) != 0) {
            int cwd_error = errno;
            fprintf(stderr, "Android directory note: cannot restore [%s]: %s (errno=%d). "
                    "Compiler result retained; the worker will be discarded.\n",
                    saved_cwd, strerror(cwd_error), cwd_error);
        }
        fflush(stdout);
        fflush(stderr);
    } else {
        result = -14;
    }
    if (saved_stderr >= 0) { dup2(saved_stderr, STDERR_FILENO); close(saved_stderr); }
    if (saved_stdout >= 0) { dup2(saved_stdout, STDOUT_FILENO); close(saved_stdout); }
    if (log_fd >= 0) close(log_fd);
    pthread_mutex_unlock(&compile_lock);

cleanup:
    if (src) (*env)->ReleaseStringUTFChars(env, input, src);
    if (dst) (*env)->ReleaseStringUTFChars(env, output, dst);
    if (err) (*env)->ReleaseStringUTFChars(env, errorFile, err);
    if (tmp) (*env)->ReleaseStringUTFChars(env, tmpDir, tmp);
    if (incs) for (int j = 0; j < count; ++j) free(incs[j]);
    if (extraFlags) for (int j = 0; j < flagCount; ++j) free(extraFlags[j]);
    free(incs);
    free(extraFlags);
    free(args);
    free(sourceDir);
    free(sourceopt);
    free(outopt);
    return result;
}


JNIEXPORT jstring JNICALL
Java_com_sampmobilex_pawncompiler_PawnNative_compilerVersion(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, VERSION_STR " / Android runtime r6 / symbols 63");
}
