#!/usr/bin/env bash
set -e

# Codespaces' default clone doesn't pull submodules on its own.
git submodule update --init --recursive

# Delegate to the project's own CI scripts rather than hand-rolling SDK
# install logic here - this is what Iceraven's real pipeline runs, so it
# gets the platform/build-tools/NDK versions the build actually expects
# (a hand-picked platforms;android-34 / build-tools;34.0.0 pairing isn't
# guaranteed to match, and was the source of earlier build failures).
./automation/iceraven/install-sdk.sh

free -h

./automation/iceraven/patch_android_components.sh
./automation/iceraven/setup_venv.sh

echo "Dev environment setup complete."
