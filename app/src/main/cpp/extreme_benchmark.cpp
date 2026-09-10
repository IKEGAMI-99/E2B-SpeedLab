#include <jni.h>
#include <dlfcn.h>

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>

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

  SettingsCreate settings_create = nullptr;
  VoidPtr settings_delete = nullptr;
  VoidPtr settings_enable_benchmark = nullptr;
  SetInt settings_set_max_num_tokens = nullptr;
  SetInt settings_set_num_prefill_tokens = nullptr;
  SetInt settings_set_num_decode_tokens = nullptr;
  SetString settings_set_cache_dir = nullptr;
  SetBool settings_set_speculative = nullptr;
  SetInt settings_set_gpu_decode_steps = nullptr;
  SetBool settings_set_gpu_wait_weights = nullptr;

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
  a.lib = dlopen("liblitertlm_jni.so", RTLD_NOW | RTLD_LOCAL);
  if (!a.lib) {
    const char* error = dlerror();
    throw std::runtime_error(std::string("Could not open liblitertlm_jni.so: ") +
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
  a.settings_set_gpu_decode_steps = load_symbol<Api::SetInt>(a.lib, "litert_lm_engine_settings_set_gpu_decode_steps_per_sync");
  // Older builds may not expose this helper. It improves benchmark correctness but is not required.
  a.settings_set_gpu_wait_weights = load_symbol<Api::SetBool>(a.lib, "litert_lm_engine_settings_set_gpu_wait_for_weight_uploads", false);

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

}  // namespace

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_e2bspeedlab_ExtremeNative_nativeBenchmark(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_path_j,
    jstring cache_dir_j,
    jint decode_steps_per_sync,
    jboolean enable_mtp) {
  Api api;
  void* settings = nullptr;
  void* engine = nullptr;
  void* session = nullptr;
  void* input = nullptr;
  void* responses = nullptr;
  void* info = nullptr;

  try {
    if (decode_steps_per_sync < 1 || decode_steps_per_sync > 32) {
      throw std::runtime_error("decodeStepsPerSync must be in 1..32");
    }

    const std::string model_path = jstring_to_utf8(env, model_path_j);
    const std::string cache_dir = jstring_to_utf8(env, cache_dir_j);
    if (model_path.empty()) throw std::runtime_error("Model path is empty");

    api = load_api();
    settings = api.settings_create(model_path.c_str(), "gpu", nullptr, nullptr);
    if (!settings) throw std::runtime_error("LiteRT-LM failed to create engine settings");

    api.settings_enable_benchmark(settings);
    api.settings_set_max_num_tokens(settings, 2048);
    api.settings_set_num_prefill_tokens(settings, 512);
    api.settings_set_num_decode_tokens(settings, 256);
    if (!cache_dir.empty()) api.settings_set_cache_dir(settings, cache_dir.c_str());
    api.settings_set_speculative(settings, enable_mtp == JNI_TRUE);
    api.settings_set_gpu_decode_steps(settings, decode_steps_per_sync);
    if (api.settings_set_gpu_wait_weights) api.settings_set_gpu_wait_weights(settings, true);

    engine = api.engine_create(settings);
    api.settings_delete(settings);
    settings = nullptr;
    if (!engine) throw std::runtime_error("LiteRT-LM failed to create Extreme GPU engine");

    session = api.engine_create_session(engine, nullptr);
    if (!session) throw std::runtime_error("LiteRT-LM failed to create benchmark session");

    // Benchmark mode pads/truncates this to the configured 512-token prefill target.
    static constexpr char kPrompt[] =
        "On-device language models benefit from low latency, efficient memory use, "
        "fast token generation, and predictable performance. Explain the performance "
        "tradeoffs of mobile inference and continue with enough detail for benchmarking.";
    input = api.input_create(0 /* kLiteRtLmInputDataTypeText */, kPrompt, sizeof(kPrompt) - 1);
    if (!input) throw std::runtime_error("LiteRT-LM failed to create benchmark input");

    const void* inputs[1] = {input};
    responses = api.session_generate(session, inputs, 1);
    if (responses) {
      api.responses_delete(responses);
      responses = nullptr;
    }
    api.input_delete(input);
    input = nullptr;

    info = api.session_get_benchmark_info(session);
    if (!info) throw std::runtime_error("LiteRT-LM returned no Extreme benchmark info");

    const int prefill_turns = api.info_get_num_prefill_turns(info);
    const int decode_turns = api.info_get_num_decode_turns(info);
    const int prefill_index = prefill_turns > 0 ? prefill_turns - 1 : 0;
    const int decode_index = decode_turns > 0 ? decode_turns - 1 : 0;

    const double values[6] = {
        api.info_get_init(info),
        api.info_get_ttft(info),
        prefill_turns > 0 ? static_cast<double>(api.info_get_prefill_count_at(info, prefill_index)) : 0.0,
        decode_turns > 0 ? static_cast<double>(api.info_get_decode_count_at(info, decode_index)) : 0.0,
        prefill_turns > 0 ? api.info_get_prefill_tps_at(info, prefill_index) : 0.0,
        decode_turns > 0 ? api.info_get_decode_tps_at(info, decode_index) : 0.0,
    };

    api.benchmark_info_delete(info);
    info = nullptr;
    api.session_delete(session);
    session = nullptr;
    api.engine_delete(engine);
    engine = nullptr;
    dlclose(api.lib);
    api.lib = nullptr;

    jdoubleArray result = env->NewDoubleArray(6);
    if (!result) throw std::runtime_error("Could not allocate benchmark result array");
    env->SetDoubleArrayRegion(result, 0, 6, values);
    return result;
  } catch (const std::exception& e) {
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
