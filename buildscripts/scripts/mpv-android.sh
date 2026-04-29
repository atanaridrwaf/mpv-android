#!/bin/bash -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BUILD="$DIR/.."
MPV_ANDROID="$DIR/../.."

. $BUILD/include/path.sh
. $BUILD/include/depinfo.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf $MPV_ANDROID/{app,.}/build $MPV_ANDROID/app/src/main/{libs,obj}
	exit 0
else
	exit 255
fi

[ -n "$ANDROID_SIGNING_KEY" ] && BUNDLE=1

nativeprefix () {
	if [ -f $BUILD/prefix/$1/lib/libmpv.so ]; then
		echo $BUILD/prefix/$1
	else
		echo >&2 "Warning: libmpv.so not found in native prefix for $1, support will be omitted"
	fi
}

prefix32=$(nativeprefix "armv7l")
prefix64=$(nativeprefix "arm64")
prefix_x64=$(nativeprefix "x86_64")
prefix_x86=$(nativeprefix "x86")

if [[ -z "$prefix32" && -z "$prefix64" && -z "$prefix_x64" && -z "$prefix_x86" ]]; then
	echo >&2 "Error: no mpv library detected."
	exit 255
fi

PREFIX32=$prefix32 PREFIX64=$prefix64 PREFIX_X64=$prefix_x64 PREFIX_X86=$prefix_x86 \
ndk-build -C app/src/main -j$cores

targets=(assembleDefaultRelease)
[ -n "$BUNDLE" ] && targets+=(bundleDefaultRelease)
./gradlew "${targets[@]}"

if [ -n "$ANDROID_SIGNING_KEY" ]; then
	cd "${MPV_ANDROID}/app/build/outputs/apk/default/release"
	apksigner=${ANDROID_HOME}/build-tools/${v_sdk_build_tools}/apksigner
	for apk in *.apk; do
		case "$apk" in
			*-signed.apk|*-aligned.apk) continue ;;
		esac
		"$apksigner" sign --ks "${ANDROID_SIGNING_KEY}" \
			--in "$apk" --out "${apk/.apk/-signed.apk}"
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
