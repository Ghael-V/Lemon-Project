// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Minimal homebrew built specifically as a savestate test target: no threads, no network,
// no filesystem access beyond what libnx's default init pulls in, no applets besides the
// bare minimum. Two pieces of visible state to check across a save/load round-trip:
//
//   - frame_counter: increments every frame on its own. If a loadstate doesn't put this back
//     to exactly what it was at save time (and doesn't keep incrementing correctly from
//     there), CPU/timing state wasn't captured correctly.
//   - button_presses: only changes on a deliberate A press. If this doesn't survive a
//     save/load, or if presses made after loading don't count, input-adjacent state
//     wasn't captured correctly either.
//
// Press + to exit.

#include <switch.h>
#include <stdio.h>

int main(int argc, char* argv[]) {
    consoleInit(NULL);

    PadState pad;
    padConfigureInput(1, HidNpadStyleSet_NpadStandard);
    padInitializeDefault(&pad);

    u64 frame_counter = 0;
    u32 button_presses = 0;

    while (appletMainLoop()) {
        padUpdate(&pad);
        u64 kDown = padGetButtonsDown(&pad);

        if (kDown & HidNpadButton_Plus) {
            break;
        }
        if (kDown & HidNpadButton_A) {
            button_presses++;
        }

        printf("\x1b[1;1HLemon savestate test homebrew\n");
        printf("\x1b[3;1HFrame counter:     %llu\n", (unsigned long long)frame_counter);
        printf("\x1b[4;1HA-button presses:  %u\n", button_presses);
        printf("\x1b[6;1HPress A to increment, + to exit.\n");

        consoleUpdate(NULL);
        frame_counter++;
    }

    consoleExit(NULL);
    return 0;
}
