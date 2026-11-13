// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <array>
#include <string>

#include "audio_core/renderer/command/icommand.h"
#include "audio_core/renderer/voice/voice_info.h"
#include "common/common_types.h"

namespace AudioCore::ADSP::AudioRenderer {
class CommandListProcessor;
}

namespace AudioCore::Renderer {

/**
 * AudioRenderer command for a splitter destination with biquad filters (REV12+): the voice's
 * buffer goes through one or two biquad filters and is then mixed, with a volume ramp, into one
 * mix buffer of the destination. The input buffer is left untouched, the filtering happens in a
 * scratch buffer.
 *
 * A destination mixes into several buffers from the same filter state, so the first one saves
 * the state and the rest rewind to it before filtering.
 */
struct BiquadFilterAndMixCommand : ICommand {
    void Dump(const AudioRenderer::CommandListProcessor& processor, std::string& string) override;
    void Process(const AudioRenderer::CommandListProcessor& processor) override;
    bool Verify(const AudioRenderer::CommandListProcessor& processor) override;

    /// Input mix buffer index
    s16 input;
    /// Output mix buffer index
    s16 output;
    /// Filters to apply, in order
    std::array<VoiceInfo::BiquadFilterParameter, MaxBiquadFilters> biquads;
    /// Number of filters in use (1 or 2)
    u32 filter_count;
    /// Filter state of each filter, updated each call
    std::array<CpuAddr, MaxBiquadFilters> states;
    /// State of each filter at the start of this frame, to rewind to for later mix buffers
    std::array<CpuAddr, MaxBiquadFilters> prev_states;
    /// If true, the filters start from a clean state
    bool needs_init;
    /// If true, this is the destination's first mix buffer this frame
    bool is_first_mix_buffer;
    /// Volume at the start of the frame
    f32 prev_volume;
    /// Volume at the end of the frame
    f32 volume;
    /// Where the last mixed sample is saved, for depopping
    CpuAddr previous_sample;
    /// Number of decimal bits for the mix
    u8 precision;
};

} // namespace AudioCore::Renderer
