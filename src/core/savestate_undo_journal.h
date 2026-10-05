// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <functional>
#include <mutex>
#include <utility>
#include <vector>

#include "common/common_types.h"
#include "common/logging.h"

namespace Core::SaveState {

/**
 * Between a Quick Save and a Quick Load, remembers how to undo changes the guest makes to state a
 * savestate does not hold, so a Quick Load can put that state back as it was at the save. The
 * restored guest memory refers to that state by value. Measured when the player dies in Super
 * Mario 3D World: the game frees its 192 level buffers and creates them again at the same memory,
 * but under new nvmap handle ids and at new GPU addresses (a load that only rewound memory drew a
 * black scene), and it unmaps the stacks of four worker threads and maps new ones elsewhere (the
 * next death after such a load aborted when the game unmapped a stack the kernel no longer had).
 *
 * A change it cannot undo makes the journal unusable until the next save: a Quick Load then
 * refuses instead of loading onto state that does not match.
 */
class UndoJournal {
public:
    /// nvdrv: nvmap handles and GPU address space mappings.
    static UndoJournal& Gpu() {
        static UndoJournal journal;
        return journal;
    }

    /// Services: domain objects (interfaces a service hands out, such as open files).
    static UndoJournal& Services() {
        static UndoJournal journal;
        return journal;
    }

    /// The kernel: memory the guest maps and unmaps (svcMapMemory / svcUnmapMemory).
    static UndoJournal& Memory() {
        static UndoJournal journal;
        return journal;
    }

    /// Called by a save: from now on, changes are recorded relative to this moment.
    void Start() {
        std::scoped_lock lock{mutex};
        undo.clear();
        restored_ranges.clear();
        recording = true;
        usable = true;
    }

    /// Records how to undo one change; the step returns false if it could not be undone. Nothing
    /// is built while no save is being followed, which is most of the time.
    template <typename F>
    void Record(const char* what, F&& step) {
        std::scoped_lock lock{mutex};
        if (!recording || !usable) {
            return;
        }
        if (undo.size() >= MaxSteps) {
            usable = false;
            undo.clear();
            restored_ranges.clear();
            return;
        }
        undo.push_back({what, std::function<bool()>(std::forward<F>(step))});
    }

    /// Notes a guest memory range that undoing will map again, so a load does not count it as
    /// memory the guest lost.
    void NoteRestoredRange(u64 address, u64 size) {
        std::scoped_lock lock{mutex};
        if (recording && usable) {
            restored_ranges.emplace_back(address, size);
        }
    }

    /// Whether undoing maps [address, address + size) again.
    bool WillRestore(u64 address, u64 size) {
        std::scoped_lock lock{mutex};
        for (const auto& [start, length] : restored_ranges) {
            if (address >= start && address + size <= start + length) {
                return true;
            }
        }
        return false;
    }

    /// A change happened that cannot be undone.
    void Invalidate() {
        std::scoped_lock lock{mutex};
        if (recording) {
            usable = false;
            undo.clear();
            restored_ranges.clear();
        }
    }

    bool CanUndo() {
        std::scoped_lock lock{mutex};
        return recording && usable;
    }

    size_t Size() {
        std::scoped_lock lock{mutex};
        return undo.size();
    }

    /// Undoes everything recorded since the save, newest first. Afterwards the state is the one of
    /// the save again, so recording carries on from an empty journal.
    bool UndoAll() {
        std::vector<Step> steps;
        {
            std::scoped_lock lock{mutex};
            if (!recording || !usable) {
                return false;
            }
            steps.swap(undo);
            restored_ranges.clear();
            // The steps call back into code that must not record its own undoing.
            recording = false;
        }
        bool ok = true;
        u32 logged = 0;
        for (auto it = steps.rbegin(); it != steps.rend(); ++it) {
            if (!it->undo()) {
                ok = false;
                if (logged++ < 8) {
                    LOG_WARNING(Core, "Quick Load: could not undo '{}' (step {} of {})", it->what,
                                static_cast<size_t>(steps.rend() - it), steps.size());
                }
            }
        }
        std::scoped_lock lock{mutex};
        recording = true;
        usable = ok;
        return ok;
    }

private:
    static constexpr size_t MaxSteps = 200'000;

    struct Step {
        const char* what;
        std::function<bool()> undo;
    };

    std::mutex mutex;
    std::vector<Step> undo;
    std::vector<std::pair<u64, u64>> restored_ranges;
    bool recording{false};
    bool usable{false};
};

} // namespace Core::SaveState
