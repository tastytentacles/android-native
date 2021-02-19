#!/bin/bash

FIXUP_TUSKY="//string[@name='restart_emoji'] | \
//string[@name='about_tusky_version'] | \
//string[@name='about_powered_by_tusky'] | \
//string[@name='about_tusky_license'] | \
//string[@name='about_tusky_account'] | \
//string[@name='license_description']"
FIXUP_WEBSITE="//string[@name='about_project_site']"
FIXUP_BUGTRACKER="//string[@name='about_bug_feature_request_site']"
FIXUP_MASTODON="//string[@name='action_login'] | \
//string[@name='add_account_description'] | \
//string[@name='warning_scheduling_interval']"
FIXUP_INSTANCES="//string[@name='dialog_whats_an_instance']"

SOURCE_ROOT=$PWD

logue() {
	sed -E 's/(<.?result>|<.?results.?>)//g'
}

prologue() {
	logue | awk -v FS=$'\b' -v RS=$'\b' '{ print "<resources>"$1}'
}

epilogue() {
	logue | awk -v FS=$'\b' -v RS=$'\b' '{ print $1"</resources>"}'
}

pushd app/src/main/res/
for i in values*/strings.xml;
do
    DIRECTORY=$(dirname $i)
    
    echo $DIRECTORY
    
    mkdir -p ../../husky/res/$DIRECTORY/

    # add <resources>, some strings
    $SOURCE_ROOT/scripts/xq.py "$FIXUP_TUSKY" $i | prologue | sed -E 's|Tusky|koyu.space|g' > ../../husky/res/$DIRECTORY/husky_generated.xml
    $SOURCE_ROOT/scripts/xq.py "$FIXUP_WEBSITE" $i | logue | sed -E 's|https://tusky.app|https://koyu.space|g' >> ../../husky/res/$DIRECTORY/husky_generated.xml
    $SOURCE_ROOT/scripts/xq.py "$FIXUP_BUGTRACKER" $i | logue | sed -E 's|https://github.com/tuskyapp/Tusky/issues|https://github.com/koyuspace/android-native/issues|g' >> ../../husky/res/$DIRECTORY/husky_generated.xml
    $SOURCE_ROOT/scripts/xq.py "$FIXUP_MASTODON" $i | logue | sed -E 's|Mastodon|koyu.space|g' >> ../../husky/res/$DIRECTORY/husky_generated.xml
    $SOURCE_ROOT/scripts/xq.py "$FIXUP_INSTANCES" $i | epilogue | sed -E 's|mastodon.social|koyu.space|g' | sed -E 's|icosahedron.website|blob.cat|g' | sed -E 's|social.tchncs.de|cdrom.tokyo|g' | sed -E 's|https://instances.social|https://web.koyu.space/instance-recommendations/|g' >> ../../husky/res/$DIRECTORY/husky_generated.xml
    
done
popd
