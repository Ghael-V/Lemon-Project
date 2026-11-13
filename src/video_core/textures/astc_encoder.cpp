// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Fast ASTC 4x4 LDR encoder, used to recompress BCn textures on GPUs that can't sample BCn.
//
// Every block is single-partition with a 4x4 weight grid. A handful of block layouts is tried and
// the one with the lowest error is kept:
//   opaque blocks (CEM 8, RGB):        4, 8, 16 or 32 weight levels
//   blocks with alpha (CEM 12, RGBA):  shared weights with 4 or 8 levels, or alpha on its own
//                                      plane with 2 or 4 levels
// Weights always use plain binary ranges; the endpoint range is whatever the decoder derives from
// the bits left over, which for some layouts is trit-based (48 or 192 levels).
// Endpoints come from the principal axis of the block's colours, refined once by least squares.
// The approach follows Lee Gao's rt-astcenc-glsl (MIT): a single-partition encoder like this is
// fast enough for real-time recompression on mobile GPUs.

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>

#include "video_core/textures/astc_encoder.h"
#include "video_core/textures/workers.h"

namespace Tegra::Texture::ASTC {
namespace {

template <std::size_t N>
using Color = std::array<float, N>;

template <std::size_t N>
using Texels = std::array<Color<N>, 16>;

/// An integer sequence encoding range: values are trit * 2^bits + m, or just m when !trit.
struct EndpointRange {
    u32 bits;
    bool trit;

    u32 Count() const {
        return trit ? 3u << bits : 1u << bits;
    }
};

constexpr EndpointRange RANGE_32{5, false};
constexpr EndpointRange RANGE_48{4, true};
constexpr EndpointRange RANGE_192{6, true};
constexpr EndpointRange RANGE_256{8, false};

struct Layout {
    u32 block_mode;
    u32 weight_bits; ///< per weight; levels = 1 << weight_bits
    bool dual_plane;
    EndpointRange endpoints;
};

// Block modes for a 4x4 weight grid: range bits R in [1:0] and [4], A = 2 in [6:5], H in [9],
// dual plane in [10]. The endpoint ranges are what the decoder derives from the leftover bits.
constexpr Layout RGB_W4{0x042, 2, false, RANGE_256};
constexpr Layout RGB_W8{0x053, 3, false, RANGE_256};
constexpr Layout RGB_W16{0x242, 4, false, RANGE_192};
constexpr Layout RGB_W32{0x253, 5, false, RANGE_32};
constexpr Layout RGBA_W4{0x042, 2, false, RANGE_256};
constexpr Layout RGBA_W8{0x053, 3, false, RANGE_192};
constexpr Layout DUAL_W2{0x441, 1, true, RANGE_256};
constexpr Layout DUAL_W4{0x442, 2, true, RANGE_48};

constexpr u32 CEM_RGB_DIRECT = 8;
constexpr u32 CEM_RGBA_DIRECT = 12;

/// Unquantized weight (0..64) of a plain binary weight value.
u32 UnquantizeWeight(u32 value, u32 bits) {
    u32 result = 0;
    for (u32 filled = 0; filled < 6; filled += bits) {
        result = (result << bits) | value;
    }
    result >>= (bits * ((6 + bits - 1) / bits)) - 6;
    return result > 32 ? result + 1 : result;
}

/// Unquantized endpoint (0..255) of an encoded value, as in ASTC spec C.2.13.
u32 UnquantizeEndpoint(u32 trit, u32 m, const EndpointRange& range) {
    if (!range.trit) {
        u32 result = 0;
        for (u32 filled = 0; filled < 8; filled += range.bits) {
            result = (result << range.bits) | m;
        }
        return result >> ((range.bits * ((8 + range.bits - 1) / range.bits)) - 8);
    }
    const u32 a = (m & 1) ? 0x1FF : 0;
    u32 b = 0;
    u32 c = 0;
    if (range.bits == 4) {
        const u32 dcb = (m >> 1) & 7;
        b = (dcb << 6) | dcb;
        c = 22;
    } else { // 6 bits
        const u32 fedcb = (m >> 1) & 0x1F;
        b = (fedcb << 4) | (fedcb >> 4);
        c = 5;
    }
    u32 t = trit * c + b;
    t ^= a;
    return (a & 0x80) | (t >> 2);
}

/// For every target value 0..255, the encoded (trit, m) whose unquantized value is closest.
struct EndpointTable {
    std::array<u8, 256> trit;
    std::array<u8, 256> m;
    std::array<u8, 256> value;
};

EndpointTable MakeEndpointTable(const EndpointRange& range) {
    EndpointTable table{};
    for (u32 target = 0; target < 256; ++target) {
        u32 best_distance = std::numeric_limits<u32>::max();
        for (u32 trit = 0; trit < (range.trit ? 3u : 1u); ++trit) {
            for (u32 m = 0; m < (1u << range.bits); ++m) {
                const u32 value = UnquantizeEndpoint(trit, m, range);
                const u32 distance = value > target ? value - target : target - value;
                if (distance < best_distance) {
                    best_distance = distance;
                    table.trit[target] = static_cast<u8>(trit);
                    table.m[target] = static_cast<u8>(m);
                    table.value[target] = static_cast<u8>(value);
                }
            }
        }
    }
    return table;
}

const EndpointTable& TableFor(const EndpointRange& range) {
    static const EndpointTable table_32 = MakeEndpointTable(RANGE_32);
    static const EndpointTable table_48 = MakeEndpointTable(RANGE_48);
    static const EndpointTable table_192 = MakeEndpointTable(RANGE_192);
    static const EndpointTable table_256 = MakeEndpointTable(RANGE_256);
    if (range.trit) {
        return range.bits == 4 ? table_48 : table_192;
    }
    return range.bits == 5 ? table_32 : table_256;
}

/// Trits of one 8-bit trit block value T, as in ASTC spec C.2.12.
std::array<u32, 5> DecodeTrits(u32 t) {
    const auto bit = [](u32 v, u32 i) { return (v >> i) & 1; };
    const auto bits = [](u32 v, u32 lo, u32 hi) { return (v >> lo) & ((1u << (hi - lo + 1)) - 1); };
    std::array<u32, 5> trits{};
    u32 c = 0;
    if (bits(t, 2, 4) == 7) {
        c = (bits(t, 5, 7) << 2) | bits(t, 0, 1);
        trits[4] = trits[3] = 2;
    } else {
        c = bits(t, 0, 4);
        if (bits(t, 5, 6) == 3) {
            trits[4] = 2;
            trits[3] = bit(t, 7);
        } else {
            trits[4] = bit(t, 7);
            trits[3] = bits(t, 5, 6);
        }
    }
    if (bits(c, 0, 1) == 3) {
        trits[2] = 2;
        trits[1] = bit(c, 4);
        trits[0] = (bit(c, 3) << 1) | (bit(c, 2) & (bit(c, 3) ^ 1));
    } else if (bits(c, 2, 3) == 3) {
        trits[2] = 2;
        trits[1] = 2;
        trits[0] = bits(c, 0, 1);
    } else {
        trits[2] = bit(c, 4);
        trits[1] = bits(c, 2, 3);
        trits[0] = (bit(c, 1) << 1) | (bit(c, 0) & (bit(c, 1) ^ 1));
    }
    return trits;
}

/// T values for blocks of 1..5 trits; bits a shorter block doesn't store are kept zero.
struct TritTable {
    std::array<std::array<u8, 243>, 5> t;
};

const TritTable& Trits() {
    static const TritTable table = [] {
        // Bits of T stored after each value: 2, 2, 1, 2, 1.
        constexpr std::array<u32, 5> STORED_MASK{0x03, 0x0F, 0x1F, 0x7F, 0xFF};
        TritTable result{};
        for (u32 count = 1; count <= 5; ++count) {
            std::array<bool, 243> found{};
            // A partial block whose T has bits [4:2] all set would make the first trits depend
            // on bits it doesn't store, so those are only a last resort.
            for (u32 pass = 0; pass < 2; ++pass)
            for (u32 t = 0; t < 256; ++t) {
                if ((t & ~STORED_MASK[count - 1]) != 0) {
                    continue;
                }
                if (pass == 0 && count < 5 && ((t >> 2) & 7) == 7) {
                    continue;
                }
                const auto trits = DecodeTrits(t);
                u32 index = 0;
                u32 scale = 1;
                for (u32 i = 0; i < 5; ++i) {
                    index += (i < count ? trits[i] : 0) * scale;
                    scale *= 3;
                }
                if (!found[index]) {
                    found[index] = true;
                    result.t[count - 1][index] = static_cast<u8>(t);
                }
            }
        }
        return result;
    }();
    return table;
}

class BlockWriter {
public:
    explicit BlockWriter(std::span<u8, 16> block_) : block{block_} {
        std::memset(block.data(), 0, block.size());
    }

    void Put(u32 position, u32 value, u32 bits) {
        for (u32 i = 0; i < bits; ++i) {
            if ((value >> i) & 1) {
                SetBit(position + i);
            }
        }
    }

    /// Weights are stored from the top of the block downwards, bit-reversed.
    void PutReversed(u32 stream_position, u32 value, u32 bits) {
        for (u32 i = 0; i < bits; ++i) {
            if ((value >> i) & 1) {
                SetBit(127 - (stream_position + i));
            }
        }
    }

private:
    void SetBit(u32 bit) {
        block[bit / 8] = static_cast<u8>(block[bit / 8] | (1u << (bit % 8)));
    }

    std::span<u8, 16> block;
};

/// Writes endpoint values with the integer sequence encoding of the range, from bit 17.
void WriteEndpointSequence(BlockWriter& writer, const std::array<u8, 8>& values, u32 count,
                           const EndpointRange& range) {
    const EndpointTable& table = TableFor(range);
    u32 position = 17;
    if (!range.trit) {
        for (u32 i = 0; i < count; ++i) {
            writer.Put(position, table.m[values[i]], range.bits);
            position += range.bits;
        }
        return;
    }
    // Trit blocks of five: m0 T[1:0] m1 T[3:2] m2 T[4] m3 T[6:5] m4 T[7].
    constexpr std::array<u32, 5> T_SHIFT{0, 2, 4, 5, 7};
    constexpr std::array<u32, 5> T_BITS{2, 2, 1, 2, 1};
    for (u32 first = 0; first < count; first += 5) {
        const u32 in_block = std::min(5u, count - first);
        u32 index = 0;
        u32 scale = 1;
        for (u32 i = 0; i < in_block; ++i) {
            index += table.trit[values[first + i]] * scale;
            scale *= 3;
        }
        const u32 t = Trits().t[in_block - 1][index];
        for (u32 i = 0; i < in_block; ++i) {
            writer.Put(position, table.m[values[first + i]], range.bits);
            position += range.bits;
            writer.Put(position, (t >> T_SHIFT[i]) & ((1u << T_BITS[i]) - 1), T_BITS[i]);
            position += T_BITS[i];
        }
    }
}

template <std::size_t N>
float Dot(const Color<N>& a, const Color<N>& b) {
    float sum = 0.0f;
    for (std::size_t i = 0; i < N; ++i) {
        sum += a[i] * b[i];
    }
    return sum;
}

template <std::size_t N>
float DistanceSquared(const Color<N>& a, const Color<N>& b) {
    float sum = 0.0f;
    for (std::size_t i = 0; i < N; ++i) {
        const float d = a[i] - b[i];
        sum += d * d;
    }
    return sum;
}

/// Snaps endpoints to the nearest values the range can represent.
template <std::size_t N>
Color<N> Quantize(const Color<N>& c, const EndpointRange& range) {
    const EndpointTable& table = TableFor(range);
    Color<N> result;
    for (std::size_t i = 0; i < N; ++i) {
        const u32 target = static_cast<u32>(std::clamp(std::round(c[i]), 0.0f, 255.0f));
        result[i] = static_cast<float>(table.value[target]);
    }
    return result;
}

/// Mean and principal axis of the texels (power iteration on the covariance).
template <std::size_t N>
void PrincipalAxis(const Texels<N>& texels, Color<N>& mean, Color<N>& axis) {
    mean = {};
    for (const auto& t : texels) {
        for (std::size_t i = 0; i < N; ++i) {
            mean[i] += t[i];
        }
    }
    for (auto& m : mean) {
        m /= 16.0f;
    }
    std::array<Color<N>, N> covariance{};
    for (const auto& t : texels) {
        for (std::size_t i = 0; i < N; ++i) {
            for (std::size_t j = 0; j < N; ++j) {
                covariance[i][j] += (t[i] - mean[i]) * (t[j] - mean[j]);
            }
        }
    }
    for (std::size_t i = 0; i < N; ++i) {
        float lo = 255.0f;
        float hi = 0.0f;
        for (const auto& t : texels) {
            lo = std::min(lo, t[i]);
            hi = std::max(hi, t[i]);
        }
        axis[i] = hi - lo + 1e-3f;
    }
    for (int iteration = 0; iteration < 8; ++iteration) {
        Color<N> next{};
        for (std::size_t i = 0; i < N; ++i) {
            for (std::size_t j = 0; j < N; ++j) {
                next[i] += covariance[i][j] * axis[j];
            }
        }
        const float length = std::sqrt(Dot(next, next));
        if (length < 1e-6f) {
            break;
        }
        for (std::size_t i = 0; i < N; ++i) {
            axis[i] = next[i] / length;
        }
    }
    const float length = std::sqrt(Dot(axis, axis));
    for (auto& a : axis) {
        a /= length;
    }
}

template <std::size_t N>
void EndpointsOnAxis(const Texels<N>& texels, const Color<N>& mean, const Color<N>& axis,
                     Color<N>& e0, Color<N>& e1) {
    float t_min = 0.0f;
    float t_max = 0.0f;
    for (const auto& t : texels) {
        Color<N> offset;
        for (std::size_t i = 0; i < N; ++i) {
            offset[i] = t[i] - mean[i];
        }
        const float projection = Dot(offset, axis);
        t_min = std::min(t_min, projection);
        t_max = std::max(t_max, projection);
    }
    for (std::size_t i = 0; i < N; ++i) {
        e0[i] = mean[i] + axis[i] * t_min;
        e1[i] = mean[i] + axis[i] * t_max;
    }
}

/// Picks the closest weight for every texel; returns the squared error. The weight is guessed
/// from the projection on the line and only its neighbours are compared.
template <std::size_t N>
float PickWeights(const Texels<N>& texels, const Color<N>& e0, const Color<N>& e1, u32 weight_bits,
                  std::array<u32, 16>& weights) {
    const u32 top = (1u << weight_bits) - 1;
    Color<N> d;
    for (std::size_t c = 0; c < N; ++c) {
        d[c] = e1[c] - e0[c];
    }
    const float dd = Dot(d, d);
    float error = 0.0f;
    for (std::size_t i = 0; i < 16; ++i) {
        Color<N> offset;
        for (std::size_t c = 0; c < N; ++c) {
            offset[c] = texels[i][c] - e0[c];
        }
        const float t = dd > 0.0f ? std::clamp(Dot(offset, d) / dd, 0.0f, 1.0f) : 0.0f;
        const u32 guess = static_cast<u32>(std::round(t * static_cast<float>(top)));
        float best = std::numeric_limits<float>::max();
        u32 best_q = guess;
        for (u32 q = guess > 0 ? guess - 1 : 0; q <= std::min(guess + 1, top); ++q) {
            const float w = static_cast<float>(UnquantizeWeight(q, weight_bits)) / 64.0f;
            Color<N> decoded;
            for (std::size_t c = 0; c < N; ++c) {
                decoded[c] = e0[c] + (e1[c] - e0[c]) * w;
            }
            const float dist = DistanceSquared(texels[i], decoded);
            if (dist < best) {
                best = dist;
                best_q = q;
            }
        }
        weights[i] = best_q;
        error += best;
    }
    return error;
}

/// Least-squares endpoints for fixed weights; returns false when they can't be solved.
template <std::size_t N>
bool RefineEndpoints(const Texels<N>& texels, const std::array<u32, 16>& weights, u32 weight_bits,
                     Color<N>& e0, Color<N>& e1) {
    float aa = 0.0f;
    float ab = 0.0f;
    float bb = 0.0f;
    Color<N> xa{};
    Color<N> xb{};
    for (std::size_t i = 0; i < 16; ++i) {
        const float t = static_cast<float>(UnquantizeWeight(weights[i], weight_bits)) / 64.0f;
        const float s = 1.0f - t;
        aa += s * s;
        ab += s * t;
        bb += t * t;
        for (std::size_t c = 0; c < N; ++c) {
            xa[c] += s * texels[i][c];
            xb[c] += t * texels[i][c];
        }
    }
    const float determinant = aa * bb - ab * ab;
    if (std::abs(determinant) < 1e-6f) {
        return false;
    }
    for (std::size_t c = 0; c < N; ++c) {
        e0[c] = (bb * xa[c] - ab * xb[c]) / determinant;
        e1[c] = (aa * xb[c] - ab * xa[c]) / determinant;
    }
    return true;
}

template <std::size_t N>
struct LineFit {
    Color<N> e0;
    Color<N> e1;
    std::array<u32, 16> weights;
    float error;
};

/// Endpoints (quantized to the range) and weights for one line through the texels.
template <std::size_t N>
LineFit<N> FitLine(const Texels<N>& texels, const Color<N>& mean, const Color<N>& axis,
                   u32 weight_bits, const EndpointRange& range) {
    LineFit<N> fit;
    EndpointsOnAxis(texels, mean, axis, fit.e0, fit.e1);
    fit.e0 = Quantize(fit.e0, range);
    fit.e1 = Quantize(fit.e1, range);
    fit.error = PickWeights(texels, fit.e0, fit.e1, weight_bits, fit.weights);
    LineFit<N> refined;
    refined.e0 = fit.e0;
    refined.e1 = fit.e1;
    if (RefineEndpoints(texels, fit.weights, weight_bits, refined.e0, refined.e1)) {
        refined.e0 = Quantize(refined.e0, range);
        refined.e1 = Quantize(refined.e1, range);
        refined.error = PickWeights(texels, refined.e0, refined.e1, weight_bits, refined.weights);
        if (refined.error < fit.error) {
            return refined;
        }
    }
    return fit;
}

struct Candidate {
    const Layout* layout;
    u32 cem;
    std::array<float, 4> e0;
    std::array<float, 4> e1;
    std::array<u32, 16> weights;       ///< colour (or all channels) plane
    std::array<u32, 16> alpha_weights; ///< second plane, dual-plane layouts only
    float error;
};

void Write(const Candidate& c, std::span<u8, 16> block) {
    const Layout& layout = *c.layout;
    std::array<float, 4> e0 = c.e0;
    std::array<float, 4> e1 = c.e1;
    std::array<u32, 16> weights = c.weights;
    std::array<u32, 16> alpha_weights = c.alpha_weights;
    // The decoder swaps (and blue-contracts) endpoints whose second colour sums lower than the
    // first, so store them the other way round and mirror the weights instead.
    if (e1[0] + e1[1] + e1[2] < e0[0] + e0[1] + e0[2]) {
        std::swap(e0, e1);
        const u32 top = (1u << layout.weight_bits) - 1;
        for (u32 i = 0; i < 16; ++i) {
            weights[i] = top - weights[i];
            alpha_weights[i] = top - alpha_weights[i];
        }
    }
    const u32 channels = c.cem == CEM_RGBA_DIRECT ? 4 : 3;
    std::array<u8, 8> values{};
    for (u32 ch = 0; ch < channels; ++ch) {
        values[ch * 2] = static_cast<u8>(e0[ch]);
        values[ch * 2 + 1] = static_cast<u8>(e1[ch]);
    }
    BlockWriter writer{block};
    writer.Put(0, layout.block_mode, 11);
    writer.Put(11, 0, 2); // one partition
    writer.Put(13, c.cem, 4);
    WriteEndpointSequence(writer, values, channels * 2, layout.endpoints);
    const u32 planes = layout.dual_plane ? 2 : 1;
    const u32 weight_stream_bits = 16 * planes * layout.weight_bits;
    if (layout.dual_plane) {
        // Colour component selector (3 = alpha on the second plane), right below the weights.
        writer.Put(128 - weight_stream_bits - 2, 3, 2);
    }
    u32 position = 0;
    for (u32 i = 0; i < 16; ++i) {
        writer.PutReversed(position, weights[i], layout.weight_bits);
        position += layout.weight_bits;
        if (layout.dual_plane) {
            writer.PutReversed(position, alpha_weights[i], layout.weight_bits);
            position += layout.weight_bits;
        }
    }
}

Candidate BestOpaque(const Texels<4>& texels) {
    Texels<3> rgb;
    for (std::size_t i = 0; i < 16; ++i) {
        rgb[i] = {texels[i][0], texels[i][1], texels[i][2]};
    }
    Color<3> mean;
    Color<3> axis;
    PrincipalAxis(rgb, mean, axis);
    Candidate best{};
    best.error = std::numeric_limits<float>::max();
    for (const Layout* layout : {&RGB_W4, &RGB_W8, &RGB_W16, &RGB_W32}) {
        const LineFit<3> fit = FitLine(rgb, mean, axis, layout->weight_bits, layout->endpoints);
        if (fit.error < best.error) {
            best = {layout, CEM_RGB_DIRECT, {fit.e0[0], fit.e0[1], fit.e0[2], 255.0f},
                    {fit.e1[0], fit.e1[1], fit.e1[2], 255.0f}, fit.weights, {}, fit.error};
        }
    }
    return best;
}

Candidate BestTranslucent(const Texels<4>& texels) {
    Candidate best{};
    best.error = std::numeric_limits<float>::max();
    // Shared weights for colour and alpha.
    Color<4> mean;
    Color<4> axis;
    PrincipalAxis(texels, mean, axis);
    for (const Layout* layout : {&RGBA_W4, &RGBA_W8}) {
        const LineFit<4> fit = FitLine(texels, mean, axis, layout->weight_bits, layout->endpoints);
        if (fit.error < best.error) {
            best = {layout, CEM_RGBA_DIRECT, fit.e0, fit.e1, fit.weights, {}, fit.error};
        }
    }
    // Alpha on its own plane.
    Texels<3> rgb;
    Texels<1> alpha;
    for (std::size_t i = 0; i < 16; ++i) {
        rgb[i] = {texels[i][0], texels[i][1], texels[i][2]};
        alpha[i] = {texels[i][3]};
    }
    Color<3> rgb_mean;
    Color<3> rgb_axis;
    PrincipalAxis(rgb, rgb_mean, rgb_axis);
    Color<1> alpha_mean;
    Color<1> alpha_axis;
    PrincipalAxis(alpha, alpha_mean, alpha_axis);
    for (const Layout* layout : {&DUAL_W2, &DUAL_W4}) {
        const LineFit<3> color =
            FitLine(rgb, rgb_mean, rgb_axis, layout->weight_bits, layout->endpoints);
        const LineFit<1> a =
            FitLine(alpha, alpha_mean, alpha_axis, layout->weight_bits, layout->endpoints);
        const float error = color.error + a.error;
        if (error < best.error) {
            best = {layout,
                    CEM_RGBA_DIRECT,
                    {color.e0[0], color.e0[1], color.e0[2], a.e0[0]},
                    {color.e1[0], color.e1[1], color.e1[2], a.e1[0]},
                    color.weights,
                    a.weights,
                    error};
        }
    }
    return best;
}

} // Anonymous namespace

void EncodeBlock4x4(const std::array<std::array<u8, 4>, 16>& texels, std::span<u8, 16> block) {
    Texels<4> colors;
    bool opaque = true;
    for (std::size_t i = 0; i < 16; ++i) {
        for (std::size_t c = 0; c < 4; ++c) {
            colors[i][c] = static_cast<float>(texels[i][c]);
        }
        opaque &= texels[i][3] == 255;
    }
    Write(opaque ? BestOpaque(colors) : BestTranslucent(colors), block);
}

void Encode4x4(std::span<const u8> rgba, u32 width, u32 height, u32 depth, std::span<u8> output) {
    const u32 blocks_x = (width + 3) / 4;
    const u32 blocks_y = (height + 3) / 4;
    Common::ThreadWorker& workers{GetThreadWorkers()};
    for (u32 z = 0; z < depth; ++z) {
        const std::size_t slice = static_cast<std::size_t>(z) * width * height * 4;
        for (u32 by = 0; by < blocks_y; ++by) {
            workers.QueueWork([rgba, width, height, output, blocks_x, blocks_y, slice, z, by] {
                std::array<std::array<u8, 4>, 16> texels;
                for (u32 bx = 0; bx < blocks_x; ++bx) {
                    for (u32 y = 0; y < 4; ++y) {
                        const u32 sy = std::min(by * 4 + y, height - 1);
                        for (u32 x = 0; x < 4; ++x) {
                            const u32 sx = std::min(bx * 4 + x, width - 1);
                            std::memcpy(texels[y * 4 + x].data(),
                                        rgba.data() + slice +
                                            (static_cast<std::size_t>(sy) * width + sx) * 4,
                                        4);
                        }
                    }
                    const std::size_t block_index =
                        (static_cast<std::size_t>(z) * blocks_y + by) * blocks_x + bx;
                    EncodeBlock4x4(texels, output.subspan(block_index * 16).first<16>());
                }
            });
        }
    }
    workers.WaitForRequests();
}

std::vector<u32> MakeGpuTables() {
    std::vector<u32> tables;
    tables.reserve(1024 + 5 * 243);
    for (const EndpointRange& range : {RANGE_32, RANGE_48, RANGE_192, RANGE_256}) {
        const EndpointTable& table = TableFor(range);
        for (u32 target = 0; target < 256; ++target) {
            tables.push_back(table.value[target] | (u32{table.m[target]} << 8) |
                             (u32{table.trit[target]} << 16));
        }
    }
    for (const auto& by_count : Trits().t) {
        for (const u8 t : by_count) {
            tables.push_back(t);
        }
    }
    return tables;
}

} // namespace Tegra::Texture::ASTC
