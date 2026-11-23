#pragma once
#include <string>

// backend_dir: folder with libggml-cpu-*.so variants (Android: nativeLibraryDir); empty = default search
void rc_init(const char *backend_dir);
// returns "" on success, otherwise an error text
std::string rc_load(const char *path, int n_ctx, int n_threads);
bool rc_loaded();
std::string rc_generate(const std::string &prompt, int max_tokens, float temp, std::string &err);
void rc_stop();
void rc_unload();
