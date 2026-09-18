// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "core/hle/service/bcat/news/builtin_news.h"

namespace Service::News {

// This used to populate the emulated News applet with the upstream project's own release notes,
// fetched from its Forgejo instance (git.eden-emu.dev) and authored as that project's name, with
// its logo downloaded from its own website. There's no equivalent feed for this fork to point it
// at, so rather than show foreign-branded content fetched from a third party's server, this is a
// no-op: NewsStorage stays empty and callers see the same "no news" result a real console with
// nothing configured would give.
void EnsureBuiltinNewsLoaded() {}

} // namespace Service::News
