#!/bin/zsh --no-rcs
# Universal Action "Send to phone clipboard": sends the selected text / URL as-is.
./clipsync send --text "$1" 2>&1
