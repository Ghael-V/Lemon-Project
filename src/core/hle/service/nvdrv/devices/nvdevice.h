// SPDX-FileCopyrightText: Copyright 2018 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <span>
#include <vector>

#include "common/common_types.h"
#include "core/hle/service/nvdrv/core/container.h"
#include "core/hle/service/nvdrv/nvdata.h"

namespace Core {
class System;
}

namespace Kernel {
class KEvent;
}

namespace Service::Nvidia::Devices {

/// Represents an abstract nvidia device node. It is to be subclassed by concrete device nodes to
/// implement the ioctl interface.
class nvdevice {
public:
    explicit nvdevice(Core::System& system_) : system{system_} {}
    virtual ~nvdevice() = default;

    /**
     * Handles an ioctl1 request.
     * @param command The ioctl command id.
     * @param input A buffer containing the input data for the ioctl.
     * @param output A buffer where the output data will be written to.
     * @returns The result code of the ioctl.
     */
    virtual NvResult Ioctl1(DeviceFD fd, Ioctl command, std::span<const u8> input,
                            std::span<u8> output) = 0;

    /**
     * Handles an ioctl2 request.
     * @param command The ioctl command id.
     * @param input A buffer containing the input data for the ioctl.
     * @param inline_input A buffer containing the input data for the ioctl which has been inlined.
     * @param output A buffer where the output data will be written to.
     * @returns The result code of the ioctl.
     */
    virtual NvResult Ioctl2(DeviceFD fd, Ioctl command, std::span<const u8> input,
                            std::span<const u8> inline_input, std::span<u8> output) = 0;

    /**
     * Handles an ioctl3 request.
     * @param command The ioctl command id.
     * @param input A buffer containing the input data for the ioctl.
     * @param output A buffer where the output data will be written to.
     * @param inline_output A buffer where the inlined output data will be written to.
     * @returns The result code of the ioctl.
     */
    virtual NvResult Ioctl3(DeviceFD fd, Ioctl command, std::span<const u8> input,
                            std::span<u8> output, std::span<u8> inline_output) = 0;

    /**
     * Called once a device is opened
     * @param fd The device fd
     */
    virtual void OnOpen(NvCore::SessionId session_id, DeviceFD fd) = 0;

    /**
     * Called once a device is closed
     * @param fd The device fd
     */
    virtual void OnClose(DeviceFD fd) = 0;

    /// Quick Load support (see Core::SaveState::UndoJournal): whether a Quick Load can open this
    /// device again after the game closed it, and close it again after the game opened it.
    virtual bool CanReopenForRestore() const {
        return false;
    }

    /// Called instead of OnClose when a Quick Load may open the device again: closes it as far as
    /// the game can tell, but keeps what opening it anew could not bring back.
    virtual void OnCloseForRestore(DeviceFD fd) {
        OnClose(fd);
    }

    /// Opens a device closed by OnCloseForRestore again.
    virtual void OnReopenForRestore(NvCore::SessionId session_id, DeviceFD fd) {
        OnOpen(session_id, fd);
    }

    /// A device closed by OnCloseForRestore that is not opened again lets go of what it kept.
    virtual void OnReleaseForRestore(DeviceFD fd) {}

    virtual Kernel::KEvent* QueryEvent(u32 event_id) {
        return nullptr;
    }

protected:
    Core::System& system;
};

} // namespace Service::Nvidia::Devices
