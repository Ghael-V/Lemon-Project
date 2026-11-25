# AI per-game settings schema

The list of settings the AI game-config feature may change, with type, default, range or enum values and a
description. The server puts it in the model prompt and validates the answer against it. The app validates again
before it writes the per-game config.

Regenerate after any change to `src/common/settings.h`, `settings_enums.h` or the settings UI:

```sh
python -I .ci/ai-schema/gen_schema.py . ironlemon dev/ironlemon/ironlemon_emulator /tmp/il.raw.json
python -I .ci/ai-schema/merge.py /tmp/il.raw.json .ci/ai-schema/tiers.json \
    src/android/app/src/main/res/raw/ai_settings_schema.json
```

- `gen_schema.py` reads every `Setting` / `SwitchableSetting`, resolves `#if` blocks as an arm64 build sees them,
  and adds the UI title and description where the settings UI has one.
- `tiers.json` decides what the AI may touch. Only `SwitchableSetting`s can be per-game, so global settings are
  always left out.
  - `allow`: the AI may change it.
  - `risky`: only with a curated precedent, shown as experimental.
  - `preference`: the user's taste (volume, speed limit, frame generation, filters…). The AI never changes it.
  - Anything else is `never`. `never_reasons` says why for the ones that could look useful.
- Only `allow` and `risky` are exported.

Lemon uses the same tools with its own tree; its keys and enums differ, so each app has its own schema.
