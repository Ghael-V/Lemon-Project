// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <algorithm>
#include <atomic>
#include <functional>
#include <initializer_list>
#include <iterator>
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
 * Several saves can be followed at once (the Quick Save slots): each is a moment in the recording
 * order (Mark), and a load undoes only what was recorded after its save's moment. What was recorded
 * before the oldest save still to be loaded is let go at the next save.
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

    /// The current moment in the recording order: a save's, for a load to go back to.
    static u64 Mark() {
        return NextOrder();
    }

    /// Called by a save made at `mark`: from now on, changes are recorded for it too. What was
    /// recorded before `keep_from`, the moment of the oldest save still to be loaded, is let go.
    void Start(u64 mark, u64 keep_from) {
        std::vector<Step> dropped;
        {
            std::scoped_lock lock{mutex};
            if (!recording || !usable) {
                // Nothing usable was followed until now: only this save and later ones can load.
                first_mark = mark;
                keep_from = mark;
            }
            DropBefore(keep_from, dropped);
            last_mark = mark;
            recording = true;
            usable = true;
        }
        // Destroyed outside the lock: releasing a service object can run code that calls Record.
        dropped.clear();
    }

    /// Records how to undo one change; the step returns false if it could not be undone. Nothing
    /// is built while no save is being followed, which is most of the time.
    template <typename F>
    void Record(const char* what, F&& step) {
        std::vector<Step> dropped;
        {
            std::scoped_lock lock{mutex};
            if (!recording || !usable) {
                return;
            }
            if (undo.size() >= MaxSteps) {
                // Too much to follow: saves older than the last one are given up first.
                if (last_mark <= first_mark) {
                    usable = false;
                    undo.clear();
                    restored_ranges.clear();
                    return;
                }
                DropBefore(last_mark, dropped);
                first_mark = last_mark;
            }
            undo.push_back({what, std::function<bool()>(std::forward<F>(step)), NextOrder()});
        }
        // Destroyed outside the lock: releasing a service object can run code that calls Record.
        dropped.clear();
    }

    /// Notes a guest memory range that undoing will map again, so a load does not count it as
    /// memory the guest lost.
    void NoteRestoredRange(u64 address, u64 size) {
        std::scoped_lock lock{mutex};
        if (recording && usable) {
            restored_ranges.push_back({address, size, NextOrder()});
        }
    }

    /// Whether undoing back to the save made at `mark` maps [address, address + size) again.
    /// Covered by one noted range or by several that together leave no gap: a saved region can
    /// span memory the game unmapped in separate calls (adjacent physical memory blocks merge
    /// into one region at the save).
    bool WillRestore(u64 address, u64 size, u64 mark) {
        std::scoped_lock lock{mutex};
        const u64 end = address + size;
        u64 covered = address; // everything below this is known to be restored
        bool advanced = true;
        while (covered < end && advanced) {
            advanced = false;
            for (const auto& [start, length, order] : restored_ranges) {
                if (order > mark && start <= covered && covered < start + length) {
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
        LastStop() = NextOrder();
    }

    /// Whether the save made at `mark` was made since the game started (since the last StopAll).
    static bool InThisRun(u64 mark) {
        return mark > LastStop().load();
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

    /// Whether a change made now could be undone by a load: a save is being followed.
    bool CanUndo() {
        std::scoped_lock lock{mutex};
        return recording && usable;
    }

    /// Whether everything since the save made at `mark` can be undone.
    bool CanUndoTo(u64 mark) {
        std::scoped_lock lock{mutex};
        return recording && usable && mark >= first_mark;
    }

    /// How many changes were recorded since the save made at `mark`.
    size_t Size(u64 mark) {
        std::scoped_lock lock{mutex};
        return static_cast<size_t>(
            std::ranges::count_if(undo, [mark](const Step& step) { return step.order > mark; }));
    }

    /// Undoes everything recorded since the save made at `mark`, newest first. Afterwards the
    /// state is the one of that save again, so recording carries on from there.
    bool UndoAll(u64 mark) {
        return UndoAllTogether({this}, mark).front();
    }

    /// Undoes everything the journals recorded since the save, newest first across all of them,
    /// and returns, per journal, whether all its steps were undone. One order across the journals
    /// matters when their changes depend on each other: memory the game maps and then locks for
    /// the GPU (memory journal, then GPU journal) can only be unmapped once the lock is undone,
    /// and memory it unlocks and then unmaps has to be mapped again before it can be locked.
    static std::vector<bool> UndoAllTogether(std::initializer_list<UndoJournal*> journals,
                                             u64 mark) {
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
            if (journal->TakeSteps(own, mark)) {
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

    struct RestoredRange {
        u64 address;
        u64 size;
        u64 order; // when it was noted (see NextOrder)
    };

    // Recording order across all journals. Starts above LastStop's first value (see InThisRun).
    static u64 NextOrder() {
        static std::atomic<u64> next{1};
        return next.fetch_add(1, std::memory_order_relaxed);
    }

    // Moves the steps recorded before `order` to `dropped`, and forgets the ranges noted before it.
    void DropBefore(u64 order, std::vector<Step>& dropped) {
        // Steps are kept in recording order.
        const auto kept = std::ranges::partition_point(
            undo, [order](const Step& step) { return step.order < order; });
        dropped.assign(std::make_move_iterator(undo.begin()), std::make_move_iterator(kept));
        undo.erase(undo.begin(), kept);
        std::erase_if(restored_ranges,
                      [order](const RestoredRange& range) { return range.order < order; });
    }

    // The moment of the last StopAll.
    static std::atomic<u64>& LastStop() {
        static std::atomic<u64> last_stop{0};
        return last_stop;
    }

    // Starts an undo back to the save made at `mark`: hands over the steps recorded since and
    // stops recording, as the steps call back into code that must not record its own undoing.
    // False, and nothing handed over, when the journal cannot undo that far.
    bool TakeSteps(std::vector<Step>& out, u64 mark) {
        std::scoped_lock lock{mutex};
        if (!recording || !usable || mark < first_mark) {
            return false;
        }
        // Steps are kept in recording order.
        const auto since = std::ranges::partition_point(
            undo, [mark](const Step& step) { return step.order < mark; });
        out.assign(std::make_move_iterator(since), std::make_move_iterator(undo.end()));
        undo.erase(since, undo.end());
        std::erase_if(restored_ranges,
                      [mark](const RestoredRange& range) { return range.order > mark; });
        // Saves made after it are gone with what is undone: it is the newest one left.
        last_mark = std::min(last_mark, mark);
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
    std::vector<RestoredRange> restored_ranges;
    u64 first_mark{0}; // the oldest save whose changes since are all recorded
    u64 last_mark{0};  // the newest save
    bool recording{false};
    bool usable{false};
};

} // namespace Core::SaveState
