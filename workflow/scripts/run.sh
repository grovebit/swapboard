#!/bin/zsh --no-rcs
# Runs an action chosen in the `clip` menu (or the hotkey). Output becomes the notification text.
action="$1"
output="$(./swapboard run "$action" 2>&1)"
rc=$?
if [[ "$action" == "paste-last" && $rc -eq 0 ]]; then
  # Alfred has closed its window; give focus a moment to return, then paste.
  sleep 0.2
  osascript -e 'tell application "System Events" to keystroke "v" using command down'
  exit 0
fi
print -r -- "$output"
