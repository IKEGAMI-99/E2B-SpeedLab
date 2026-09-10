#include <jni.h>
#include <dlfcn.h>
#include <sched.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

struct Api {
  void* lib = nullptr;

  using SettingsCreate = void* (*)(const char*, const char*, const char*, const char*);
  using VoidPtr = void (*)(void*);
  using SetInt = void (*)(void*, int);
  using SetBool = void (*)(void*, bool);
  using SetString = void (*)(void*, const char*);
  using EngineCreate = void* (*)(void*);
  using EngineCreateSession = void* (*)(void*, void*);
  using InputCreate = void* (*)(int, const void*, size_t);
  using Generate = void* (*)(void*, const void* const*, size_t);
  using GetInfo = void* (*)(void*);
  using GetInt = int (*)(const void*);
  using GetIntAt = int (*)(const void*, int);
  using GetDouble = double (*)(const void*);
  using GetDoubleAt = double (*)(const void*, int);

  using LoadedFileCreate = void* (*)(const char*);
  using LoadedFileBool = bool (*)(void*);
  using LoadedFileU32 = uint32_t (*)(void*);
  using LoadedFileBackends = int32_t (*)(void*, int, int32_t*, int32_t);
  using LoadedFileString = const char* (*)(void*);

  SettingsCreate settings_create = nullptr;
  VoidPtr settings_delete = nullptr;
  VoidPtr settings_enable_benchmark = nullptr;
  SetInt settings_set_max_num_tokens = nullptr;
  SetInt settings_set_num_prefill_tokens = nullptr;
  SetInt settings_set_num_decode_tokens = nullptr;
  SetString settings_set_cache_dir = nullptr;
  SetBool settings_set_speculative = nullptr;
  SetBool settings_set_wait_for_weight_uploads = nullptr;

  EngineCreate engine_create = nullptr;
  VoidPtr engine_delete = nullptr;
  EngineCreateSession engine_create_session = nullptr;
  VoidPtr session_delete = nullptr;
  InputCreate input_create = nullptr;
  VoidPtr input_delete = nullptr;
  Generate session_generate = nullptr;
  VoidPtr responses_delete = nullptr;
  GetInfo session_get_benchmark_info = nullptr;
  VoidPtr benchmark_info_delete = nullptr;

  GetDouble info_get_ttft = nullptr;
  GetDouble info_get_init = nullptr;
  GetInt info_get_num_prefill_turns = nullptr;
  GetInt info_get_num_decode_turns = nullptr;
  GetIntAt info_get_prefill_count_at = nullptr;
  GetIntAt info_get_decode_count_at = nullptr;
  GetDoubleAt info_get_prefill_tps_at = nullptr;
  GetDoubleAt info_get_decode_tps_at = nullptr;

  LoadedFileCreate loaded_file_create = nullptr;
  VoidPtr loaded_file_delete = nullptr;
  LoadedFileBool loaded_file_has_mtp = nullptr;
  LoadedFileU32 loaded_file_max_context = nullptr;
  LoadedFileBool loaded_file_dynamic_context = nullptr;
  LoadedFileBackends loaded_file_backends = nullptr;
  LoadedFileString loaded_file_min_runtime = nullptr;
};

template <typename T>
T load_symbol(void* lib, const char* name, bool required = true) {
  dlerror();
  void* p = dlsym(lib, name);
  if (!p && required) {
    const char* error = dlerror();
    throw std::runtime_error(std::string("Missing LiteRT-LM C symbol: ") + name +
                             (error ? std::string(" (") + error + ")" : ""));
  }
  return reinterpret_cast<T>(p);
}

Api load_api() {
  Api a;
  a.lib = dlopen("liblitert-lm.so", RTLD_NOW | RTLD_LOCAL);
  if (!a.lib) {
    const char* error = dlerror();
    throw std::runtime_error(std::string("Could not open bundled liblitert-lm.so: ") +
                             (error ? error : "unknown dlopen error"));
  }

  a.settings_create = load_symbol<Api::SettingsCreate>(a.lib, "litert_lm_engine_settings_create");
  a.settings_delete = load_symbol<Api::VoidPtr>(a.lib, "litert_lm_engine_settings_delete");
  a.settings_enable_benchmark = load_symbol<Api::VoidPtr>(a.lib, "litert_lm_engine_settings_enable_benchmark");
  a.settings_set_max_num_tokens = load_symbol<Api::SetInt>(a.lib, "litert_lm_engine_settings_set_max_num_tokens");
  a.settings_set_num_prefill_tokens = load_symbol<Api::SetInt>(a.lib, "litert_lm_engine_settings_set_num_prefill_tokens");
  a.settings_set_num_decode_tokens = load_symbol<Api::SetInt>(a.lib, "litert_lm_engine_settings_set_num_decode_tokens");
  a.settings_set_cache_dir = load_symbol<Api::SetString>(a.lib, "litert_lm_engine_settings_set_cache_dir");
  a.settings_set_speculative = load_symbol<Api::SetBool>(a.lib, "litert_lm_engine_settings_set_enable_speculative_decoding");
  a.settings_set_wait_for_weight_uploads = load_symbol<Api::SetBool>(a.lib, "litert_lm_engine_settings_set_gpu_wait_for_weight_uploads");

  a.engine_create = load_symbol<Api::EngineCreate>(a.lib, "litert_lm_engine_create");
  a.engine_delete = load_symbol<Api::VoidPtr>(a.lib, "litert_lm_engine_delete");
  a.engine_create_session = load_symbol<Api::EngineCreateSession>(a.lib, "litert_lm_engine_create_session");
  a.session_delete = load_symbol<Api::VoidPtr>(a.lib, "litert_lm_session_delete");
  a.input_create = load_symbol<Api::InputCreate>(a.lib, "litert_lm_input_data_create");
  a.input_delete = load_symbol<Api::VoidPtr>(a.lib, "litert_lm_input_data_delete");
  a.session_generate = load_symbol<Api::Generate>(a.lib, "litert_lm_session_generate_content");
  a.responses_delete = load_symbol<Api::VoidPtr>(a.lib, "litert_lm_responses_delete");
  a.session_get_benchmark_info = load_symbol<Api::GetInfo>(a.lib, "litert_lm_session_get_benchmark_info");
  a.benchmark_info_delete = load_symbol<Api::VoidPtr>(a.lib, "litert_lm_benchmark_info_delete");

  a.info_get_ttft = load_symbol<Api::GetDouble>(a.lib, "litert_lm_benchmark_info_get_time_to_first_token");
  a.info_get_init = load_symbol<Api::GetDouble>(a.lib, "litert_lm_benchmark_info_get_total_init_time_in_second");
  a.info_get_num_prefill_turns = load_symbol<Api::GetInt>(a.lib, "litert_lm_benchmark_info_get_num_prefill_turns");
  a.info_get_num_decode_turns = load_symbol<Api::GetInt>(a.lib, "litert_lm_benchmark_info_get_num_decode_turns");
  a.info_get_prefill_count_at = load_symbol<Api::GetIntAt>(a.lib, "litert_lm_benchmark_info_get_prefill_token_count_at");
  a.info_get_decode_count_at = load_symbol<Api::GetIntAt>(a.lib, "litert_lm_benchmark_info_get_decode_token_count_at");
  a.info_get_prefill_tps_at = load_symbol<Api::GetDoubleAt>(a.lib, "litert_lm_benchmark_info_get_prefill_tokens_per_sec_at");
  a.info_get_decode_tps_at = load_symbol<Api::GetDoubleAt>(a.lib, "litert_lm_benchmark_info_get_decode_tokens_per_sec_at");

  // ModelInfo is deliberately loaded through the same final 0.17.0 C library so we can verify
  // that the selected package actually declares an embedded MTP drafter before benchmarking it.
  a.loaded_file_create = load_symbol<Api::LoadedFileCreate>(a.lib, "litert_lm_loaded_file_create", false);
  a.loaded_file_delete = load_symbol<Api::VoidPtr>(a.lib, "litert_lm_loaded_file_delete", false);
  a.loaded_file_has_mtp = load_symbol<Api::LoadedFileBool>(a.lib, "litert_lm_loaded_file_has_speculative_decoding_support", false);
  a.loaded_file_max_context = load_symbol<Api::LoadedFileU32>(a.lib, "litert_lm_loaded_file_max_context_tokens", false);
  a.loaded_file_dynamic_context = load_symbol<Api::LoadedFileBool>(a.lib, "litert_lm_loaded_file_is_dynamic_context", false);
  a.loaded_file_backends = load_symbol<Api::LoadedFileBackends>(a.lib, "litert_lm_loaded_file_modality_supported_backends", false);
  a.loaded_file_min_runtime = load_symbol<Api::LoadedFileString>(a.lib, "litert_lm_loaded_file_min_runtime_version", false);
  return a;
}

std::string jstring_to_utf8(JNIEnv* env, jstring value) {
  if (!value) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (!chars) throw std::runtime_error("GetStringUTFChars failed");
  std::string out(chars);
  env->ReleaseStringUTFChars(value, chars);
  return out;
}

void throw_java(JNIEnv* env, const std::string& message) {
  jclass cls = env->FindClass("java/lang/RuntimeException");
  if (cls) env->ThrowNew(cls, message.c_str());
}

void write_stage(const std::string& cache_dir, const std::string& stage) {
  if (cache_dir.empty()) return;
  std::ofstream out(cache_dir + "/native_gpu_stage.txt", std::ios::out | std::ios::trunc);
  if (out) {
    out << stage;
    out.flush();
  }
}

uint64_t current_affinity_mask() {
  cpu_set_t set;
  CPU_ZERO(&set);
  if (sched_getaffinity(0, sizeof(set), &set) != 0) return 0;
  uint64_t mask = 0;
  for (int cpu = 0; cpu < 64 && cpu < CPU_SETSIZE; ++cpu) {
    if (CPU_ISSET(cpu, &set)) mask |= (uint64_t{1} << cpu);
  }
  return mask;
}

uint64_t read_uint_file(const std::string& path) {
  std::ifstream in(path);
  uint64_t value = 0;
  if (in) in >> value;
  return value;
}

uint64_t cpu_score(int cpu) {
  const std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(cpu);
  uint64_t freq = read_uint_file(base + "/cpufreq/cpuinfo_max_freq");
  if (freq == 0) freq = read_uint_file(base + "/cpufreq/scaling_max_freq");
  if (freq != 0) return freq * 1000ULL + static_cast<uint64_t>(cpu);
  uint64_t capacity = read_uint_file(base + "/cpu_capacity");
  if (capacity != 0) return capacity * 1000ULL + static_cast<uint64_t>(cpu);
  // Last-resort ordering is still deterministic. Qualcomm devices normally expose max frequency.
  return static_cast<uint64_t>(cpu);
}

uint64_t apply_fast_cpu_affinity(int fastest_count) {
  cpu_set_t allowed;
  CPU_ZERO(&allowed);
  if (sched_getaffinity(0, sizeof(allowed), &allowed) != 0) {
    throw std::runtime_error("sched_getaffinity failed");
  }

  if (fastest_count <= 0) return current_affinity_mask();

  std::vector<std::pair<uint64_t, int>> ranked;
  for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
    if (CPU_ISSET(cpu, &allowed)) ranked.emplace_back(cpu_score(cpu), cpu);
  }
  if (ranked.empty()) throw std::runtime_error("No CPUs available in affinity mask");

  std::sort(ranked.begin(), ranked.end(), [](const auto& a, const auto& b) {
    if (a.first != b.first) return a.first > b.first;
    return a.second > b.second;
  });

  const int count = std::min<int>(fastest_count, ranked.size());
  cpu_set_t selected;
  CPU_ZERO(&selected);
  uint64_t mask = 0;
  for (int i = 0; i < count; ++i) {
    CPU_SET(ranked[i].second, &selected);
    if (ranked[i].second < 64) mask |= (uint64_t{1} << ranked[i].second);
  }
  if (sched_setaffinity(0, sizeof(selected), &selected) != 0) {
    throw std::runtime_error("sched_setaffinity failed for FAST" + std::to_string(fastest_count));
  }
  return mask;
}

std::string benchmark_prompt() {
  std::string prompt;
  prompt.reserve(2400);
  for (int i = 0; i < 24; ++i) {
    prompt += "On-device language models benefit from low latency, efficient memory use, and fast token generation. ";
  }
  prompt += "Explain the performance tradeoffs in detail.";
  return prompt;
}

std::string backend_name(int32_t backend) {
  switch (backend) {
    case 1: return "CPU";
    case 2: return "GPU";
    case 3: return "NPU";
    default: return "UNKNOWN(" + std::to_string(backend) + ")";
  }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_e2bspeedlab_NativeGpuLocal_nativeInspect(
    JNIEnv* env, jobject /* thiz */, jstring model_path_j) {
  Api api;
  void* loaded = nullptr;
  try {
    const std::string model_path = jstring_to_utf8(env, model_path_j);
    api = load_api();

    std::ostringstream out;
    out << "runtime=0.17.0";
    if (api.loaded_file_create && api.loaded_file_delete) {
      loaded = api.loaded_file_create(model_path.c_str());
    }
    if (!loaded) {
      out << ";model_info=unavailable";
    } else {
      if (api.loaded_file_has_mtp) out << ";mtp=" << (api.loaded_file_has_mtp(loaded) ? "1" : "0");
      if (api.loaded_file_max_context) out << ";max_context=" << api.loaded_file_max_context(loaded);
      if (api.loaded_file_dynamic_context) out << ";dynamic=" << (api.loaded_file_dynamic_context(loaded) ? "1" : "0");
      if (api.loaded_file_min_runtime) {
        const char* min_runtime = api.loaded_file_min_runtime(loaded);
        if (min_runtime && *min_runtime) out << ";min_runtime=" << min_runtime;
      }
      if (api.loaded_file_backends) {
        int32_t backends[8] = {};
        int32_t count = api.loaded_file_backends(loaded, 0 /* text */, backends, 8);
        out << ";backends=";
        for (int32_t i = 0; i < count && i < 8; ++i) {
          if (i) out << ',';
          out << backend_name(backends[i]);
        }
      }
    }

    if (loaded && api.loaded_file_delete) api.loaded_file_delete(loaded);
    if (api.lib) dlclose(api.lib);
    return env->NewStringUTF(out.str().c_str());
  } catch (const std::exception& e) {
    if (loaded && api.loaded_file_delete) api.loaded_file_delete(loaded);
    if (api.lib) dlclose(api.lib);
    throw_java(env, e.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_e2bspeedlab_NativeGpuLocal_nativeBenchmark(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_path_j,
    jstring cache_dir_j,
    jint max_context,
    jboolean enable_mtp,
    jint fastest_cpu_count) {
  Api api;
  void* settings = nullptr;
  void* engine = nullptr;
  void* session = nullptr;
  void* input = nullptr;
  void* responses = nullptr;
  void* info = nullptr;
  std::string cache_dir;

  try {
    if (max_context < 768 || max_context > 32768) {
      throw std::runtime_error("maxContext must be in 768..32768");
    }
    if (!(fastest_cpu_count == 0 || fastest_cpu_count == 2 || fastest_cpu_count == 4)) {
      throw std::runtime_error("fastestCpuCount must be 0, 2, or 4");
    }

    const std::string model_path = jstring_to_utf8(env, model_path_j);
    cache_dir = jstring_to_utf8(env, cache_dir_j);
    if (model_path.empty()) throw std::runtime_error("Model path is empty");

    write_stage(cache_dir, "AFFINITY");
    const uint64_t affinity_mask = apply_fast_cpu_affinity(fastest_cpu_count);

    write_stage(cache_dir, "LOAD_C_API");
    api = load_api();

    write_stage(cache_dir, "CREATE_SETTINGS_GPU");
    settings = api.settings_create(model_path.c_str(), "gpu", nullptr, nullptr);
    if (!settings) throw std::runtime_error("LiteRT-LM failed to create regular GPU engine settings");

    api.settings_enable_benchmark(settings);
    api.settings_set_max_num_tokens(settings, max_context);
    api.settings_set_num_prefill_tokens(settings, 512);
    api.settings_set_num_decode_tokens(settings, 256);
    if (!cache_dir.empty()) api.settings_set_cache_dir(settings, cache_dir.c_str());
    api.settings_set_speculative(settings, enable_mtp == JNI_TRUE);
    // Mirrors LiteRT-LM's official Python GPU benchmark path. Without this, prefill timing can
    // overlap asynchronous weight uploads and become misleading.
    api.settings_set_wait_for_weight_uploads(settings, true);

    write_stage(cache_dir, "ENGINE_CREATE");
    engine = api.engine_create(settings);
    api.settings_delete(settings);
    settings = nullptr;
    if (!engine) throw std::runtime_error("LiteRT-LM failed to create regular GPU engine");

    write_stage(cache_dir, "SESSION_CREATE");
    session = api.engine_create_session(engine, nullptr);
    if (!session) throw std::runtime_error("LiteRT-LM failed to create regular GPU benchmark session");

    const std::string prompt = benchmark_prompt();
    write_stage(cache_dir, "INPUT_CREATE");
    input = api.input_create(0 /* text */, prompt.data(), prompt.size());
    if (!input) throw std::runtime_error("LiteRT-LM failed to create benchmark input");

    const void* inputs[1] = {input};
    write_stage(cache_dir, "GENERATE");
    responses = api.session_generate(session, inputs, 1);
    write_stage(cache_dir, "GENERATE_DONE");
    if (responses) {
      api.responses_delete(responses);
      responses = nullptr;
    }
    api.input_delete(input);
    input = nullptr;

    write_stage(cache_dir, "BENCHMARK_INFO");
    info = api.session_get_benchmark_info(session);
    if (!info) throw std::runtime_error("LiteRT-LM returned no native GPU benchmark info");

    const int prefill_turns = api.info_get_num_prefill_turns(info);
    const int decode_turns = api.info_get_num_decode_turns(info);
    const int prefill_index = prefill_turns > 0 ? prefill_turns - 1 : 0;
    const int decode_index = decode_turns > 0 ? decode_turns - 1 : 0;

    const double values[7] = {
        api.info_get_init(info),
        api.info_get_ttft(info),
        prefill_turns > 0 ? static_cast<double>(api.info_get_prefill_count_at(info, prefill_index)) : 0.0,
        decode_turns > 0 ? static_cast<double>(api.info_get_decode_count_at(info, decode_index)) : 0.0,
        prefill_turns > 0 ? api.info_get_prefill_tps_at(info, prefill_index) : 0.0,
        decode_turns > 0 ? api.info_get_decode_tps_at(info, decode_index) : 0.0,
        static_cast<double>(affinity_mask),
    };

    api.benchmark_info_delete(info);
    info = nullptr;
    api.session_delete(session);
    session = nullptr;
    api.engine_delete(engine);
    engine = nullptr;
    dlclose(api.lib);
    api.lib = nullptr;
    write_stage(cache_dir, "DONE");

    jdoubleArray result = env->NewDoubleArray(7);
    if (!result) throw std::runtime_error("Could not allocate native GPU benchmark result array");
    env->SetDoubleArrayRegion(result, 0, 7, values);
    return result;
  } catch (const std::exception& e) {
    write_stage(cache_dir, std::string("ERROR: ") + e.what());
    if (info && api.benchmark_info_delete) api.benchmark_info_delete(info);
    if (responses && api.responses_delete) api.responses_delete(responses);
    if (input && api.input_delete) api.input_delete(input);
    if (session && api.session_delete) api.session_delete(session);
    if (engine && api.engine_delete) api.engine_delete(engine);
    if (settings && api.settings_delete) api.settings_delete(settings);
    if (api.lib) dlclose(api.lib);
    throw_java(env, e.what());
    return nullptr;
  }
}
