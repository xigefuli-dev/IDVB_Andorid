#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>
#include <chrono>
#include <atomic>
#include <new>
#ifdef __aarch64__
#include <arm_neon.h>
#endif

namespace {
// The owning token retains its atomic flag for the duration of the JNI call. Reads
// stay in native code at every existing checkpoint; no Java callback per grid block.
class Cancellation {
    const std::atomic<int>* flag = nullptr;
    bool failed = false;
public:
    Cancellation(JNIEnv* env, jobject token) {
        if (token) {
            auto type = env->GetObjectClass(token);
            auto method = env->GetMethodID(type, "nativeStopAddress", "()J");
            if (method) flag = reinterpret_cast<const std::atomic<int>*>(static_cast<uintptr_t>(env->CallLongMethod(token, method)));
            failed = env->ExceptionCheck() || !flag;
            env->DeleteLocalRef(type);
        }
    }
    bool cancelled() const { return failed || (flag && flag->load(std::memory_order_acquire) != 0); }
};
template<typename T, typename A> class Elements;
template<> class Elements<jint, jintArray> {
    JNIEnv* env; jintArray array;
public:
    jint* data;
    Elements(JNIEnv* e, jintArray a) : env(e), array(a), data(e->GetIntArrayElements(a, nullptr)) {}
    ~Elements() { if (data) env->ReleaseIntArrayElements(array, data, JNI_ABORT); }
};
template<> class Elements<jlong, jlongArray> {
    JNIEnv* env; jlongArray array;
public:
    jlong* data;
    Elements(JNIEnv* e, jlongArray a) : env(e), array(a), data(e->GetLongArrayElements(a, nullptr)) {}
    ~Elements() { if (data) env->ReleaseLongArrayElements(array, data, JNI_ABORT); }
};
template<> class Elements<jbyte, jbyteArray> {
    JNIEnv* env; jbyteArray array;
public:
    jbyte* data;
    Elements(JNIEnv* e, jbyteArray a) : env(e), array(a), data(e->GetByteArrayElements(a, nullptr)) {}
    ~Elements() { if (data) env->ReleaseByteArrayElements(array, data, JNI_ABORT); }
};
template<> class Elements<jdouble, jdoubleArray> {
    JNIEnv* env; jdoubleArray array;
public:
    jdouble* data;
    Elements(JNIEnv* e, jdoubleArray a) : env(e), array(a), data(e->GetDoubleArrayElements(a, nullptr)) {}
    ~Elements() { if (data) env->ReleaseDoubleArrayElements(array, data, JNI_ABORT); }
};
struct Index {
    int width, height, stride;
    const jlong *k3, *k5;
    bool hit(const jlong* words, int x, int y) const {
        return x >= 0 && y >= 0 && x < width && y < height &&
            (static_cast<uint64_t>(words[y * stride + (x >> 6)]) & (uint64_t{1} << (x & 63)));
    }
};
struct Pose { double scale, x, y, score; };
// Kotlin.math.round is ties-to-even. Do not replace with std::round (ties-away),
// float math, fused multiply-add, or approximate reciprocal instructions.
int rounded(double value) { return static_cast<int>(std::nearbyint(value)); }
double score(const jint* points, int count, const Index& index, const Pose& pose, double incumbent) {
    int h3 = 0, h5 = 0;
    const double inverse = 1.0 / pose.scale;
    for (int i = 0; i < count; ++i) {
        const int x = rounded((points[2 * i] - pose.x) * inverse);
        const int y = rounded((points[2 * i + 1] - pose.y) * inverse);
        if (index.hit(index.k5, x, y)) { ++h5; if (index.hit(index.k3, x, y)) ++h3; }
        if ((i & 15) == 15 && (h5 + 2.0 * h3 + 3.0 * (count - i - 1)) / (3.0 * count) <= incumbent) return -1.0;
    }
    return (h5 + 2.0 * h3) / (3.0 * std::max(1, count));
}

}

// Exact source-domain reduction: all global consumers require V >= 82.
// The bounded green detector additionally needs H=35..95, S>=55, V>=45.
// Low-V green candidates necessarily have G>R and channelRange*5>=V;
// this is a conservative necessary condition, not a replacement for its HSV gate.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_hsvDomainsNative(
    JNIEnv* env, jobject, jlong sourceAddress, jlong sourceStep, jint width, jint height, jobject token) {
    Cancellation cancellation(env, token);
    const auto* source = reinterpret_cast<const uint8_t*>(static_cast<uintptr_t>(sourceAddress));
    const int greenX = static_cast<int>(width * .28), greenY = static_cast<int>(height * .68);
    struct Bounds {
        int left, right, top, bottom;
        void add(int x, int end, int y) {
            left = std::min(left, x); right = std::max(right, end);
            top = std::min(top, y); bottom = std::max(bottom, y + 1);
        }
    };
    constexpr int bandHeight = 32;
    std::vector<Bounds> bands((height + bandHeight - 1) / bandHeight, Bounds{width, 0, height, 0});
#ifdef __aarch64__
    const auto addMask = [](uint8x16_t mask, int x, int y, Bounds& bounds) {
        const auto bits = vreinterpretq_u64_u8(mask);
        const uint64_t low = vgetq_lane_u64(bits, 0), high = vgetq_lane_u64(bits, 1);
        if (low || high) {
            const int first = low ? __builtin_ctzll(low) / 8 : 8 + __builtin_ctzll(high) / 8;
            const int last = high ? 16 - __builtin_clzll(high) / 8 : 8 - __builtin_clzll(low) / 8;
            bounds.add(x + first, x + last, y);
        }
    };
#endif
    for (int y = 0; y < height; ++y) {
        if (cancellation.cancelled()) return nullptr;
        auto& bright = bands[y / bandHeight];
        const auto* input = source + y * sourceStep;
        int x = 0;
#ifdef __aarch64__
        for (; x + 16 <= width; x += 16, input += 48) {
            const auto rgb = vld3q_u8(input);
            const auto value = vmaxq_u8(rgb.val[0], vmaxq_u8(rgb.val[1], rgb.val[2]));
            addMask(vcgeq_u8(value, vdupq_n_u8(82)), x, y, bright);
            if (y >= greenY && x < greenX) {
                static const uint8_t columns[16] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
                const auto region = vcltq_u8(vld1q_u8(columns), vdupq_n_u8(std::min(16, greenX - x)));
                const auto minimum = vminq_u8(rgb.val[0], vminq_u8(rgb.val[1], rgb.val[2]));
                const auto range = vsubq_u8(value, minimum);
                const auto low = vcgeq_u16(vmull_u8(vget_low_u8(range), vdup_n_u8(5)), vmovl_u8(vget_low_u8(value)));
                const auto high = vcgeq_u16(vmull_u8(vget_high_u8(range), vdup_n_u8(5)), vmovl_u8(vget_high_u8(value)));
                auto eligible = vandq_u8(vcombine_u8(vmovn_u16(low), vmovn_u16(high)), vcgtq_u8(rgb.val[1], rgb.val[2]));
                eligible = vandq_u8(eligible, vandq_u8(vcgeq_u8(value, vdupq_n_u8(45)), vcltq_u8(value, vdupq_n_u8(82))));
                addMask(vandq_u8(region, eligible), x, y, bright);
            }
        }
#endif
        for (; x < width; ++x, input += 3) {
            const int v = std::max(input[0], std::max(input[1], input[2]));
            if (v >= 82) bright.add(x, x + 1, y);
            else if (x < greenX && y >= greenY && v >= 45 && input[1] > input[2] &&
                (v - std::min(input[0], std::min(input[1], input[2]))) * 5 >= v) bright.add(x, x + 1, y);
        }
    }
    std::vector<jint> result;
    const auto append = [&](Bounds bounds) {
        if (bounds.left >= bounds.right) return;
        bounds.left &= ~15; bounds.right = std::min(width, (bounds.right + 15) & ~15);
        if (result.size() >= 4 && result[result.size() - 4] == bounds.left &&
            result[result.size() - 2] == bounds.right - bounds.left &&
            result[result.size() - 3] + result.back() == bounds.top) {
            result.back() += bounds.bottom - bounds.top;
            return;
        }
        result.insert(result.end(), {bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top});
    };
    for (const auto& band : bands) append(band);
    auto output = env->NewIntArray(result.size());
    if (output && !result.empty()) env->SetIntArrayRegion(output, 0, result.size(), result.data());
    return output;
}

// Fused versions of the unchanged inclusive HSV predicates and exclusion masks.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_semanticMasksNative(
    JNIEnv* env, jobject, jlong hsvAddress, jlong hsvStep, jlong exclusionAddress, jlong exclusionStep,
    jlong roomAddress, jlong roomStep, jlong corridorAddress, jlong corridorStep,
    jlong redAddress, jlong redStep, jlong yellowAddress, jlong yellowStep,
    jint width, jint height, jobject token) {
    Cancellation cancellation(env, token);
    const auto* hsv = reinterpret_cast<const uint8_t*>(static_cast<uintptr_t>(hsvAddress));
    const auto* exclusion = reinterpret_cast<const uint8_t*>(static_cast<uintptr_t>(exclusionAddress));
    auto* room = reinterpret_cast<uint8_t*>(static_cast<uintptr_t>(roomAddress));
    auto* corridor = reinterpret_cast<uint8_t*>(static_cast<uintptr_t>(corridorAddress));
    auto* red = reinterpret_cast<uint8_t*>(static_cast<uintptr_t>(redAddress));
    auto* yellow = reinterpret_cast<uint8_t*>(static_cast<uintptr_t>(yellowAddress));
    for (int y = 0; y < height; ++y) {
        if (cancellation.cancelled()) return JNI_FALSE;
        const auto* input = hsv + y * hsvStep; const auto* invalid = exclusion + y * exclusionStep;
        auto* brown = room + y * roomStep; auto* blue = corridor + y * corridorStep;
        int x = 0;
#ifdef __aarch64__
        const auto range = [](uint8x16_t value, uint8_t low, uint8_t high) {
            return vandq_u8(vcgeq_u8(value, vdupq_n_u8(low)), vcleq_u8(value, vdupq_n_u8(high)));
        };
        for (; x + 16 <= width; x += 16) {
            const auto v = vld3q_u8(input + x * 3);
            auto valid = vandq_u8(range(v.val[2], 82, 200), vceqq_u8(vld1q_u8(invalid + x), vdupq_n_u8(0)));
            const auto hue = vorrq_u8(vcleq_u8(v.val[0], vdupq_n_u8(25)), range(v.val[0], 170, 179));
            vst1q_u8(brown + x, vandq_u8(valid, vandq_u8(hue, range(v.val[1], 18, 165))));
            vst1q_u8(blue + x, vandq_u8(valid, vandq_u8(range(v.val[0], 95, 130), range(v.val[1], 14, 105))));
            if (red) vst1q_u8(red + y * redStep + x, vandq_u8(
                vorrq_u8(vcleq_u8(v.val[0], vdupq_n_u8(5)), range(v.val[0], 160, 179)),
                vandq_u8(vcgeq_u8(v.val[1], vdupq_n_u8(65)), vcgeq_u8(v.val[2], vdupq_n_u8(95)))));
            if (yellow) vst1q_u8(yellow + y * yellowStep + x, vandq_u8(range(v.val[0], 18, 38),
                vandq_u8(vcgeq_u8(v.val[1], vdupq_n_u8(100)), vcgeq_u8(v.val[2], vdupq_n_u8(160)))));
        }
#endif
        for (; x < width; ++x) {
            const int h = input[x * 3], s = input[x * 3 + 1], v = input[x * 3 + 2];
            const bool valid = !invalid[x] && v >= 82 && v <= 200;
            brown[x] = valid && (h <= 25 || (h >= 170 && h <= 179)) && s >= 18 && s <= 165 ? 255 : 0;
            blue[x] = valid && h >= 95 && h <= 130 && s >= 14 && s <= 105 ? 255 : 0;
            if (red) red[y * redStep + x] = (h <= 5 || (h >= 160 && h <= 179)) && s >= 65 && v >= 95 ? 255 : 0;
            if (yellow) yellow[y * yellowStep + x] = h >= 18 && h <= 38 && s >= 100 && v >= 160 ? 255 : 0;
        }
    }
    return JNI_TRUE;
}

// Packaged OpenCV 4.12: ARM Carotene uses one reciprocal refinement in 8-pixel
// blocks and different scalar-tail equations. Preserve both, including rounding.
// Source: opencv/opencv 4.12.0 hal/carotene/src/colorconvert.cpp.
// Consumers are exact; omitted pixels cannot pass a downstream HSV predicate.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_hsvConsumersNative(
    JNIEnv* env, jobject, jlong sourceAddress, jlong sourceStep, jlong outputAddress, jlong outputStep,
    jint width, jint height, jobject token) {
    Cancellation cancellation(env, token);
    const auto* source = reinterpret_cast<const uint8_t*>(static_cast<uintptr_t>(sourceAddress));
    auto* output = reinterpret_cast<uint8_t*>(static_cast<uintptr_t>(outputAddress));
    struct Tables {
        std::array<int, 256> saturation{}, hue{};
#ifdef __aarch64__
        std::array<uint32_t, 256> armSaturation{}, armHue{};
#endif
        Tables() {
            for (int i = 1; i < 256; ++i) {
                saturation[i] = rounded((255.0 * 4096.0) / i);
                hue[i] = rounded((180.0 * 4096.0) / (6 * i));
            }
#ifdef __aarch64__
            for (int i = 0; i < 256; i += 4) {
                const float values[4] = {float(i), float(i + 1), float(i + 2), float(i + 3)};
                const auto v = vld1q_f32(values);
                const auto reciprocal = vrecpeq_f32(v);
                const auto divisor = vmulq_f32(vmulq_f32(reciprocal, vrecpsq_f32(reciprocal, v)), vdupq_n_f32(255 * 4096));
                vst1q_u32(armSaturation.data() + i, vcvtnq_u32_f32(divisor));
                const auto six = vmulq_f32(v, vdupq_n_f32(6));
                const auto hueReciprocal = vrecpeq_f32(six);
                const auto hueDivisor = vmulq_f32(vmulq_f32(hueReciprocal, vrecpsq_f32(hueReciprocal, six)), vdupq_n_f32(180 * 4096));
                vst1q_u32(armHue.data() + i, vcvtnq_u32_f32(hueDivisor));
            }
            armSaturation[0] = armHue[0] = 0;
#endif
        }
    };
    static const Tables tables;
    const int greenX = static_cast<int>(width * .28), greenY = static_cast<int>(height * .68);
    jint counts[3] = {0, 0, 0};
    for (int y = 0; y < height; ++y) {
        if (cancellation.cancelled()) return nullptr;
        const auto* input = source + y * sourceStep;
        auto* result = output + y * outputStep;
        for (int x = 0; x < width; ++x, input += 3, result += 3) {
            const int b = input[0], g = input[1], r = input[2];
            const int value = std::max(b, std::max(g, r));
            const int diff = value - std::min(b, std::min(g, r));
            const bool green = value < 82 && x < greenX && y >= greenY && value >= 45 && g > r && diff * 5 >= value;
            if (value < 82 && !green) {
                result[0] = result[1] = result[2] = 0; ++counts[1]; continue;
            }
            const int numerator = value == r ? g - b : value == g ? b - r + 2 * diff : r - g + 4 * diff;
            int saturation, hue;
#ifdef __aarch64__
            if (x < (width & ~7)) {
                saturation = (diff * tables.armSaturation[value] + 2048) >> 12;
                hue = (numerator * int(tables.armHue[diff]) + 2048) >> 12;
            } else {
                saturation = (int(float(diff * (255 << 12)) * (1.0f / value)) + 2048) >> 12;
                hue = diff ? (numerator * int(float(180 << 12) / (6.f * diff) + .5) + 2048) >> 12 : 0;
            }
#else
            saturation = (diff * tables.saturation[value] + 2048) >> 12;
            hue = (numerator * tables.hue[diff] + 2048) >> 12;
#endif
            if (hue < 0) hue += 180;
            result[0] = hue; result[1] = saturation; result[2] = value;
            ++counts[0]; if (green) ++counts[2];
        }
    }
    auto result = env->NewIntArray(3);
    if (result) env->SetIntArrayRegion(result, 0, 3, counts);
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_borrowBufferNative(JNIEnv* env, jobject, jlong address, jlong bytes) {
    return env->NewDirectByteBuffer(reinterpret_cast<void*>(static_cast<uintptr_t>(address)), bytes);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_initializeStopFlagNative(JNIEnv* env, jobject, jobject buffer, jboolean cancelled) {
    auto* memory = env->GetDirectBufferAddress(buffer);
    if (!memory || env->GetDirectBufferCapacity(buffer) < static_cast<jlong>(sizeof(std::atomic<int>))) return 0;
    auto* flag = new(memory) std::atomic<int>(cancelled ? 1 : 0);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(flag));
}
extern "C" JNIEXPORT void JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_signalStopFlagNative(JNIEnv*, jobject, jlong address) {
    reinterpret_cast<std::atomic<int>*>(static_cast<uintptr_t>(address))->store(1, std::memory_order_release);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_reverseCoverageNative(
    JNIEnv* env, jobject, jobject positionsArray, jint total, jint referenceWidth, jobject domainArray,
    jint width, jint height, jobject distanceBuffer, jdouble scale, jdouble tx, jdouble ty,
    jboolean legacy, jboolean capture, jint domainX, jint domainY, jint domainWidth, jint domainHeight, jobject token) {
    const auto* positions = static_cast<const jint*>(env->GetDirectBufferAddress(positionsArray));
    const auto* domain = static_cast<const jbyte*>(env->GetDirectBufferAddress(domainArray));
    const auto* distance = static_cast<const float*>(env->GetDirectBufferAddress(distanceBuffer));
    if (!positions || !domain || !distance) return nullptr;
    const int legacyStep = std::max(1, (total + 2047) / 2048);
    Cancellation cancellation(env, token);
    std::vector<int> eligible;
    eligible.reserve(total);
    int inside = 0, hits = 0;
    const int step = legacy ? legacyStep : 1;
    // Reference edge positions are immutable, sorted row-major. Binary bounds use the
    // actual rounding predicate: no approximate inverse bounds can drop a boundary pixel.
    auto lower = [&](int lo, int hi, int bound, bool vertical) {
        while (lo < hi) {
            const int mid = lo + (hi - lo) / 2;
            const int coordinate = vertical ? positions[mid] / referenceWidth : positions[mid] % referenceWidth;
            const int screen = static_cast<int>(std::floor(coordinate * scale + (vertical ? ty : tx) + .5));
            if (screen < bound) lo = mid + 1; else hi = mid;
        }
        return lo;
    };
    const int first = lower(0, total, 0, true), last = lower(first, total, height, true);
    for (int rowStart = first; rowStart < last;) {
        if (cancellation.cancelled()) return nullptr;
        const int y = positions[rowStart] / referenceWidth;
        const int rowEnd = static_cast<int>(std::lower_bound(positions + rowStart, positions + last, (y + 1) * referenceWidth) - positions);
        const int begin = lower(rowStart, rowEnd, 0, false), end = lower(begin, rowEnd, width, false);
        const int firstSelected = (begin + step - 1) / step * step;
        if (firstSelected < end) inside += (end - 1 - firstSelected) / step + 1;
        const int iy = static_cast<int>(std::floor(y * scale + ty + .5));
        if (iy >= domainY && iy < domainY + domainHeight) {
            const int knownBegin = lower(begin, end, domainX, false), knownEnd = lower(knownBegin, end, domainX + domainWidth, false);
            for (int i = (knownBegin + step - 1) / step * step; i < knownEnd; i += step) {
                if ((i & 255) == 0 && cancellation.cancelled()) return nullptr;
                const int x = positions[i] % referenceWidth;
                const int ix = static_cast<int>(std::floor(x * scale + tx + .5));
                if (static_cast<unsigned char>(domain[iy * width + ix]) > 128) eligible.push_back(positions[i]);
            }
        }
        rowStart = rowEnd;
    }
    const int outside = (total + step - 1) / step - inside;
    const int unknown = inside - static_cast<int>(eligible.size());
    const int count = std::min(2048, static_cast<int>(eligible.size()));
    std::vector<double> output(5);
    if (capture) output.reserve(5 + count * 5);
    for (int i = 0; i < count; ++i) {
        if ((i & 63) == 0 && cancellation.cancelled()) return nullptr;
        const int position = eligible[static_cast<size_t>(i) * eligible.size() / count];
        const int x = position % referenceWidth, y = position / referenceWidth;
        const double sx = x * scale + tx, sy = y * scale + ty;
        double residual = 50.0;
        if (std::isfinite(sx) && std::isfinite(sy) && sx >= 0 && sy >= 0 && sx < width - 1 && sy < height - 1) {
            const int ix = static_cast<int>(sx), iy = static_cast<int>(sy);
            const double fx = sx - ix, fy = sy - iy;
            const double top = distance[iy * width + ix] * (1 - fx) + distance[iy * width + ix + 1] * fx;
            const double bottom = distance[(iy + 1) * width + ix] * (1 - fx) + distance[(iy + 1) * width + ix + 1] * fx;
            residual = top * (1 - fy) + bottom * fy;
        }
        if (residual <= 5.5) ++hits;
        if (capture) output.insert(output.end(), {static_cast<double>(x), static_cast<double>(y), sx, sy, residual});
    }
    output[0] = hits; output[1] = count; output[2] = outside; output[3] = unknown; output[4] = eligible.size();
    auto result = env->NewDoubleArray(static_cast<jsize>(output.size()));
    if (result) env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
}
namespace {
double scoreCoordinates(const int* xs, const int* ys, int count, const Index& index, double incumbent) {
    int h3 = 0, h5 = 0;
    for (int i = 0; i < count; ++i) {
        if (index.hit(index.k5, xs[i], ys[i])) { ++h5; if (index.hit(index.k3, xs[i], ys[i])) ++h3; }
        if ((i & 15) == 15 && (h5 + 2.0 * h3 + 3.0 * (count - i - 1)) / (3.0 * count) <= incumbent) return -1.0;
    }
    return (h5 + 2.0 * h3) / (3.0 * std::max(1, count));
}
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_scoreGridNative(
    JNIEnv* env, jobject, jintArray pointsArray, jobject wordsArray, jint width, jint height,
    jint minX, jint maxX, jint minY, jint maxY, jint stride, jobject token) {
    Elements<jint, jintArray> points(env, pointsArray);
    const auto* words = static_cast<const jlong*>(env->GetDirectBufferAddress(wordsArray));
    if (!points.data || !words) return nullptr;
    const int count = env->GetArrayLength(pointsArray) / 2;
    const int columns = (maxX - minX) / stride + 1;
    const int rows = (maxY - minY) / stride + 1;
    const int rowWords = stride == 4 ? (width + 255) / 256 : (width + 63) / 64;
    const int phaseSize = rowWords * height;
    std::vector<int> baseX(count), phases(count);
    if (stride == 4) for (int i = 0; i < count; ++i) {
        const int x = points.data[2 * i] + minX;
        phases[i] = (x % 4 + 4) % 4;
        baseX[i] = (x - phases[i]) / 4;
    }
    std::vector<jint> scores;
    scores.reserve(static_cast<size_t>(columns) * rows);
    Cancellation cancellation(env, token);
    for (int row = 0; row < rows; ++row) {
        if (cancellation.cancelled()) return nullptr;
        scores.resize(static_cast<size_t>(row + 1) * columns);
        const int dy = minY + row * stride;
        const int blockLimit = stride == 4 ? columns - 1 : maxX;
        for (int blockX = stride == 4 ? 0 : minX; blockX <= blockLimit; blockX += 64) {
            if (cancellation.cancelled()) return nullptr;
            std::array<uint64_t, 9> planes{};
            for (int i = 0; i < count; ++i) {
                const int x = stride == 4 ? baseX[i] + blockX : points.data[2 * i] + blockX;
                const int y = points.data[2 * i + 1] + dy;
                const int activeWidth = stride == 4 ? (width + 3 - phases[i]) / 4 : width;
                const int rowOffset = y * rowWords + (stride == 4 ? phases[i] * phaseSize : 0);
                if (y < 0 || y >= height || x >= activeWidth || x <= -64) continue;
                uint64_t carry;
                if (x < 0) carry = static_cast<uint64_t>(words[rowOffset]) << -x;
                else {
                    const int wordX = x >> 6, shift = x & 63;
                    carry = static_cast<uint64_t>(words[rowOffset + wordX]) >> shift;
                    if (shift && wordX + 1 < rowWords)
                        carry |= static_cast<uint64_t>(words[rowOffset + wordX + 1]) << (64 - shift);
                }
                const int remaining = activeWidth - x;
                if (remaining < 64) carry &= (uint64_t{1} << remaining) - 1;
                int bit = 0;
                while (carry) {
                    const uint64_t next = planes[bit] & carry;
                    planes[bit] ^= carry; carry = next; ++bit;
                }
            }
            const int laneStride = stride == 4 ? 1 : stride;
            const int first = stride == 4 ? 0 : (stride - (blockX - minX) % stride) % stride;
            int firstScalar = first;
#ifdef __aarch64__
            if (stride == 4 && count <= 255) {
                const uint8_t masks[16] = {1,2,4,8,16,32,64,128,1,2,4,8,16,32,64,128};
                const auto laneMasks = vld1q_u8(masks);
                const int lanes = std::min(64, blockLimit - blockX + 1);
                for (int lane = 0; lane + 16 <= lanes; lane += 16) {
                    uint8x16_t hits = vdupq_n_u8(0);
                    for (int bit = 0; bit < 8; ++bit) {
                        const uint16_t value = static_cast<uint16_t>(planes[bit] >> lane);
                        const auto active = vtstq_u8(vcombine_u8(vdup_n_u8(value & 255), vdup_n_u8(value >> 8)), laneMasks);
                        hits = vorrq_u8(hits, vandq_u8(active, vdupq_n_u8(1 << bit)));
                    }
                    const auto low = vmovl_u8(vget_low_u8(hits)), high = vmovl_u8(vget_high_u8(hits));
                    auto* out = reinterpret_cast<uint32_t*>(scores.data() + row * columns + blockX + lane);
                    vst1q_u32(out, vmovl_u16(vget_low_u16(low)));
                    vst1q_u32(out + 4, vmovl_u16(vget_high_u16(low)));
                    vst1q_u32(out + 8, vmovl_u16(vget_low_u16(high)));
                    vst1q_u32(out + 12, vmovl_u16(vget_high_u16(high)));
                    firstScalar = lane + 16;
                }
            }
#endif
            for (int lane = firstScalar; lane <= std::min(63, blockLimit - blockX); lane += laneStride) {
                int hits = 0;
                for (int bit = 0; bit < 9; ++bit) hits |= ((planes[bit] >> lane) & 1) << bit;
                scores[row * columns + (stride == 4 ? blockX + lane : (blockX + lane - minX) / stride)] = hits;
            }
        }
    }
    auto result = env->NewIntArray(static_cast<jsize>(scores.size()));
    if (result) env->SetIntArrayRegion(result, 0, static_cast<jsize>(scores.size()), scores.data());
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_prepareStride4Native(
    JNIEnv* env, jobject, jlongArray wordsArray, jint width, jint height) {
    Elements<jlong, jlongArray> words(env, wordsArray);
    if (!words.data) return nullptr;
    const int sourceStride = (width + 63) / 64, stride = (width + 255) / 256;
    std::vector<jlong> packed(4 * stride * height);
    for (int phase = 0; phase < 4; ++phase) for (int row = 0; row < height; ++row) for (int word = 0; word < sourceStride; ++word) {
        uint64_t v = (static_cast<uint64_t>(words.data[row * sourceStride + word]) >> phase) & 0x1111111111111111ULL;
        v = (v | (v >> 3)) & 0x0303030303030303ULL;
        v = (v | (v >> 6)) & 0x000F000F000F000FULL;
        v = (v | (v >> 12)) & 0x000000FF000000FFULL;
        v = (v | (v >> 24)) & 0xFFFFULL;
        packed[(phase * height + row) * stride + word / 4] |= static_cast<jlong>(v << (16 * (word % 4)));
    }
    auto result = env->NewLongArray(static_cast<jsize>(packed.size()));
    if (result) env->SetLongArrayRegion(result, 0, static_cast<jsize>(packed.size()), packed.data());
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_precisionLossesNative(
    JNIEnv* env, jobject, jintArray pointsArray, jdoubleArray posesArray, jobject distanceBuffer,
    jint width, jint height, jdouble cx, jdouble cy, jdouble rcx, jdouble rcy, jobject token) {
    Elements<jint, jintArray> points(env, pointsArray);
    Elements<jdouble, jdoubleArray> poses(env, posesArray);
    const auto* distances = static_cast<const float*>(env->GetDirectBufferAddress(distanceBuffer));
    if (!points.data || !poses.data || !distances) return nullptr;
    const int count = env->GetArrayLength(pointsArray) / 2, size = env->GetArrayLength(posesArray) / 3;
    std::array<int, 4> counts{};
    std::vector<int> partitions(count);
    for (int i = 0; i < count; ++i) {
        partitions[i] = (points.data[2 * i] < cx ? 0 : 1) + (points.data[2 * i + 1] < cy ? 0 : 2);
        ++counts[partitions[i]];
    }
    Cancellation cancellation(env, token);
    std::vector<double> output(size);
    for (int pose = 0; pose < size; ++pose) {
        if (cancellation.cancelled()) return nullptr;
        const double scale = poses.data[pose * 3], dx = poses.data[pose * 3 + 1], dy = poses.data[pose * 3 + 2];
        std::array<double, 4> sums{};
        for (int i = 0; i < count; ++i) {
            const double x = rcx + (points.data[2 * i] - cx - dx) / scale;
            const double y = rcy + (points.data[2 * i + 1] - cy - dy) / scale;
            double d = 50.0;
            if (std::isfinite(x) && std::isfinite(y) && x >= 0 && y >= 0 && x < width - 1 && y < height - 1) {
                const int ix = static_cast<int>(x), iy = static_cast<int>(y);
                const double fx = x - ix, fy = y - iy;
                const double top = distances[iy * width + ix] * (1 - fx) + distances[iy * width + ix + 1] * fx;
                const double bottom = distances[(iy + 1) * width + ix] * (1 - fx) + distances[(iy + 1) * width + ix + 1] * fx;
                d = (top * (1 - fy) + bottom * fy) * scale;
            }
            d = std::min(6.0, d);
            sums[partitions[i]] += d <= 1.5 ? .5 * d * d : 1.5 * (d - .75);
        }
        double sum = 0; int valid = 0;
        for (int i = 0; i < 4; ++i) if (counts[i] >= 15) { sum += sums[i] / counts[i]; ++valid; }
        output[pose] = sum / valid;
    }
    auto result = env->NewDoubleArray(size);
    if (result) env->SetDoubleArrayRegion(result, 0, size, output.data());
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_verifyForwardNative(
    JNIEnv* env, jobject, jintArray pointsArray, jintArray segmentsArray, jobject distanceBuffer,
    jint referenceWidth, jint referenceHeight, jint width, jint height, jdouble scale, jdouble tx, jdouble ty,
    jdoubleArray thresholdsArray, jboolean capture, jobject token) {
    Elements<jint, jintArray> points(env, pointsArray), segments(env, segmentsArray);
    Elements<jdouble, jdoubleArray> thresholds(env, thresholdsArray);
    const auto* distances = static_cast<const float*>(env->GetDirectBufferAddress(distanceBuffer));
    if (!points.data || !segments.data || !thresholds.data || !distances) return nullptr;
    Cancellation cancellation(env, token);
    const int count = env->GetArrayLength(pointsArray) / 2, segmentCount = env->GetArrayLength(segmentsArray) / 4;
    auto distance = [&](int px, int py) {
        const double x = (px - tx) / scale, y = (py - ty) / scale;
        if (!std::isfinite(x) || !std::isfinite(y) || x < 0 || y < 0 || x >= referenceWidth - 1 || y >= referenceHeight - 1) return 50.0;
        const int ix = static_cast<int>(x), iy = static_cast<int>(y);
        const double fx = x - ix, fy = y - iy;
        const double top = distances[iy * referenceWidth + ix] * (1 - fx) + distances[iy * referenceWidth + ix + 1] * fx;
        const double bottom = distances[(iy + 1) * referenceWidth + ix] * (1 - fx) + distances[(iy + 1) * referenceWidth + ix + 1] * fx;
        return (top * (1 - fy) + bottom * fy) * scale;
    };
    std::vector<double> output(38 + (capture ? count : 0));
    std::array<int, 16> totals{}, cellHits{};
    int hits = 0; double sum = 0, longest = 0;
    auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < count; ++i) {
        if ((i & 63) == 0 && cancellation.cancelled()) return nullptr;
        const int x = points.data[2 * i], y = points.data[2 * i + 1];
        const double d = distance(x, y);
        const int cell = std::min(3, x * 4 / width) + 4 * std::min(3, y * 4 / height);
        ++totals[cell]; sum += d;
        if (d <= thresholds.data[0]) { ++hits; ++cellHits[cell]; }
        if (capture) output[38 + i] = d;
    }
    auto middle = std::chrono::steady_clock::now();
    bool conflict = false;
    for (int i = 0; i < 16; ++i) if (totals[i] >= thresholds.data[2] && cellHits[i] < totals[i] * thresholds.data[3]) conflict = true;
    const bool checkContours = hits / static_cast<double>(count) >= thresholds.data[1] && !conflict;
    if (checkContours) for (int i = 0; i < segmentCount; ++i) {
        const int ax = segments.data[i * 4], ay = segments.data[i * 4 + 1], bx = segments.data[i * 4 + 2], by = segments.data[i * 4 + 3];
        const int dx = bx - ax, dy = by - ay, steps = std::max(std::abs(dx), std::abs(dy));
        if (!steps) continue;
        const double stride = std::hypot(static_cast<double>(dx), static_cast<double>(dy)) / steps;
        double run = 0, segmentMax = 0;
        for (int step = 0; step <= steps; ++step) {
            if ((step & 63) == 0 && cancellation.cancelled()) return nullptr;
            const int x = static_cast<int>(std::floor(ax + dx * static_cast<double>(step) / steps + .5));
            const int y = static_cast<int>(std::floor(ay + dy * static_cast<double>(step) / steps + .5));
            if (distance(x, y) > thresholds.data[0]) run += step == 0 ? 0.0 : stride; else run = 0;
            longest = std::max(longest, run); segmentMax = std::max(segmentMax, run);
        }
        if (capture) output.insert(output.end(), {static_cast<double>(ax), static_cast<double>(ay), static_cast<double>(bx), static_cast<double>(by), segmentMax});
    }
    const auto end = std::chrono::steady_clock::now();
    output[0] = hits; output[1] = sum; output[2] = longest; output[3] = checkContours ? 1 : 0;
    output[4] = std::chrono::duration_cast<std::chrono::nanoseconds>(middle - start).count();
    output[5] = std::chrono::duration_cast<std::chrono::nanoseconds>(end - middle).count();
    for (int i = 0; i < 16; ++i) { output[6 + i] = totals[i]; output[22 + i] = cellHits[i]; }
    auto result = env->NewDoubleArray(static_cast<jsize>(output.size()));
    if (result) env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_translationCandidatesNative(
    JNIEnv* env, jobject, jintArray pointsArray, jobject k3Buffer, jint width, jint height, jintArray scoresArray,
    jint minX, jint maxX, jint minY, jint maxY, jdouble scale, jdouble minimumRivalDistance, jobject token) {
    Elements<jint, jintArray> points(env, pointsArray), scores(env, scoresArray);
    const auto* words = static_cast<const jlong*>(env->GetDirectBufferAddress(k3Buffer));
    if (!points.data || !scores.data || !words) return nullptr;
    Index index{width, height, (width + 63) / 64, words, words};
    Cancellation cancellation(env, token);
    struct Vote { int x, y, hits; };
    const int columns = (maxX - minX) / 4 + 1, size = env->GetArrayLength(scoresArray), count = env->GetArrayLength(pointsArray) / 2;
    std::vector<Vote> pool;
    pool.reserve(33);
    for (int i = 0; i < size; ++i) {
        if ((i & 4095) == 0 && cancellation.cancelled()) return nullptr;
        const int hits = scores.data[i];
        if (hits < 5 || (pool.size() == 32 && hits <= pool.back().hits)) continue;
        auto at = std::find_if(pool.begin(), pool.end(), [&](const Vote& v) { return v.hits < hits; });
        pool.insert(at, {minX + i % columns * 4, minY + i / columns * 4, hits});
        if (pool.size() > 32) pool.pop_back();
    }
    auto hitsAt = [&](int x, int y) {
        int hits = 0;
        for (int i = 0; i < count; ++i) if (index.hit(words, points.data[2 * i] + x, points.data[2 * i + 1] + y)) ++hits;
        return hits;
    };
    auto polish = [&](Vote vote) {
        Vote best{vote.x, vote.y, hitsAt(vote.x, vote.y)};
        for (int y = std::max(minY, vote.y - 2); y <= std::min(maxY, vote.y + 2); ++y)
            for (int x = std::max(minX, vote.x - 2); x <= std::min(maxX, vote.x + 2); ++x) {
                const int hits = hitsAt(x, y);
                if (hits > best.hits) best = {x, y, hits};
            }
        return best;
    };
    auto polished = pool;
    for (auto& vote : polished) {
        if (cancellation.cancelled()) return nullptr;
        vote = polish(vote);
    }
    std::stable_sort(polished.begin(), polished.end(), [](const Vote& a, const Vote& b) { return a.hits > b.hits; });
    std::vector<Vote> distinct;
    for (const auto& vote : polished) if (std::none_of(distinct.begin(), distinct.end(), [&](const Vote& v) {
        return std::abs(v.x - vote.x) <= 2 && std::abs(v.y - vote.y) <= 2;
    })) distinct.push_back(vote);
    if (!distinct.empty()) {
        const auto best = distinct.front();
        auto distance = [&](const Vote& v) { return std::hypot((v.x - best.x) * scale, (v.y - best.y) * scale); };
        if (std::none_of(distinct.begin(), distinct.end(), [&](const Vote& v) { return distance(v) >= minimumRivalDistance; })) {
            Vote rival{0, 0, 0};
            for (int i = 0; i < size; ++i) {
                if ((i & 4095) == 0 && cancellation.cancelled()) return nullptr;
                // Strictly identical winner and tie order: a score that cannot
                // replace the incumbent needs neither coordinates nor hypot.
                const int hits = scores.data[i];
                if (hits < 3 || hits <= rival.hits) continue;
                Vote v{minX + i % columns * 4, minY + i / columns * 4, hits};
                const double allowance = minimumRivalDistance > 10 ? 2 * std::sqrt(2.0) * scale : 0;
                if (distance(v) >= minimumRivalDistance + allowance && v.hits >= 3 && v.hits > rival.hits) rival = v;
            }
            if (rival.hits) { rival = polish(rival); if (distance(rival) >= minimumRivalDistance) distinct.push_back(rival); }
        }
    }
    std::vector<double> output{static_cast<double>(pool.size())};
    for (const auto& v : distinct) output.insert(output.end(), {static_cast<double>(v.x), static_cast<double>(v.y), static_cast<double>(v.hits)});
    auto result = env->NewDoubleArray(static_cast<jsize>(output.size()));
    if (result) env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_idvb_android_recognize_vpsg_VpsgNativeKernel_refineNative(
    JNIEnv* env, jobject, jintArray pointsArray, jobject k3Array, jobject k5Array, jint width, jint height,
    jdouble seedScale, jdouble seedX, jdouble seedY, jdouble cx, jdouble cy,
    jdouble maximumScale, jboolean captureProbes, jobject token) {
    Elements<jint, jintArray> points(env, pointsArray);
    const auto* k3 = static_cast<const jlong*>(env->GetDirectBufferAddress(k3Array));
    const auto* k5 = static_cast<const jlong*>(env->GetDirectBufferAddress(k5Array));
    if (!points.data || !k3 || !k5) return nullptr;
    Index index{width, height, (width + 63) / 64, k3, k5};
    const int count = env->GetArrayLength(pointsArray) / 2;
    const Pose seed{seedScale, seedX, seedY, 0.0};
    Pose best = seed;
    best.score = score(points.data, count, index, seed, -1.0);
    std::vector<double> probes;
    if (captureProbes) probes.reserve(278 * 4);
    Cancellation cancellation(env, token);
    int evaluated = 0;
    auto remember = [&](Pose pose) {
        ++evaluated;
        if (captureProbes) probes.insert(probes.end(), {pose.scale, pose.x, pose.y, pose.score});
        if (pose.score > best.score) best = pose;
    };
    auto probe = [&](const Pose& center, double ds, double dx, double dy) {
        const double s = center.scale + ds;
        if (s < .35 || s > maximumScale) return;
        Pose pose{s, cx - (cx - center.x) / center.scale * s + dx,
            cy - (cy - center.y) / center.scale * s + dy, 0.0};
        pose.score = score(points.data, count, index, pose, best.score);
        remember(pose);
    };
    std::vector<int> xs(7 * count), ys(7 * count);
    for (double ds : {-.020, -.015, 0.0, .015, .020}) {
        const double s = seed.scale + ds;
        if (s < .35 || s > maximumScale) continue;
        const double inverse = 1.0 / s;
        const double bx = cx - (cx - seed.x) / seed.scale * s, by = cy - (cy - seed.y) / seed.scale * s;
        for (int axis = 0; axis < 7; ++axis) for (int i = 0; i < count; ++i) {
            xs[axis * count + i] = rounded((points.data[2 * i] - (bx + (-6 + axis * 2))) * inverse);
            ys[axis * count + i] = rounded((points.data[2 * i + 1] - (by + (-6 + axis * 2))) * inverse);
        }
        for (int xi = 0; xi < 7; ++xi) for (int yi = 0; yi < 7; ++yi) {
            if ((evaluated & 7) == 0 && cancellation.cancelled()) return nullptr;
            Pose pose{s, bx + (-6 + xi * 2), by + (-6 + yi * 2), 0.0};
            pose.score = scoreCoordinates(xs.data() + xi * count, ys.data() + yi * count, count, index, best.score);
            remember(pose);
        }
    }
    const Pose coarse = best;
    for (double ds : {-.020, -.010, -.005, .005, .010, .020}) {
        if (cancellation.cancelled()) return nullptr;
        probe(coarse, ds, 0, 0);
    }
    const Pose fine = best;
    for (double ds : {-.005, 0.0, .005}) for (double dx : {-1.5, 0.0, 1.5}) for (double dy : {-1.5, 0.0, 1.5}) {
        if ((evaluated & 7) == 0 && cancellation.cancelled()) return nullptr;
        probe(fine, ds, dx, dy);
    }
    std::vector<double> output{best.scale, best.x, best.y, best.score, static_cast<double>(evaluated)};
    output.insert(output.end(), probes.begin(), probes.end());
    auto result = env->NewDoubleArray(static_cast<jsize>(output.size()));
    if (result) env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
}

// Exact sidebar comparison, including the full-strip fallback. No reduced search domain.
namespace {
using SidebarPlanes = std::array<const double*, 3>;
std::array<double, 8> sidebarCompare(const SidebarPlanes& reference, const SidebarPlanes& candidate) {
    std::array<double, 8> best{};
    best[0] = -1.0; best[7] = 1.0;
    for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
        double delta = 0.0;
        int count = 0;
        for (int y = 1; y < 23; ++y) for (int x = 1; x < 31; ++x) {
            delta += candidate[0][(y + dy) * 32 + x + dx] - reference[0][y * 32 + x];
            ++count;
        }
        const double compensation = std::clamp(delta / count, -.12, .12);
        double colorError = 0.0, brightnessError = 0.0, edgeError = 0.0;
        for (int y = 1; y < 23; ++y) for (int x = 1; x < 31; ++x) {
            const int a = y * 32 + x, b = (y + dy) * 32 + x + dx;
            colorError += std::min(1.0, (std::abs(reference[1][a] - candidate[1][b]) +
                std::abs(reference[2][a] - candidate[2][b])) / .24);
            brightnessError += std::min(1.0, std::abs(reference[0][a] - candidate[0][b] + compensation) / .18);
            const double ax = reference[0][a + 1] - reference[0][a], ay = reference[0][a + 32] - reference[0][a];
            const double bx = x + dx + 1 < 32 ? candidate[0][b + 1] - candidate[0][b] : 0.0;
            const double by = y + dy + 1 < 24 ? candidate[0][b + 32] - candidate[0][b] : 0.0;
            edgeError += std::min(1.0, (std::abs(ax - bx) + std::abs(ay - by)) / .24);
        }
        const double color = 1.0 - colorError / count, brightness = 1.0 - brightnessError / count, edge = 1.0 - edgeError / count;
        const double score = std::clamp(.4 * color + .35 * brightness + .25 * edge, 0.0, 1.0);
        if (score > best[0]) best = {score, color, brightness, edge, double(dx), double(dy), 0.0, 1.0};
    }
    return best;
}
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_idvb_android_alignment_AutoMapOpenNativeKernel_compareNative(JNIEnv* env, jobject,
    jdoubleArray rl, jdoubleArray rr, jdoubleArray rb, jdoubleArray cl, jdoubleArray cr, jdoubleArray cb, jdouble fraction) {
    if (!std::isfinite(fraction) || fraction <= 0.0 || fraction > 1.0) return nullptr;
    for (auto array : {rl, rr, rb, cl, cr, cb}) if (!array || env->GetArrayLength(array) != 768) return nullptr;
    Elements<jdouble, jdoubleArray> r0(env, rl), r1(env, rr), r2(env, rb), c0(env, cl), c1(env, cr), c2(env, cb);
    if (!r0.data || !r1.data || !r2.data || !c0.data || !c1.data || !c2.data) return nullptr;
    SidebarPlanes reference{r0.data, r1.data, r2.data}, candidate{c0.data, c1.data, c2.data};
    auto best = sidebarCompare(reference, candidate);
    std::array<std::array<double, 768>, 3> resampled;
    SidebarPlanes window{resampled[0].data(), resampled[1].data(), resampled[2].data()};
    for (double scale : {.85, 1.0, 1.15}) {
        const double width = std::clamp(32 * fraction * scale, 4.0, 32.0), last = 32 - width;
        const int steps = int(std::ceil(last / .5));
        for (int step = 0; step <= steps; ++step) {
            const double left = std::min(step * .5, last);
            // Coordinates and fractions are shared by all channels and rows.
            for (int x = 0; x < 32; ++x) {
                const double source = std::clamp(left + (x + .5) * width / 32 - .5, 0.0, 31.0);
                const int x0 = int(source), x1 = std::min(x0 + 1, 31);
                const double part = source - x0;
                for (int channel = 0; channel < 3; ++channel) for (int y = 0; y < 24; ++y)
                    resampled[channel][y * 32 + x] = candidate[channel][y * 32 + x0] * (1 - part) + candidate[channel][y * 32 + x1] * part;
            }
            auto fit = sidebarCompare(reference, window);
            fit[6] = left / 32; fit[7] = width / 32;
            if (fit[0] > best[0]) best = fit;
        }
    }
    auto result = env->NewDoubleArray(8);
    if (result) env->SetDoubleArrayRegion(result, 0, 8, best.data());
    return result;
}
