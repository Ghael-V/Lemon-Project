// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <algorithm>
#include <array>

#include <fmt/format.h>

#include "common/polyfill_ranges.h"
#include "shader_recompiler/frontend/ir/type.h"

namespace Shader::IR {

enum class Opcode {
#define OPCODE(name, ...) name,
#include "opcodes.inc"
#undef OPCODE
};

namespace Detail {
struct OpcodeMeta {
    std::string_view name;
    Type type;
    std::array<Type, 5> arg_types;
};

// using enum Type;
constexpr Type Void{Type::Void};
constexpr Type Opaque{Type::Opaque};
constexpr Type Reg{Type::Reg};
constexpr Type Pred{Type::Pred};
constexpr Type Attribute{Type::Attribute};
constexpr Type Patch{Type::Patch};
constexpr Type U1{Type::U1};
constexpr Type U8{Type::U8};
constexpr Type U16{Type::U16};
constexpr Type U32{Type::U32};
constexpr Type U64{Type::U64};
constexpr Type F16{Type::F16};
constexpr Type F32{Type::F32};
constexpr Type F64{Type::F64};
constexpr Type U32x2{Type::U32x2};
constexpr Type U32x3{Type::U32x3};
constexpr Type U32x4{Type::U32x4};
constexpr Type F16x2{Type::F16x2};
constexpr Type F16x3{Type::F16x3};
constexpr Type F16x4{Type::F16x4};
constexpr Type F32x2{Type::F32x2};
constexpr Type F32x3{Type::F32x3};
constexpr Type F32x4{Type::F32x4};
constexpr Type F64x2{Type::F64x2};
constexpr Type F64x3{Type::F64x3};
constexpr Type F64x4{Type::F64x4};

constexpr OpcodeMeta META_TABLE[]{
#define OPCODE(name_token, type_token, ...)                                                        \
    {                                                                                              \
        .name{#name_token},                                                                        \
        .type = type_token,                                                                        \
        .arg_types{__VA_ARGS__},                                                                   \
    },
#include "opcodes.inc"
#undef OPCODE
};
constexpr size_t CalculateNumArgsOf(Opcode op) {
    const auto& arg_types{META_TABLE[static_cast<size_t>(op)].arg_types};
    return static_cast<size_t>(
        std::distance(arg_types.begin(), std::ranges::find(arg_types, Type::Void)));
}

constexpr u8 NUM_ARGS[]{
#define OPCODE(name_token, type_token, ...) static_cast<u8>(CalculateNumArgsOf(Opcode::name_token)),
#include "opcodes.inc"
#undef OPCODE
};
} // namespace Detail

/// Get return type of an opcode
[[nodiscard]] inline Type TypeOf(Opcode op) noexcept {
    return Detail::META_TABLE[static_cast<size_t>(op)].type;
}

/// Get the number of arguments an opcode accepts
[[nodiscard]] inline size_t NumArgsOf(Opcode op) noexcept {
    return static_cast<size_t>(Detail::NUM_ARGS[static_cast<size_t>(op)]);
}

/// Get the required type of an argument of an opcode
[[nodiscard]] inline Type ArgTypeOf(Opcode op, size_t arg_index) noexcept {
    return Detail::META_TABLE[static_cast<size_t>(op)].arg_types[arg_index];
}

/// Get the name of an opcode
[[nodiscard]] std::string_view NameOf(Opcode op);

/// Memory writes and atomics, which happen once per invocation (geometry shaders on Adreno run
/// once per output vertex, see Profile::gs_replicates_side_effects).
[[nodiscard]] inline bool IsGeometrySideEffect(Opcode op) noexcept {
    switch (op) {
    case Opcode::WriteGlobalU8:
    case Opcode::WriteGlobalS8:
    case Opcode::WriteGlobalU16:
    case Opcode::WriteGlobalS16:
    case Opcode::WriteGlobal32:
    case Opcode::WriteGlobal64:
    case Opcode::WriteGlobal128:
    case Opcode::WriteStorageU8:
    case Opcode::WriteStorageS8:
    case Opcode::WriteStorageU16:
    case Opcode::WriteStorageS16:
    case Opcode::WriteStorage32:
    case Opcode::WriteStorage64:
    case Opcode::WriteStorage128:
    case Opcode::GlobalAtomicIAdd32:
    case Opcode::GlobalAtomicSMin32:
    case Opcode::GlobalAtomicUMin32:
    case Opcode::GlobalAtomicSMax32:
    case Opcode::GlobalAtomicUMax32:
    case Opcode::GlobalAtomicInc32:
    case Opcode::GlobalAtomicDec32:
    case Opcode::GlobalAtomicAnd32:
    case Opcode::GlobalAtomicOr32:
    case Opcode::GlobalAtomicXor32:
    case Opcode::GlobalAtomicExchange32:
    case Opcode::GlobalAtomicIAdd64:
    case Opcode::GlobalAtomicSMin64:
    case Opcode::GlobalAtomicUMin64:
    case Opcode::GlobalAtomicSMax64:
    case Opcode::GlobalAtomicUMax64:
    case Opcode::GlobalAtomicAnd64:
    case Opcode::GlobalAtomicOr64:
    case Opcode::GlobalAtomicXor64:
    case Opcode::GlobalAtomicExchange64:
    case Opcode::GlobalAtomicIAdd32x2:
    case Opcode::GlobalAtomicSMin32x2:
    case Opcode::GlobalAtomicUMin32x2:
    case Opcode::GlobalAtomicSMax32x2:
    case Opcode::GlobalAtomicUMax32x2:
    case Opcode::GlobalAtomicAnd32x2:
    case Opcode::GlobalAtomicOr32x2:
    case Opcode::GlobalAtomicXor32x2:
    case Opcode::GlobalAtomicExchange32x2:
    case Opcode::GlobalAtomicAddF32:
    case Opcode::GlobalAtomicAddF16x2:
    case Opcode::GlobalAtomicAddF32x2:
    case Opcode::GlobalAtomicMinF16x2:
    case Opcode::GlobalAtomicMinF32x2:
    case Opcode::GlobalAtomicMaxF16x2:
    case Opcode::GlobalAtomicMaxF32x2:
    case Opcode::StorageAtomicIAdd32:
    case Opcode::StorageAtomicSMin32:
    case Opcode::StorageAtomicUMin32:
    case Opcode::StorageAtomicSMax32:
    case Opcode::StorageAtomicUMax32:
    case Opcode::StorageAtomicInc32:
    case Opcode::StorageAtomicDec32:
    case Opcode::StorageAtomicAnd32:
    case Opcode::StorageAtomicOr32:
    case Opcode::StorageAtomicXor32:
    case Opcode::StorageAtomicExchange32:
    case Opcode::StorageAtomicIAdd64:
    case Opcode::StorageAtomicSMin64:
    case Opcode::StorageAtomicUMin64:
    case Opcode::StorageAtomicSMax64:
    case Opcode::StorageAtomicUMax64:
    case Opcode::StorageAtomicAnd64:
    case Opcode::StorageAtomicOr64:
    case Opcode::StorageAtomicXor64:
    case Opcode::StorageAtomicExchange64:
    case Opcode::StorageAtomicIAdd32x2:
    case Opcode::StorageAtomicSMin32x2:
    case Opcode::StorageAtomicUMin32x2:
    case Opcode::StorageAtomicSMax32x2:
    case Opcode::StorageAtomicUMax32x2:
    case Opcode::StorageAtomicAnd32x2:
    case Opcode::StorageAtomicOr32x2:
    case Opcode::StorageAtomicXor32x2:
    case Opcode::StorageAtomicExchange32x2:
    case Opcode::StorageAtomicAddF32:
    case Opcode::StorageAtomicAddF16x2:
    case Opcode::StorageAtomicAddF32x2:
    case Opcode::StorageAtomicMinF16x2:
    case Opcode::StorageAtomicMinF32x2:
    case Opcode::StorageAtomicMaxF16x2:
    case Opcode::StorageAtomicMaxF32x2:
    case Opcode::ImageWrite:
    case Opcode::ImageAtomicIAdd32:
    case Opcode::ImageAtomicSMin32:
    case Opcode::ImageAtomicUMin32:
    case Opcode::ImageAtomicSMax32:
    case Opcode::ImageAtomicUMax32:
    case Opcode::ImageAtomicInc32:
    case Opcode::ImageAtomicDec32:
    case Opcode::ImageAtomicAnd32:
    case Opcode::ImageAtomicOr32:
    case Opcode::ImageAtomicXor32:
    case Opcode::ImageAtomicExchange32:
        return true;
    default:
        return false;
    }
}

} // namespace Shader::IR

template <>
struct fmt::formatter<Shader::IR::Opcode> {
    constexpr auto parse(format_parse_context& ctx) {
        return ctx.begin();
    }
    template <typename FormatContext>
    auto format(const Shader::IR::Opcode& op, FormatContext& ctx) {
        return fmt::format_to(ctx.out(), "{}", Shader::IR::NameOf(op));
    }
};
