# ClipSync: build, test and install. Paths contain spaces, so everything is quoted.
SHELL := /bin/zsh
ROOT := $(shell pwd)
ALFRED_WORKFLOWS := $(HOME)/Library/Application Support/Alfred/Alfred.alfredpreferences/workflows
DEV_LINK := $(ALFRED_WORKFLOWS)/user.workflow.clipsync-dev
# Set SIGN_ID to your "Apple Development: …" identity for a stable code signature (fewer macOS prompts after rebuilds).
SIGN_ID ?= -
# Gradle runs on Android Studio's bundled JDK when present.
JBR := $(shell d="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; [ -d "$$d" ] && echo "$$d" || /usr/libexec/java_home)
GRADLE = cd android && JAVA_HOME="$(JBR)" ./gradlew

.PHONY: all vectors test test-mac test-android mac android install-android dev-install dev-uninstall package clean

all: mac android

vectors:
	node protocol/tools/gen-vectors.mjs

test: test-mac test-android

test-mac:
	cd mac && swift test

test-android:
	$(GRADLE) --quiet test

mac:
	cd mac && swift build -c release
	cp mac/.build/release/clipsync workflow/clipsync
	codesign --force --sign "$(SIGN_ID)" --identifier com.clipsync.agent workflow/clipsync

android:
	$(GRADLE) assembleDebug

install-android:
	$(GRADLE) installDebug

# Links workflow/ into Alfred so edits take effect immediately.
dev-install: mac
	ln -sfn "$(ROOT)/workflow" "$(DEV_LINK)"
	@echo "Linked into Alfred: $(DEV_LINK)"

dev-uninstall:
	rm -f "$(DEV_LINK)"

package: mac
	mkdir -p dist
	rm -f dist/ClipSync.alfredworkflow
	cd workflow && zip -q -r -X ../dist/ClipSync.alfredworkflow info.plist icon.png clipsync scripts
	@echo "Built dist/ClipSync.alfredworkflow"

clean:
	rm -rf mac/.build dist workflow/clipsync
	$(GRADLE) --quiet clean
