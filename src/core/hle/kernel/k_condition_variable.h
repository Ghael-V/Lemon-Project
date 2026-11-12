// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <memory>

#include "common/assert.h"

#include "core/hle/kernel/k_scheduler.h"
#include "core/hle/kernel/k_thread.h"
#include "core/hle/kernel/k_typed_address.h"
#include "core/hle/kernel/kernel.h"
#include "core/hle/result.h"

namespace Core {
class System;
}

namespace Kernel {

class KConditionVariable {
public:
    using ThreadTree = typename KThread::ConditionVariableThreadTreeType;

    explicit KConditionVariable(Core::System& system);
    ~KConditionVariable();

    // Arbitration.
    static Result SignalToAddress(KernelCore& kernel, KProcessAddress addr);
    static Result WaitForAddress(KernelCore& kernel, Handle handle, KProcessAddress addr,
                                 u32 value);

    // Condition variable.
    void Signal(u64 cv_key, s32 count);
    Result Wait(KProcessAddress addr, u64 key, u32 value, s64 timeout);

    // Savestate restore only: puts a thread that is not inside a kernel call (one created again by
    // a load) to sleep on a condition variable, as WaitProcessWideKeyAtomic(addr, key, value,
    // timeout_ns) leaves a caller, without touching the lock or the key in memory: the restored
    // memory holds them as they were while the saved thread slept there. It has no call to return
    // from, so when it wakes the call's result is put in its x0 here (see the queue), and it
    // resumes at the instruction after the call, as the saved thread would have.
    void WaitForRestore(KThread* thread, KProcessAddress addr, u64 key, u32 value, s64 timeout_ns);

private:
    void SignalImpl(KThread* thread);

private:
    Core::System& m_system;
    KernelCore& m_kernel;
    ThreadTree m_tree{};
    std::unique_ptr<KThreadQueue> m_restore_queue;
};

inline void BeforeUpdatePriority(KernelCore& kernel, KConditionVariable::ThreadTree* tree,
                                 KThread* thread) {
    ASSERT(KScheduler::IsSchedulerLockedByCurrentThread(kernel));

    tree->erase(tree->iterator_to(*thread));
}

inline void AfterUpdatePriority(KernelCore& kernel, KConditionVariable::ThreadTree* tree,
                                KThread* thread) {
    ASSERT(KScheduler::IsSchedulerLockedByCurrentThread(kernel));

    tree->insert(*thread);
}

} // namespace Kernel
