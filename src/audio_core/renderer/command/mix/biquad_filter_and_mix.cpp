// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <vector>

#include "audio_core/adsp/apps/audio_renderer/command_list_processor.h"
#include "audio_core/renderer/command/effect/biquad_filter.h"
#include "audio_core/renderer/command/mix/biquad_filter_and_mix.h"
#include "audio_core/renderer/command/mix/mix_ramp.h"
#include "audio_core/renderer/voice/voice_state.h"
#include "common/logging/log.h"

namespace AudioCore::Renderer {

void BiquadFilterAndMixCommand::Dump(
    [[maybe_unused]] const AudioRenderer::CommandListProcessor& processor, std::string& string) {
    string += fmt::format("BiquadFilterAndMixCommand\n\tinput {:02X} output {:02X} filters {} "
                          "needs_init {} first {} prev_volume {:.8f} volume {:.8f}\n",
                          input, output, filter_count, needs_init, is_first_mix_buffer,
                          prev_volume, volume);
}

void BiquadFilterAndMixCommand::Process(const AudioRenderer::CommandListProcessor& processor) {
    auto prev_sample_ptr{reinterpret_cast<s32*>(previous_sample)};
    const auto ramp{(volume - prev_volume) / static_cast<f32>(processor.sample_count)};

    for (u32 i = 0; i < filter_count; i++) {
        auto& state{*reinterpret_cast<VoiceState::BiquadFilterState*>(states[i])};
        auto& prev_state{*reinterpret_cast<VoiceState::BiquadFilterState*>(prev_states[i])};
        if (needs_init) {
            state = {};
            prev_state = {};
        } else if (is_first_mix_buffer) {
            prev_state = state;
        } else {
            state = prev_state;
        }
    }

    // Nothing would reach the output (the generator does not emit these).
    if (prev_volume == 0.0f && ramp == 0.0f) {
        *prev_sample_ptr = 0;
        return;
    }

    auto input_buffer{
        processor.mix_buffers.subspan(input * processor.sample_count, processor.sample_count)};
    auto output_buffer{
        processor.mix_buffers.subspan(output * processor.sample_count, processor.sample_count)};

    thread_local std::vector<s32> filtered;
    filtered.resize(processor.sample_count);
    std::copy(input_buffer.begin(), input_buffer.end(), filtered.begin());

    for (u32 i = 0; i < filter_count; i++) {
        auto& state{*reinterpret_cast<VoiceState::BiquadFilterState*>(states[i])};
        ApplyBiquadFilterFloat(filtered, filtered, biquads[i].b, biquads[i].a, state,
                               processor.sample_count);
    }

    switch (precision) {
    case 15:
        *prev_sample_ptr = ApplyMixRamp<15>(output_buffer, filtered, prev_volume, ramp,
                                            processor.sample_count);
        break;
    case 23:
        *prev_sample_ptr = ApplyMixRamp<23>(output_buffer, filtered, prev_volume, ramp,
                                            processor.sample_count);
        break;
    default:
        LOG_ERROR(Service_Audio, "Invalid precision {}", precision);
        break;
    }
}

bool BiquadFilterAndMixCommand::Verify(
    [[maybe_unused]] const AudioRenderer::CommandListProcessor& processor) {
    return true;
}

} // namespace AudioCore::Renderer
