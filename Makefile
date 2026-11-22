# ClipSync: build, test and install. Paths contain spaces, so everything is quoted.
SHELL := /bin/zsh
ROOT := $(shell pwd)
ALFRED_WORKFLOWS := $(HOME)/Library/Application Support/Alfred/Alfred.alfredpreferences/workflows
DEV_LINK := $(ALFRED_WORKFLOWS)/user.workflow.clipsync-dev
# Set SIGN_ID to your "Apple Development: …" identity for a stable code signature (fewer macOS prompts after rebuilds).
SIGN_ID ?= -
# Extra codesign flags, e.g. "--options runtime --timestamp" for Developer ID builds.
CODESIGN_FLAGS ?=
MAC_ARCHS := --arch arm64 --arch x86_64
VERSION := $(shell cat VERSION)
APP := dist/ClipSync.app
# The project's GitHub page, for the app's "get the Android app" QR code and Help link. From CI, or
# from the git remote (credentials dropped; anything that isn't a github.com repo is ignored).
PROJECT_URL ?= $(shell if [ -n "$$GITHUB_REPOSITORY" ]; then echo "https://github.com/$$GITHUB_REPOSITORY"; else git remote get-url origin 2>/dev/null | sed -E 's|^git@github\.com:|https://github.com/|; s|^https://[^@/]*@|https://|; s|\.git$$||' | grep -E '^https://github\.com/[^/]+/[^/]+$$'; fi)
# Gradle runs on Android Studio's bundled JDK when present.
JBR := $(shell d="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; [ -d "$$d" ] && echo "$$d" || /usr/libexec/java_home)
GRADLE = cd android && JAVA_HOME="$(JBR)" ./gradlew

.PHONY: all vectors test test-mac test-android mac app install-app android install-android apk dev-install dev-uninstall package clean

all: mac android

vectors:
	node protocol/tools/gen-vectors.mjs

test: test-mac test-android

test-mac:
	cd mac && swift test

test-android:
	$(GRADLE) --quiet test

# Universal binary: runs on Apple Silicon and Intel Macs (macOS 12+).
mac:
	cd mac && swift build -c release $(MAC_ARCHS)
	cp "$$(cd mac && swift build -c release $(MAC_ARCHS) --show-bin-path)/clipsync" workflow/clipsync
	codesign --force --sign "$(SIGN_ID)" --identifier com.clipsync.agent $(CODESIGN_FLAGS) workflow/clipsync

# The menu-bar app: dist/ClipSync.app and dist/ClipSync-mac.zip (universal, macOS 12+).
app: dist/AppIcon.icns
	cd mac && swift build -c release $(MAC_ARCHS)
	rm -rf "$(APP)"
	mkdir -p "$(APP)/Contents/MacOS" "$(APP)/Contents/Resources"
	cp "$$(cd mac && swift build -c release $(MAC_ARCHS) --show-bin-path)/clipsync" "$(APP)/Contents/MacOS/clipsync"
	cp mac/App/Info.plist "$(APP)/Contents/Info.plist"
	plutil -replace CFBundleShortVersionString -string "$(VERSION)" "$(APP)/Contents/Info.plist"
	plutil -replace CFBundleVersion -string "$(VERSION)" "$(APP)/Contents/Info.plist"
	plutil -replace ClipSyncProjectURL -string "$(PROJECT_URL)" "$(APP)/Contents/Info.plist"
	cp dist/AppIcon.icns "$(APP)/Contents/Resources/AppIcon.icns"
	codesign --force --sign "$(SIGN_ID)" $(CODESIGN_FLAGS) "$(APP)"
	rm -f dist/ClipSync-mac.zip
	ditto -c -k --keepParent "$(APP)" dist/ClipSync-mac.zip
	@echo "Built $(APP) and dist/ClipSync-mac.zip ($(VERSION))"

dist/AppIcon.icns: workflow/icon.png
	rm -rf dist/AppIcon.iconset && mkdir -p dist/AppIcon.iconset
	for size in 16 32 128 256 512; do \
		sips -z $$size $$size workflow/icon.png --out "dist/AppIcon.iconset/icon_$${size}x$${size}.png" >/dev/null; \
		sips -z $$((size * 2)) $$((size * 2)) workflow/icon.png --out "dist/AppIcon.iconset/icon_$${size}x$${size}@2x.png" >/dev/null; \
	done
	iconutil -c icns dist/AppIcon.iconset -o dist/AppIcon.icns
	rm -rf dist/AppIcon.iconset

# Installs the app on this Mac and opens it; it takes over from an older agent.
install-app: app
	rm -rf /Applications/ClipSync.app
	ditto "$(APP)" /Applications/ClipSync.app
	open /Applications/ClipSync.app

android:
	$(GRADLE) assembleDebug -PprojectUrl="$(PROJECT_URL)"

install-android:
	$(GRADLE) installDebug -PprojectUrl="$(PROJECT_URL)"

# Signed release APK (needs CLIPSYNC_KEYSTORE and its passwords; see docs/DEVELOPMENT.md): dist/ClipSync.apk.
apk:
	$(GRADLE) assembleRelease -PprojectUrl="$(PROJECT_URL)"
	@test -f android/app/build/outputs/apk/release/app-release.apk || \
		{ echo "The release APK isn't signed: set CLIPSYNC_KEYSTORE and its passwords" >&2; exit 1; }
	mkdir -p dist
	cp android/app/build/outputs/apk/release/app-release.apk dist/ClipSync.apk

# Links workflow/ into Alfred so edits take effect immediately.
dev-install: mac
	ln -sfn "$(ROOT)/workflow" "$(DEV_LINK)"
	@echo "Linked into Alfred: $(DEV_LINK)"

dev-uninstall:
	rm -f "$(DEV_LINK)"

package: mac
	rm -rf dist/workflow dist/ClipSync.alfredworkflow
	mkdir -p dist/workflow
	cp -R workflow/info.plist workflow/icon.png workflow/clipsync workflow/scripts dist/workflow/
	plutil -replace version -string "$(VERSION)" dist/workflow/info.plist
	cd dist/workflow && zip -q -r -X ../ClipSync.alfredworkflow info.plist icon.png clipsync scripts
	rm -rf dist/workflow
	@echo "Built dist/ClipSync.alfredworkflow ($(VERSION))"

clean:
	rm -rf mac/.build dist workflow/clipsync
	$(GRADLE) --quiet clean
