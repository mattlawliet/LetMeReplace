#!/usr/bin/env bash
# Builds the server half with plain javac against a Paper 26.2 server's own jar and libraries -
# one source file does not need a Gradle project. Point PAPER_SERVER at a Paper 26.2 server folder
# that has been started once (so versions/ and libraries/ exist):
#
#   PAPER_SERVER=~/my-paper-server server/build.sh
#
# The version comes from ../gradle.properties so the mod and the plugin can never drift apart.
set -euo pipefail
cd "$(dirname "$0")"
S=${PAPER_SERVER:?set PAPER_SERVER to a Paper 26.2 server folder that has been started once}
VERSION=$(grep '^mod_version' ../gradle.properties | cut -d= -f2 | tr -d ' ')
CP=$(find "$S/libraries" -name '*.jar' | tr '\n' ':')$S/versions/26.2/paper-26.2.jar
rm -rf out && mkdir -p out/classes
javac --release 25 -Xlint:-options -cp "$CP" -d out/classes $(find src -name '*.java')
sed "s/\${version}/$VERSION/" src/plugin.yml > out/classes/plugin.yml
jar cf "out/LetMeReplace-server-$VERSION.jar" -C out/classes .
echo "out/LetMeReplace-server-$VERSION.jar"
