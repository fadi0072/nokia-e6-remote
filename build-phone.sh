#!/bin/bash
# Builds dist/E6Remote.jar + dist/E6Remote.jad for the Nokia E6 (Java ME, CLDC 1.1 / MIDP 2.0).
set -euo pipefail
cd "$(dirname "$0")"
TOOLS="$PWD/tools"
[ -x "$TOOLS/jdk8/Contents/Home/bin/javac" ] || ./setup.sh
JAVA_HOME="$TOOLS/jdk8/Contents/Home"
API="$TOOLS/cldcapi11.jar:$TOOLS/midpapi20.jar"
OUT=build/phone
rm -rf "$OUT" && mkdir -p "$OUT/classes" dist

"$JAVA_HOME/bin/javac" -source 1.3 -target 1.3 -nowarn -Xlint:-options \
  -bootclasspath "$API" -d "$OUT/classes" phone/src/*.java
cp phone/res/* "$OUT/classes/"
"$JAVA_HOME/bin/jar" cfm "$OUT/raw.jar" phone/MANIFEST.MF -C "$OUT/classes" .

# ProGuard preverifies the classes for CLDC (required by the phone's VM) and shrinks the jar.
"$JAVA_HOME/bin/java" -jar "$TOOLS/proguard/lib/proguard.jar" \
  -injars "$OUT/raw.jar" -outjars dist/E6Remote.jar \
  -libraryjars "$TOOLS/cldcapi11.jar" -libraryjars "$TOOLS/midpapi20.jar" \
  -microedition -dontobfuscate -dontoptimize -dontnote -dontwarn \
  -keep 'public class * extends javax.microedition.midlet.MIDlet' > "$OUT/proguard.log"

SIZE=$(stat -f%z dist/E6Remote.jar)
{ cat phone/MANIFEST.MF; echo "MIDlet-Jar-URL: E6Remote.jar"; echo "MIDlet-Jar-Size: $SIZE"; } > dist/E6Remote.jad
echo "Built dist/E6Remote.jar ($SIZE bytes) and dist/E6Remote.jad"
