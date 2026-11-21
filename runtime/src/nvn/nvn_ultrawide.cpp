#include "nvn_ultrawide.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "nvn.h"
#include "nvn_pipeline.h"
#include <nx/module.h>
#include <nx/runtime.h>

#include "kernel.h"
#include "platform.h"

namespace nx::nvn {

namespace {

constexpr double k169 = 16.0 / 9.0;

float init_factor() {
    const char* e = std::getenv("NX_ULTRAWIDE");
    if (!e || !*e || e[0] == '0') return 1.0f;
    double aspect = 0;
    unsigned w = 0, h = 0;
    if (std::sscanf(e, "%ux%u", &w, &h) == 2 && h) {
        aspect = double(w) / h;
    } else if (std::sscanf(e, "%u:%u", &w, &h) == 2 && h) {
        aspect = double(w) / h;
    } else {  // "1" / "auto": the window's shape
        uint32_t ww = 0, wh = 0;
        plat::window_size(ww, wh);
        if (wh) aspect = double(ww) / wh;
    }
    float k = float(aspect / k169);
    if (k < 1.02f) k = 1.0f;  // 16:9 and narrower: nothing to do
    if (k > 3.0f) k = 3.0f;
    log("nvn: ultrawide: display aspect %.4f, render targets widened by %.4f", aspect, double(k));
    return k;
}

}  // namespace

float uw_factor() {
    static const float k = init_factor();
    return k;
}

uint32_t uw_widen(uint32_t w) {
    uint32_t v = uint32_t(std::lround(double(w) * uw_factor()));
    return v & ~1u;
}

bool uw_widen_texture(Texture& t) {
    // Render targets (flags & 8), window textures (flags & 1), or uncompressed scene colour (flags == 0);
    // strictly exclude pitch-linear targets (t.stride != 0, e.g. P5R's 480x270 R32F CPU occlusion depth pyramid)
    // and plain packaged assets (flags == 4).
    if (!uw_on() || t.target != 1 || t.stride != 0 || ((t.flags & 4) && !(t.flags & 9)) || t.width < 16 || t.height < 9)
        return false;
    double r = double(t.width) / double(t.height);
    if (std::fabs(r - k169) / k169 > 0.03) return false;
    static std::map<std::string, int> seen;  // each distinct widened shape once, for the study log
    char key[64];
    std::snprintf(key, sizeof key, "%ux%u f%x fmt%x", t.width, t.height, t.flags, t.format);
    if (seen[key]++ == 0) log("nvn: ultrawide: widening %s", key);
    t.width = uw_widen(t.width);
    t.uw = true;
    return true;
}

bool uw_crop(int32_t crop[4]) {
    if (!uw_on() || crop[0] != 0 || crop[1] != 0 || crop[2] <= 0 || crop[3] <= 0) return false;
    double r = double(crop[2]) / double(crop[3]);
    if (std::fabs(r - k169) / k169 > 0.03) return false;
    crop[2] = int32_t(uw_widen(uint32_t(crop[2])));
    return true;
}


// NX_UW_SCAN=<present>: once, at that present, lists where guest memory (heap, main's data and bss) holds the numbers
// of the 16:9 picture (aspect 16/9 and 9/16 floats, 1920x1080 and 1280x720 as int and float pairs), with context.
void uw_scan(uint64_t present) {
    static const char* e = std::getenv("NX_UW_SCAN");
    static const uint64_t at = e ? std::strtoull(e, nullptr, 10) : 0;
    static bool done = false;
    if (!e || done || present < at) return;
    done = true;
    auto f32 = [](float f) { uint32_t u; std::memcpy(&u, &f, 4); return u; };
    struct Pat { const char* name; uint32_t a, b; bool pair; };
    const Pat pats[] = {{"f32 16/9", f32(16.f / 9.f), 0, false}, {"f32 9/16", f32(9.f / 16.f), 0, false},
                        {"u32 1920,1080", 1920, 1080, true}, {"f32 1920,1080", f32(1920.f), f32(1080.f), true},
                        {"u32 1280,720", 1280, 720, true}, {"f32 1280,720", f32(1280.f), f32(720.f), true},
                        {"u32 1080,1920", 1080, 1920, true}, {"f32 960,540", f32(960.f), f32(540.f), true}};
    struct Range { uint64_t lo, hi; const char* name; };
    std::vector<Range> ranges{{kHeapBase, kHeapBase + heap_size(), "heap"}};
    if (const LoadedModule* m = module_at(kModuleBase)) {
        const ModuleInfo* mi = m->info;
        ranges.push_back({mi->base + mi->data, mi->base + mi->bss + mi->bss_size, "main data/bss"});
    }
    for (const Range& r : ranges)
        for (const Pat& p : pats) {
            int hits = 0;
            for (uint64_t a = r.lo; a + 8 <= r.hi; a += 4) {
                const uint32_t* w = reinterpret_cast<const uint32_t*>(a);
                if (w[0] != p.a || (p.pair && w[1] != p.b)) continue;
                if (hits++ >= 40) break;
                std::string ctx;
                for (int k = -4; k < 8; k++) {
                    uint64_t q = a + 4 * k;
                    if (q < r.lo || q + 4 > r.hi) continue;
                    uint32_t u = *reinterpret_cast<const uint32_t*>(q);
                    float f;
                    std::memcpy(&f, &u, 4);
                    char b[40];
                    std::snprintf(b, sizeof b, " %s%g/%x", k == 0 ? "[" : "", double(f), u);
                    ctx += b;
                }
                log("uwscan %s %s at %llx (main+%llx):%s", r.name, p.name, (unsigned long long)a,
                    (unsigned long long)(a - kModuleBase), ctx.c_str());
            }
            log("uwscan %s %s: %d hits%s", r.name, p.name, hits, hits > 40 ? "+" : "");
        }
}

// ---- camera aspect -----------------------------------------------------------------------------------------------
// Supports both:
//   - P5R's GFD camera structs ({near, far, fov_deg, 16/9, ...}) and global display/camera aspect in main's data/bss
//   - Mario Wonder's {1000.0, 0.785398, 16/9} camera pool
// Setting the 16/9 aspect float to k * 16/9 gives a true Hor+ camera (same vertical FOV, wider horizontal FOV + frustum).
namespace {
struct CamSlot {
    uint64_t addr = 0;
    uint32_t near_bits = 0;
    uint32_t far_bits = 0;
    uint32_t patch_count = 0;
};
std::mutex g_cam_lock;
std::vector<CamSlot> g_cams;  // tracked camera aspect float addresses + header signatures
std::atomic<uint64_t> g_uw_last_present{0};

inline float bits_to_f32(uint32_t u) {
    float f;
    std::memcpy(&f, &u, 4);
    return f;
}

inline bool is_p5r_gfd_cam(const uint32_t* w) {
    // w[0] == 16/9 (0x3fe38e39), w[-3] = near, w[-2] = far, w[-1] = fov_deg
    const float near_z = bits_to_f32(w[-3]);
    const float far_z = bits_to_f32(w[-2]);
    const float fov_deg = bits_to_f32(w[-1]);
    if (!std::isfinite(near_z) || !std::isfinite(far_z) || !std::isfinite(fov_deg)) return false;
    if (near_z < 0.05f || near_z > 100.0f) return false;
    if (far_z < 200.0f || far_z > 10000000.0f || far_z <= near_z * 10.0f) return false;
    if (fov_deg < 1.0f || fov_deg > 150.0f) return false;
    // Verified P5R GFD camera header tail: w[1] == 0, w[2] <= 1, w[5] == 1.
    return w[1] == 0u && w[2] <= 1u && w[5] == 1u;
}

void cam_add_locked(uint64_t a, uint32_t near_b, uint32_t far_b) {
    if (g_cams.size() >= 8192) return;
    for (const CamSlot& s : g_cams)
        if (s.addr == a) return;
    g_cams.push_back({a, near_b, far_b, 0});
}

void cam_scan_bss() {
    const LoadedModule* m = module_at(kModuleBase);
    if (!m || !m->info) return;
    const ModuleInfo* mi = m->info;
    const uint64_t lo = mi->base + mi->data;
    const uint64_t hi = mi->base + mi->bss + mi->bss_size;
    constexpr uint32_t asp_bits = 0x3fe38e39u;
    std::vector<CamSlot> found;
    for (uint64_t a = lo + 12; a + 12 <= hi; a += 4) {
        const uint32_t* w = reinterpret_cast<const uint32_t*>(a);
        if (w[0] != asp_bits) continue;
        // Only patch true global GFD camera structs {near, far, fov, 16/9} — NEVER patch main+0x2327a8c
        // ({1280, 720, 1920, 16/9}), which P5R's area-transition code (main+104e7d0) reads as its display struct.
        const float near_z = bits_to_f32(w[-3]);
        const float far_z = bits_to_f32(w[-2]);
        const float fov_deg = bits_to_f32(w[-1]);
        const bool is_cam = std::isfinite(near_z) && std::isfinite(far_z) && std::isfinite(fov_deg) &&
                            near_z >= 0.05f && near_z <= 100.0f &&
                            far_z >= 200.0f && far_z <= 10000000.0f && far_z > near_z * 10.0f &&
                            fov_deg >= 1.0f && fov_deg <= 150.0f;
        if (is_cam) found.push_back({a, w[-3], w[-2], 0});
    }
    std::lock_guard lk(g_cam_lock);
    for (const CamSlot& s : found) cam_add_locked(s.addr, s.near_bits, s.far_bits);
}

void cam_scan() {
    constexpr uint32_t far_bits = 0x447a0000u, fov_bits = 0x3f490fdbu, asp_bits = 0x3fe38e39u;
    const uint64_t lo = (kHeapBase + 16) & ~7ull;
    const uint64_t hi = (kHeapBase + heap_size() - 24) & ~7ull;
    if (lo >= hi) return;
    std::vector<CamSlot> found;
    auto check_addr = [&](uint64_t a) {
        const uint32_t* w = reinterpret_cast<const uint32_t*>(a);
        if ((w[-2] == far_bits && w[-1] == fov_bits) || is_p5r_gfd_cam(w))
            found.push_back({a, w[-3], w[-2], 0});
    };
    for (uint64_t a = lo; a < hi; a += 8) {
        const uint64_t v = *reinterpret_cast<const uint64_t*>(a);
        if (uint32_t(v) == asp_bits) check_addr(a);
        if (uint32_t(v >> 32) == asp_bits) check_addr(a + 4);
    }
    std::lock_guard lk(g_cam_lock);
    for (const CamSlot& s : found) cam_add_locked(s.addr, s.near_bits, s.far_bits);
}
}  // namespace

void uw_cameras(uint64_t present) {
    if (!uw_on()) return;
    g_uw_last_present.store(present, std::memory_order_relaxed);
    static const bool off = std::getenv("NX_UW_CAMERA") && std::getenv("NX_UW_CAMERA")[0] == '0';
    if (off) return;
    static const bool bss_on = !(std::getenv("NX_UW_CAM_BSS") && std::getenv("NX_UW_CAM_BSS")[0] == '0');
    static const bool heap_on = !(std::getenv("NX_UW_CAM_HEAP") && std::getenv("NX_UW_CAM_HEAP")[0] == '0');
    static std::once_flag once;
    std::call_once(once, [] {
        if (bss_on) cam_scan_bss();
        if (!heap_on) return;
        std::thread([] {
            plat::set_thread_name("uw-camscan");
#if defined(__ANDROID__)
            plat::pin_current_thread_to_little_cores("uw-camscan");
#endif
            plat::set_thread_priority(10);
            int scans = 0;
            for (;;) {
                // Skip heap scanning while P5R is stalled or mid-area transition (< 4 presents in 250 ms).
                const uint64_t p0 = g_uw_last_present.load(std::memory_order_relaxed);
                plat::sleep_ns(250'000'000ll);
                const uint64_t p1 = g_uw_last_present.load(std::memory_order_relaxed);
                if (p1 - p0 < 4) {
                    plat::sleep_ns(750'000'000ll);
                    continue;
                }
                uint64_t t0 = plat::monotonic_ns();
                cam_scan();
                size_t count = 0;
                {
                    std::lock_guard lk(g_cam_lock);
                    count = g_cams.size();
                }
                if (scans < 5) {
                    log("nvn: ultrawide: camera scan %.0f ms, %zu cameras",
                        double(plat::monotonic_ns() - t0) / 1e6, count);
                }
                scans++;
                plat::sleep_ns((scans <= 3 || count == 0) ? 2'000'000'000ll : 5'000'000'000ll);
            }
        }).detach();
    });
    constexpr uint32_t far_bits = 0x447a0000u, fov_bits = 0x3f490fdbu, asp_bits = 0x3fe38e39u;
    float wide = float(16.0 / 9.0) * uw_factor();
    uint32_t wide_bits;
    std::memcpy(&wide_bits, &wide, 4);
    static std::set<uint64_t> logged;
    static int repatch_logged = 0;
    std::lock_guard lk(g_cam_lock);
    for (auto it = g_cams.begin(); it != g_cams.end();) {
        const uint64_t a = it->addr;
        auto* p = reinterpret_cast<volatile uint32_t*>(a);
        const uint32_t cur = *p;
        if (cur == asp_bits) {
            const uint32_t* w = reinterpret_cast<const uint32_t*>(a);
            const bool is_heap = a >= kHeapBase;
            const bool sig_ok = !is_heap || it->patch_count == 0 ||
                                (w[-3] == it->near_bits && w[-2] == it->far_bits);
            const bool valid = sig_ok && ((w[-2] == far_bits && w[-1] == fov_bits) ||
                                          is_p5r_gfd_cam(w) || !is_heap);
            if (valid) {
                if (logged.size() < 64 && logged.insert(a).second) {
                    log("nvn: ultrawide: patch %s %llx (main+%llx) near=%g far=%g fov=%g w1=%x w2=%x w5=%x",
                        is_heap ? "heap" : "bss", (unsigned long long)a,
                        (unsigned long long)(a - kModuleBase), double(bits_to_f32(w[-3])),
                        double(bits_to_f32(w[-2])), double(bits_to_f32(w[-1])), w[1], w[2], w[5]);
                } else if (it->patch_count > 0 && repatch_logged++ < 16) {
                    log("nvn: ultrawide: re-patch %s %llx at present %llu (near=%g far=%g fov=%g)",
                        is_heap ? "heap" : "bss", (unsigned long long)a, (unsigned long long)present,
                        double(bits_to_f32(w[-3])), double(bits_to_f32(w[-2])), double(bits_to_f32(w[-1])));
                }
                uint32_t expected = asp_bits;
                if (__atomic_compare_exchange_n(reinterpret_cast<uint32_t*>(a), &expected, wide_bits, false,
                                                __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)) {
                    it->patch_count++;
                }
                ++it;
            } else {
                it = g_cams.erase(it);
            }
        } else if (cur == wide_bits) {
            ++it;
        } else {
            // Freed/reused memory after an area transition: stop tracking it.
            it = g_cams.erase(it);
        }
    }
}

bool g_uw_wide = false;

void uw_span(int32_t& x, int32_t& w, uint32_t extent_w) {
    int64_t x0 = std::llround(double(x) * uw_factor());
    // Guard against INT32_MAX / huge scissors so uw_widen() never overflows signed int32_t when extent_w == UINT32_MAX.
    int64_t ww = (w >= 0x3fffffff) ? int64_t(0x3fffffff) : int64_t(uw_widen(uint32_t(std::max(0, w))));
    if (extent_w != UINT32_MAX) {
        if (std::llabs(int64_t(extent_w) - ww) <= 2 && x == 0) ww = extent_w;  // the whole target
        if (x0 + ww > int64_t(extent_w)) ww = std::max<int64_t>(0, int64_t(extent_w) - x0);
    }
    x = int32_t(std::clamp<int64_t>(x0, INT32_MIN, 0x3fffffff));
    w = int32_t(std::clamp<int64_t>(ww, 0, 0x3fffffff));
}

namespace {
std::mutex g_ui_lock;
std::set<const ShaderInfo*> g_ui_vs;
}  // namespace
void uw_ui_vertex_shader(const ShaderInfo* vs) {
    std::lock_guard lk(g_ui_lock);
    if (g_ui_vs.insert(vs).second) log("nvn: ultrawide: UI vertex shader %s", vs->hash.c_str());
}
bool uw_is_ui_vertex_shader(const ShaderInfo* vs) {
    std::lock_guard lk(g_ui_lock);
    return g_ui_vs.count(vs) != 0;
}

bool uw_vs_scaled(const ShaderInfo* vs) {
    static std::map<const ShaderInfo*, bool> cache;
    static const std::string off = std::getenv("NX_UW_NOSCALE") ? std::getenv("NX_UW_NOSCALE") : "";
    static const std::string only = std::getenv("NX_UW_SCALE") ? std::getenv("NX_UW_SCALE") : "";
    auto it = cache.find(vs);
    if (it != cache.end()) return it->second;
    bool scaled = false;
    for (const DescriptorBinding& b : vs->refl.bindings)
        if (b.type == DescriptorType::UniformBuffer && b.name != "support_buffer") scaled = true;
    if (!off.empty() && off.find(vs->hash) != std::string::npos) scaled = false;
    if (!only.empty()) scaled = only.find(vs->hash) != std::string::npos;
    return cache[vs] = scaled;
}


bool uw_stretch_ui() {
    static const bool on = [] {
        if (!uw_on()) return false;
        const char* e = std::getenv("NX_UW_STRETCH_UI");
        return e && e[0] == '1';
    }();
    return on;
}

// ---- gl_Position.x * spec constant (modelled on patch_clip_z, nvn_spirv_clip.cpp) -----------------------------------

namespace {
enum : uint32_t {
    OpEntryPoint = 15, OpTypeInt = 21, OpTypeFloat = 22, OpTypeVector = 23, OpTypePointer = 32, OpSpecConstant = 50,
    OpFunction = 54, OpFunctionEnd = 56, OpVariable = 59, OpLoad = 61, OpStore = 62, OpAccessChain = 65,
    OpDecorate = 71, OpMemberDecorate = 72, OpCompositeExtract = 81, OpCompositeInsert = 82, OpConstant = 43,
    OpFMul = 133, OpReturn = 253, OpStructType = 30,
    DecorationBuiltIn = 11, DecorationSpecId = 1, BuiltInPosition = 0, StorageOutput = 3, ModelVertex = 0,
};
bool is_header_op(uint32_t op) {  // before the types: capabilities .. annotations
    switch (op) {
        case 17: case 10: case 11: case 14: case 15: case 16: case 330: case 3: case 4: case 5: case 6: case 7: case 8:
        case 71: case 72: case 73: case 74: case 75: return true;
        default: return false;
    }
}
}  // namespace

bool patch_x_scale(const std::vector<uint32_t>& in, std::vector<uint32_t>& out) {
    if (in.size() < 5) return false;
    struct Inst { size_t at; uint32_t op, len; };
    std::vector<Inst> insts;
    for (size_t i = 5; i < in.size();) {
        uint32_t len = in[i] >> 16;
        if (!len || i + len > in.size()) return false;
        insts.push_back({i, in[i] & 0xffff, len});
        i += len;
    }
    uint32_t bound = in[3], entry = 0, f32 = 0, u32 = 0, v4f = 0, ptr_v4f = 0, zero = 0;
    uint32_t block = 0, member = 0, position_var = 0;
    std::map<uint32_t, uint32_t> struct_ptr;
    size_t first_function = in.size(), first_type = in.size();
    for (const Inst& n : insts) {
        const uint32_t* w = &in[n.at];
        if (first_type == in.size() && !is_header_op(n.op)) first_type = n.at;
        if (n.op == OpEntryPoint && n.len >= 3 && w[1] == ModelVertex && !entry) entry = w[2];
        else if (n.op == OpMemberDecorate && n.len >= 5 && w[3] == DecorationBuiltIn && w[4] == BuiltInPosition)
            block = w[1], member = w[2];
        else if (n.op == OpDecorate && n.len >= 4 && w[2] == DecorationBuiltIn && w[3] == BuiltInPosition)
            position_var = w[1];
        else if (n.op == OpTypeFloat && n.len == 3 && w[2] == 32) f32 = w[1];
        else if (n.op == OpTypeInt && n.len == 4 && w[2] == 32 && w[3] == 0) u32 = w[1];
        else if (n.op == OpTypeVector && n.len == 4 && w[2] == f32 && w[3] == 4) v4f = w[1];
        else if (n.op == OpTypePointer && n.len == 4 && w[2] == StorageOutput) {
            if (w[3] == v4f && v4f) ptr_v4f = w[1];
            struct_ptr[w[1]] = w[3];
        } else if (n.op == OpConstant && n.len == 4 && u32 && w[1] == u32 && w[3] == member) {
            zero = w[2];
        } else if (n.op == OpFunction && first_function == in.size()) {
            first_function = n.at;
        }
    }
    if (!entry || !f32 || !v4f || first_type == in.size()) return false;
    uint32_t var = 0;
    for (const Inst& n : insts) {
        const uint32_t* w = &in[n.at];
        if (n.op != OpVariable || n.len < 4 || w[3] != StorageOutput) continue;
        auto p = struct_ptr.find(w[1]);
        if (p == struct_ptr.end()) continue;
        if (block && p->second == block) var = w[2];
        if (!block && w[2] == position_var && p->second == v4f) var = w[2];
    }
    if (!var) return false;
    uint32_t spec = bound++;
    std::vector<uint32_t> globals;
    if (!ptr_v4f) ptr_v4f = bound++, globals.insert(globals.end(), {(4u << 16) | OpTypePointer, ptr_v4f, StorageOutput, v4f});
    if (block) {
        if (!u32) u32 = bound++, globals.insert(globals.end(), {(4u << 16) | OpTypeInt, u32, 32, 0});
        if (!zero) zero = bound++, globals.insert(globals.end(), {(4u << 16) | OpConstant, u32, zero, member});
    }
    globals.insert(globals.end(), {(4u << 16) | OpSpecConstant, f32, spec, 0x3f800000u});

    out.assign(in.begin(), in.begin() + 5);
    uint32_t function = 0;
    int returns = 0;
    for (const Inst& n : insts) {
        const uint32_t* w = &in[n.at];
        if (n.at == first_type) out.insert(out.end(), {(4u << 16) | OpDecorate, spec, DecorationSpecId, 0u});
        if (n.at == first_function) out.insert(out.end(), globals.begin(), globals.end());
        if (n.op == OpFunction) function = w[2];
        if (n.op == OpReturn && function == entry) {
            uint32_t p = var;
            if (block) {
                p = bound++;
                out.insert(out.end(), {(5u << 16) | OpAccessChain, ptr_v4f, p, var, zero});
            }
            uint32_t v = bound++, x = bound++, m = bound++, r = bound++;
            out.insert(out.end(), {(4u << 16) | OpLoad, v4f, v, p});
            out.insert(out.end(), {(5u << 16) | OpCompositeExtract, f32, x, v, 0});
            out.insert(out.end(), {(5u << 16) | OpFMul, f32, m, x, spec});
            out.insert(out.end(), {(6u << 16) | OpCompositeInsert, v4f, r, m, v, 0});
            out.insert(out.end(), {(3u << 16) | OpStore, p, r});
            returns++;
        }
        if (n.op == OpFunctionEnd) function = 0;
        out.insert(out.end(), w, w + n.len);
    }
    if (!returns) return false;
    out[3] = bound;
    return true;
}

namespace {
std::mutex g_fc_lock;
std::set<const ShaderInfo*> g_fc_fs;
std::map<const ShaderInfo*, bool> g_fc_uses_cache;
}  // namespace

bool uw_fs_uses_fragcoord(const ShaderInfo* fs) {
    static const bool off = std::getenv("NX_UW_FRAGCOORD") && std::getenv("NX_UW_FRAGCOORD")[0] == '0';
    if (off || !fs || fs->stage != ShaderStage::Fragment) return false;
    {
        std::lock_guard lk(g_fc_lock);
        if (auto it = g_fc_uses_cache.find(fs); it != g_fc_uses_cache.end()) return it->second;
    }
    constexpr uint32_t BuiltInFragCoord = 15;
    bool uses = false;
    const auto& in = fs->spirv;
    for (size_t i = 5; i < in.size();) {
        const uint32_t len = in[i] >> 16;
        const uint32_t op = in[i] & 0xffff;
        if (!len || i + len > in.size()) break;
        if (op == OpFunction) break;
        if (op == OpDecorate && len >= 4 && in[i + 2] == DecorationBuiltIn && in[i + 3] == BuiltInFragCoord) {
            uses = true;
            break;
        }
        i += len;
    }
    std::lock_guard lk(g_fc_lock);
    return g_fc_uses_cache[fs] = uses;
}

void uw_mark_fs_fragcoord(const ShaderInfo* fs) {
    if (!fs) return;
    std::lock_guard lk(g_fc_lock);
    g_fc_fs.insert(fs);
}

bool uw_fs_has_fragcoord(const ShaderInfo* fs) {
    if (!fs) return false;
    std::lock_guard lk(g_fc_lock);
    return g_fc_fs.count(fs) != 0;
}

// ---- gl_FragCoord.x * spec constant 0 (fixes 16:9 pixel-width screen-space UVs on widened targets) -----------------
bool patch_frag_coord_x(const std::vector<uint32_t>& in, std::vector<uint32_t>& out) {
    static const bool off = std::getenv("NX_UW_FRAGCOORD") && std::getenv("NX_UW_FRAGCOORD")[0] == '0';
    if (off || in.size() < 5) return false;
    constexpr uint32_t OpInBoundsAccessChain = 66, BuiltInFragCoord = 15, StorageInput = 1;
    struct Inst { size_t at; uint32_t op, len; };
    std::vector<Inst> insts;
    insts.reserve(in.size() / 4);
    for (size_t i = 5; i < in.size();) {
        uint32_t len = in[i] >> 16;
        if (!len || i + len > in.size()) return false;
        insts.push_back({i, in[i] & 0xffff, len});
        i += len;
    }
    uint32_t bound = in[3], f32 = 0, v4f = 0, fc_var = 0;
    std::set<uint32_t> int_types, zero_consts, ptr_input_v4f;
    size_t first_function = in.size(), first_type = in.size();
    for (const Inst& n : insts) {
        const uint32_t* w = &in[n.at];
        if (first_type == in.size() && !is_header_op(n.op)) first_type = n.at;
        if (n.op == OpDecorate && n.len >= 4 && w[2] == DecorationBuiltIn && w[3] == BuiltInFragCoord)
            fc_var = w[1];
        else if (n.op == OpTypeFloat && n.len == 3 && w[2] == 32) f32 = w[1];
        else if (n.op == OpTypeInt && n.len == 4 && w[2] == 32) int_types.insert(w[1]);
        else if (n.op == OpTypeVector && n.len == 4 && f32 && w[2] == f32 && w[3] == 4) v4f = w[1];
        else if (n.op == OpTypePointer && n.len == 4 && w[2] == StorageInput && v4f && w[3] == v4f)
            ptr_input_v4f.insert(w[1]);
        else if (n.op == OpConstant && n.len == 4 && int_types.count(w[1]) && w[3] == 0)
            zero_consts.insert(w[2]);
        else if (n.op == OpFunction && first_function == in.size())
            first_function = n.at;
    }
    if (!fc_var || !f32 || !v4f || first_type == in.size() || first_function == in.size()) return false;
    bool var_ok = false;
    for (const Inst& n : insts) {
        const uint32_t* w = &in[n.at];
        if (n.op == OpVariable && n.len >= 4 && w[2] == fc_var && w[3] == StorageInput && ptr_input_v4f.count(w[1])) {
            var_ok = true;
            break;
        }
    }
    if (!var_ok) return false;
    uint32_t spec = bound++;
    std::set<uint32_t> fc_x_ptrs;
    int patched_loads = 0;
    out.assign(in.begin(), in.begin() + 5);
    out.reserve(in.size() + 64);
    for (const Inst& n : insts) {
        const uint32_t* w = &in[n.at];
        if (n.at == first_type)
            out.insert(out.end(), {(4u << 16) | OpDecorate, spec, DecorationSpecId, 0u});
        if (n.at == first_function)
            out.insert(out.end(), {(4u << 16) | OpSpecConstant, f32, spec, 0x3f800000u});
        if ((n.op == OpAccessChain || n.op == OpInBoundsAccessChain) && n.len == 5 &&
            w[3] == fc_var && zero_consts.count(w[4])) {
            fc_x_ptrs.insert(w[2]);
            out.insert(out.end(), w, w + n.len);
            continue;
        }
        if (n.op == OpLoad && n.len >= 4 && w[1] == v4f && w[3] == fc_var) {
            const uint32_t res = w[2];
            const uint32_t raw = bound++, x = bound++, m = bound++;
            out.push_back(w[0]);
            out.push_back(w[1]);
            out.push_back(raw);
            out.insert(out.end(), w + 3, w + n.len);
            out.insert(out.end(), {(5u << 16) | OpCompositeExtract, f32, x, raw, 0});
            out.insert(out.end(), {(5u << 16) | OpFMul, f32, m, x, spec});
            out.insert(out.end(), {(6u << 16) | OpCompositeInsert, v4f, res, m, raw, 0});
            patched_loads++;
            continue;
        }
        if (n.op == OpLoad && n.len >= 4 && w[1] == f32 && fc_x_ptrs.count(w[3])) {
            const uint32_t res = w[2];
            const uint32_t raw = bound++;
            out.push_back(w[0]);
            out.push_back(w[1]);
            out.push_back(raw);
            out.insert(out.end(), w + 3, w + n.len);
            out.insert(out.end(), {(5u << 16) | OpFMul, f32, res, raw, spec});
            patched_loads++;
            continue;
        }
        out.insert(out.end(), w, w + n.len);
    }
    if (!patched_loads) return false;
    out[3] = bound;
    return true;
}

}  // namespace nx::nvn
