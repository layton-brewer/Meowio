#!/bin/bash
# copy files that differ between clean desktop and dfinal into the repo overlay dir
set -e
DEST=/tmp/w/Meowio/apple/macos/overlay
rm -rf $DEST; mkdir -p $DEST
cd /tmp/w/dfinal/composeApp/src
for sub in commonMain fullCommonMain desktopMain; do
  (cd $sub && find . -type f) | while read f; do
    if [ ! -f /tmp/w/desktop/composeApp/src/$sub/$f ] || ! cmp -s $sub/$f /tmp/w/desktop/composeApp/src/$sub/$f; then
      mkdir -p $DEST/$sub/$(dirname $f); cp $sub/$f $DEST/$sub/$f
    fi
  done
done
find $DEST -type f | wc -l; du -sh $DEST
