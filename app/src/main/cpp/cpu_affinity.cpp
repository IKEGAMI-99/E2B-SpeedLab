#include <jni.h>
#include <sched.h>

#include <algorithm>
#include <cstdint>
#include <fstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

uint64_t mask_from_set(const cpu_set_t& set) {
  uint64_t mask = 0;
  for (int cpu = 0; cpu < 64 && cpu < CPU_SETSIZE; ++cpu) {
    if (CPU_ISSET(cpu, &set)) mask |= (uint64_t{1} << cpu);
  }
  return mask;
}

uint64_t current_mask() {
  cpu_set_t set;
  CPU_ZERO(&set);
  if (sched_getaffinity(0, sizeof(set), &set) != 0) {
    throw std::runtime_error("sched_getaffinity failed");
  }
  return mask_from_set(set);
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
  return static_cast<uint64_t>(cpu);
}

uint64_t pin_fast(int count) {
  cpu_set_t allowed;
  CPU_ZERO(&allowed);
  if (sched_getaffinity(0, sizeof(allowed), &allowed) != 0) {
    throw std::runtime_error("sched_getaffinity failed");
  }

  std::vector<std::pair<uint64_t, int>> ranked;
  for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
    if (CPU_ISSET(cpu, &allowed)) ranked.emplace_back(cpu_score(cpu), cpu);
  }
  if (ranked.empty()) throw std::runtime_error("No CPUs available in affinity mask");

  std::sort(ranked.begin(), ranked.end(), [](const auto& a, const auto& b) {
    if (a.first != b.first) return a.first > b.first;
    return a.second > b.second;
  });

  cpu_set_t selected;
  CPU_ZERO(&selected);
  const int actual = std::min<int>(count, ranked.size());
  for (int i = 0; i < actual; ++i) CPU_SET(ranked[i].second, &selected);

  if (sched_setaffinity(0, sizeof(selected), &selected) != 0) {
    throw std::runtime_error("sched_setaffinity failed");
  }
  return mask_from_set(selected);
}

void set_mask(uint64_t mask) {
  cpu_set_t set;
  CPU_ZERO(&set);
  for (int cpu = 0; cpu < 64 && cpu < CPU_SETSIZE; ++cpu) {
    if ((mask >> cpu) & 1ULL) CPU_SET(cpu, &set);
  }
  if (sched_setaffinity(0, sizeof(set), &set) != 0) {
    throw std::runtime_error("sched_setaffinity restore failed");
  }
}

void throw_java(JNIEnv* env, const char* msg) {
  jclass cls = env->FindClass("java/lang/RuntimeException");
  if (cls) env->ThrowNew(cls, msg);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_e2bspeedlab_CpuAffinity_nativeCurrentMask(JNIEnv* env, jobject) {
  try {
    return static_cast<jlong>(current_mask());
  } catch (const std::exception& e) {
    throw_java(env, e.what());
    return 0;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_e2bspeedlab_CpuAffinity_nativePinFast(JNIEnv* env, jobject, jint count) {
  try {
    if (count != 2 && count != 4) throw std::runtime_error("count must be 2 or 4");
    return static_cast<jlong>(pin_fast(count));
  } catch (const std::exception& e) {
    throw_java(env, e.what());
    return 0;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_e2bspeedlab_CpuAffinity_nativeSetMask(JNIEnv* env, jobject, jlong mask) {
  try {
    set_mask(static_cast<uint64_t>(mask));
  } catch (const std::exception& e) {
    throw_java(env, e.what());
  }
}
