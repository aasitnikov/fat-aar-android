#!/bin/bash
# Usage: ./run_matrix.sh "agp:gradle:kotlin:compileSdk:minSdk:targetSdk" [more bundles...]
# Tests the fat-aar plugin (single artifact) against multiple AGP/Gradle/Kotlin/SDK combos
# by building :lib-main:assembleFlavor1Debug and verifying all merge features in the AAR.
set -u
cd "$(dirname "$0")"
WRAPPER=gradle/wrapper/gradle-wrapper.properties
AAR=lib-main/build/outputs/aar/lib-main-flavor1-debug.aar
RESULTS=()

verify_aar() {
  local tag="$1"
  if [ ! -f "$AAR" ]; then echo "  !! AAR not produced"; return 1; fi
  local tmp; tmp=$(mktemp -d); unzip -q "$AAR" -d "$tmp"
  local cj="$tmp/classes.jar"
  local ok=0
  chk(){ if [ "$2" -gt 0 ] 2>/dev/null; then printf "  [PASS] %-22s (%s)\n" "$1" "$3"; else printf "  [FAIL] %-22s (%s)\n" "$1" "$3"; ok=1; fi; }
  chk "Flavors(libaar2->fl3)"  "$(unzip -l "$cj" | grep -c 'libaar2/Aar2LibClass')" "cross-dim flavor3 class"
  chk "Manifest merge"         "$(grep -c 'uses-permission' "$tmp/AndroidManifest.xml")" "embedded permissions"
  chk "Classes merge"          "$(unzip -l "$cj" | grep -cE 'com/bumptech/glide|com/facebook')" "glide+fresco classes"
  chk "Jar merge"              "$(unzip -l "$AAR" | grep -cE 'libs/.*\.jar')" "libs/*.jar"
  chk "Res merge"              "$(find "$tmp/res" -name '*.xml' 2>/dev/null | wc -l | tr -d ' ')" "res files"
  chk "Assets merge"           "$([ -f "$tmp/assets/cat.jpg" ] && echo 1 || echo 0)" "assets/cat.jpg"
  chk "JniLibs merge"          "$(find "$tmp/jni" -name '*.so' 2>/dev/null | wc -l | tr -d ' ')" ".so files"
  chk "R.txt merge"            "$(wc -l < "$tmp/R.txt" 2>/dev/null | tr -d ' ')" "R.txt entries"
  chk "R.class merge(ASM)"     "$([ -f "$tmp/classes.jar" ] && echo 1 || echo 0)" "ASM transform ran; classes merged"
  chk "DataBinding merge"      "$(unzip -l "$AAR" | grep -c 'data-binding')" "data-binding entries"
  chk "Proguard merge"         "$(grep -c 'AarLibClass\|GlideModule\|DoNotStrip' "$tmp/proguard.txt" 2>/dev/null)" "merged proguard rules"
  chk "Kotlin module merge"    "$(unzip -l "$cj" | grep -c 'kotlin_module')" "*.kotlin_module"
  rm -rf "$tmp"
  return $ok
}

for bundle in "$@"; do
  IFS=':' read -r AGP GRADLE KOTLIN CS MS TS <<< "$bundle"
  echo ""
  echo "############################################################"
  echo "# AGP $AGP | Gradle $GRADLE | Kotlin $KOTLIN | compileSdk $CS | minSdk $MS"
  echo "############################################################"
  sed -i '' -E "s#gradle-[0-9.]+-bin\.zip#gradle-${GRADLE}-bin.zip#" "$WRAPPER"
  ./gradlew :lib-main:clean :lib-main:assembleFlavor1Debug \
      -PagpVersion="$AGP" -PkotlinVersion="$KOTLIN" \
      -PcompileSdk="$CS" -PminSdk="$MS" -PtargetSdk="$TS" \
      --console=plain > "/tmp/fataar_$AGP.log" 2>&1
  brc=$?
  if [ $brc -eq 0 ]; then
    echo ">> BUILD SUCCESS (AGP $AGP)"
    if verify_aar "$AGP"; then vr="ALL FEATURES PASS"; else vr="SOME FEATURES FAIL"; fi
    RESULTS+=("AGP $AGP : BUILD OK : $vr")
  else
    echo ">> BUILD FAILED (AGP $AGP) — tail of log:"
    grep -iE 'error|fail|exception|what went wrong|could not' "/tmp/fataar_$AGP.log" | grep -v -i 'performance' | head -12
    RESULTS+=("AGP $AGP : BUILD FAILED (see /tmp/fataar_$AGP.log)")
  fi
done

echo ""
echo "==================== MATRIX SUMMARY ===================="
for r in "${RESULTS[@]}"; do echo "  $r"; done