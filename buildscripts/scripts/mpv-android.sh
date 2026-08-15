#!/bin/bash -e

BUILD="./buildscripts"

. $BUILD/include/path.sh
. $BUILD/include/depinfo.sh # for $v_sdk_build_tools

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf {app,.}/build app/src/main/{libs,obj}
	exit 0
else
	exit 255
fi

release_only=0
if [[ "${MPV_ANDROID_GITHUB_ACTIONS_ARM64_RELEASE_ONLY:-}" == "1" || \
      "${MPV_ANDROID_GITHUB_ACTIONS_ARM64_RELEASE_ONLY:-}" == "true" ]]; then
	release_only=1
fi

[ -n "$ANDROID_SIGNING_KEY" ] && [ $release_only -eq 0 ] && BUNDLE=1

nativeprefix () {
	if [ -f $BUILD/prefix/$1/lib/libmpv.so ]; then
		echo "$(realpath "$BUILD/prefix/$1")"
	else
		echo >&2 "Warning: libmpv.so not found in native prefix for $1, support will be omitted"
	fi
}

if [ $release_only -eq 1 ]; then
	prefix32=
	prefix64=$(nativeprefix "arm64")
	prefix_x64=
	prefix_x86=
else
	prefix32=$(nativeprefix "armv7l")
	prefix64=$(nativeprefix "arm64")
	prefix_x64=$(nativeprefix "x86_64")
	prefix_x86=$(nativeprefix "x86")
fi

if [[ -z "$prefix32" && -z "$prefix64" && -z "$prefix_x64" && -z "$prefix_x86" ]]; then
	echo >&2 "Error: no mpv library detected."
	exit 1
fi

### Native parts
PREFIX32="$prefix32" PREFIX64="$prefix64" PREFIX_X64="$prefix_x64" PREFIX_X86="$prefix_x86" \
ndk-build -C app/src/main -j$cores

### Java parts
if [ $release_only -eq 1 ]; then
	targets=(assembleDefaultRelease)
else
	targets=(assembleDebug)
	if [ -z "$DONT_BUILD_RELEASE" ]; then
		targets+=(assembleRelease)
		[ -n "$BUNDLE" ] && targets+=(bundleRelease)
	fi
fi
./gradlew "${targets[@]}"

### Signing
sign_apk () {
	local input=$1
	local output=$2
	local args=(sign --ks "${ANDROID_SIGNING_KEY}")

	[ -n "${ANDROID_SIGNING_STORE_PASSWORD:-}" ] && \
		args+=(--ks-pass "pass:${ANDROID_SIGNING_STORE_PASSWORD}")
	[ -n "${ANDROID_SIGNING_KEY_PASSWORD:-}" ] && \
		args+=(--key-pass "pass:${ANDROID_SIGNING_KEY_PASSWORD}")
	[ -n "${ANDROID_SIGNING_ALIAS:-}" ] && \
		args+=(--ks-key-alias "${ANDROID_SIGNING_ALIAS}")

	"$apksigner" "${args[@]}" --in "$input" --out "$output"
}

if [ -n "$ANDROID_SIGNING_KEY" ]; then
	cd "app/build/outputs/apk"
	apksigner=${ANDROID_HOME}/build-tools/${v_sdk_build_tools}/apksigner

	if [ $release_only -eq 1 ]; then
		pushd default/release
		sign_apk app-default-arm64-v8a-release-unsigned.apk \
			app-default-arm64-v8a-release-signed.apk
		popd
		exit 0
	fi

	for v in default api29; do
		pushd $v
		# sign only the universal debug APK
		sign_apk debug/app-$v-universal-debug.apk debug/app-$v-universal-debug-signed.apk
		# but all of the release APKs
		for apk in release/*-unsigned.apk; do
			sign_apk $apk ${apk/-unsigned/-signed}
		done
		popd
	done
	# and the bundle
	cd ../bundle
	if [ -n "$BUNDLE" ]; then
		if [ -z "$ANDROID_SIGNING_ALIAS" ]; then
			echo >&2 "Error: ANDROID_SIGNING_ALIAS must be set to use jarsigner"
			exit 1
		fi
		pushd defaultRelease
		jarsigner -keystore "${ANDROID_SIGNING_KEY}" -signedjar \
			app-default-release-signed.aab app-default-release.aab \
			"${ANDROID_SIGNING_ALIAS}"
		popd
	fi
fi
