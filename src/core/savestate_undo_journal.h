// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <algorithm>
#include <atomic>
#include <functional>
#include <initializer_list>
#include <mutex>
#include <utility>
#include <vector>

#include "common/common_types.h"
#include "common/logging/log.h"

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
        undo.push_back({what, std::function<bool()>(std::forward<F>(step)), NextOrder()});
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
    /// Covered by one noted range or by several that together leave no gap: a saved region can
    /// span memory the game unmapped in separate calls (adjacent physical memory blocks merge
    /// into one region at the save).
    bool WillRestore(u64 address, u64 size) {
        std::scoped_lock lock{mutex};
        const u64 end = address + size;
        u64 covered = address; // everything below this is known to be restored
        bool advanced = true;
        while (covered < end && advanced) {
            advanced = false;
            for (const auto& [start, length] : restored_ranges) {
                if (start <= covered && covered < start + length) {
                    covered = start + length;
                    advanced = true;
                }
            }
        }
        return covered >= end;
    }

    /// The game is shutting down: forgets what was recorded and records nothing until the next
    /// save. The steps hold what the game closed since the save (service objects, open host files)
    /// and would otherwise keep it alive into the next game.
    void Stop() {
        std::vector<Step> dropped;
        {
            std::scoped_lock lock{mutex};
            dropped.swap(undo);
            restored_ranges.clear();
            recording = false;
            usable = false;
        }
        // Destroyed outside the lock: releasing a service object can run code that calls Record.
        dropped.clear();
    }

    /// Stop() for every journal.
    static void StopAll() {
        Memory().Stop();
        Gpu().Stop();
        Services().Stop();
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

    /// Whether changes are being followed since a save made in this run of the game (Start), as
    /// opposed to none made yet, or one from before the game or the app was closed (Stop).
    bool IsRecording() {
        std::scoped_lock lock{mutex};
        return recording;
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
        return UndoAllTogether({this}).front();
    }

    /// Undoes everything the journals recorded since the save, newest first across all of them,
    /// and returns, per journal, whether all its steps were undone. One order across the journals
    /// matters when their changes depend on each other: memory the game maps and then locks for
    /// the GPU (memory journal, then GPU journal) can only be unmapped once the lock is undone,
    /// and memory it unlocks and then unmaps has to be mapped again before it can be locked.
    static std::vector<bool> UndoAllTogether(std::initializer_list<UndoJournal*> journals) {
        struct Taken {
            Step step;
            size_t journal;
        };
        std::vector<Taken> steps;
        std::vector<bool> taken(journals.size(), false);
        std::vector<bool> ok(journals.size(), false);
        size_t index = 0;
        for (UndoJournal* journal : journals) {
            std::vector<Step> own;
            if (journal->TakeSteps(own)) {
                taken[index] = true;
                ok[index] = true;
                for (auto& step : own) {
                    steps.push_back({std::move(step), index});
                }
            }
            index++;
        }
        std::sort(steps.begin(), steps.end(),
                  [](const Taken& a, const Taken& b) { return a.step.order > b.step.order; });
        u32 logged = 0;
        for (size_t i = 0; i < steps.size(); i++) {
            if (!steps[i].step.undo()) {
                ok[steps[i].journal] = false;
                if (logged++ < 8) {
                    LOG_WARNING(Core, "Quick Load: could not undo '{}' (step {} of {})",
                                steps[i].step.what, steps.size() - i, steps.size());
                }
            }
        }
        index = 0;
        for (UndoJournal* journal : journals) {
            if (taken[index]) {
                journal->FinishUndo(ok[index]);
            }
            index++;
        }
        return ok;
    }

private:
    static constexpr size_t MaxSteps = 200'000;

    struct Step {
        const char* what;
        std::function<bool()> undo;
        u64 order; // when it was recorded, across all journals (see UndoAllTogether)
    };

    // Recording order across all journals.
    static u64 NextOrder() {
        static std::atomic<u64> next{0};
        return next.fetch_add(1, std::memory_order_relaxed);
    }

    // Starts an undo: hands over the recorded steps and stops recording, as the steps call back
    // into code that must not record its own undoing. False, and nothing handed over, when the
    // journal is not usable.
    bool TakeSteps(std::vector<Step>& out) {
        std::scoped_lock lock{mutex};
        if (!recording || !usable) {
            return false;
        }
        out.swap(undo);
        undo.clear();
        restored_ranges.clear();
        recording = false;
        return true;
    }

    // Ends an undo started by TakeSteps(): recording carries on, usable only if every step was
    // undone.
    void FinishUndo(bool ok) {
        std::scoped_lock lock{mutex};
        recording = true;
        usable = ok;
    }

    std::mutex mutex;
    std::vector<Step> undo;
    std::vector<std::pair<u64, u64>> restored_ranges;
    bool recording{false};
    bool usable{false};
};

} // namespace Core::SaveState
