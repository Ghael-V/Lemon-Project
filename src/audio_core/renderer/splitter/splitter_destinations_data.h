// SPDX-FileCopyrightText: Copyright 2022 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <array>
#include <span>

#include "audio_core/common/common.h"
#include "common/common_types.h"

namespace AudioCore::Renderer {
/**
 * Represents a mixing node, can be connected to a previous and next destination forming a chain
 * that a certain mix buffer will pass through to output.
 */
class SplitterDestinationData {
public:
    struct InParameter {
        /* 0x00 */ u32 magic; // 'SNDD'
        /* 0x04 */ s32 id;
        /* 0x08 */ std::array<f32, MaxMixBuffers> mix_volumes;
        /* 0x68 */ u32 mix_id;
        /* 0x6C */ bool in_use;
    };
    static_assert(sizeof(InParameter) == 0x70,
                  "SplitterDestinationData::InParameter has the wrong size!");

    // Sizes of the destination parameter by revision: the base one, with two Q14 biquad filters
    // (REV12, 0x18 bytes) and with two float ones (REV15, 0x30 bytes) after mix_id. The filters
    // are read past; in_use and reset_prev_volume follow them.
    static constexpr u32 InParameterSize = 0x70;
    static constexpr u32 InParameterBiquadSize = 0x90;
    static constexpr u32 InParameterFloatBiquadSize = 0xA8;

    SplitterDestinationData(s32 id);

    /**
     * Reset the mix volumes for this destination.
     */
    void ClearMixVolume();

    /**
     * Get the id of this destination.
     *
     * @return Id for this destination.
     */
    s32 GetId() const;

    /**
     * Check if this destination is correctly configured.
     *
     * @return True if configured, otherwise false.
     */
    bool IsConfigured() const;

    /**
     * Get the mix id for this destination.
     *
     * @return Mix id for this destination.
     */
    s32 GetMixId() const;

    /**
     * Get the current mix volume of a given index in this destination.
     *
     * @param index - Mix buffer index to get the volume for.
     * @return Current volume of the specified mix.
     */
    f32 GetMixVolume(u32 index) const;

    /**
     * Get the current mix volumes for all mix buffers in this destination.
     *
     * @return Span of current mix buffer volumes.
     */
    std::span<f32> GetMixVolume();

    /**
     * Get the previous mix volume of a given index in this destination.
     *
     * @param index - Mix buffer index to get the volume for.
     * @return Previous volume of the specified mix.
     */
    f32 GetMixVolumePrev(u32 index) const;

    /**
     * Get the previous mix volumes for all mix buffers in this destination.
     *
     * @return Span of previous mix buffer volumes.
     */
    std::span<f32> GetMixVolumePrev();

    /**
     * Update this destination.
     *
     * @param params - Input parameters to update the destination.
     */
    void Update(const InParameter& params);

    /**
     * Update from a parameter of any revision: [in_use] and [reset_prev_volume] were read from
     * wherever that revision keeps them. With [prev_volume_reset_supported] (REV13) the game says
     * when to reset the previous volume.
     */
    void Update(const InParameter& params, bool in_use, bool reset_prev_volume,
                bool prev_volume_reset_supported);

    /**
     * Mark this destination as needing its volumes updated.
     */
    void MarkAsNeedToUpdateInternalState();

    /**
     * Copy current volumes to previous if an update is required.
     */
    void UpdateInternalState();

    /**
     * Get the next destination in the mix chain.
     *
     * @return The next splitter destination, may be nullptr if this is the last in the chain.
     */
    SplitterDestinationData* GetNext() const;

    /**
     * Set the next destination in the mix chain.
     *
     * @param next - Destination this one is to be connected to.
     */
    void SetNext(SplitterDestinationData* next);

private:
    /// Id of this destination
    const s32 id;
    /// Mix id this destination represents
    s32 destination_id{UnusedMixId};
    /// Current mix volumes
    std::array<f32, MaxMixBuffers> mix_volumes{0.0f};
    /// Previous mix volumes
    std::array<f32, MaxMixBuffers> prev_mix_volumes{0.0f};
    /// Next destination in the mix chain
    SplitterDestinationData* next{};
    /// Is this destination in use?
    bool in_use{};
    /// Does this destination need its volumes updated?
    bool need_update{};
};

} // namespace AudioCore::Renderer
