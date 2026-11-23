#!/usr/bin/env perl
# Quality choice on Mac and Windows: lets the player's quality picker hold an adaptive stream
# (one mpv video track per rendition) to a resolution, like the Android apps.
# Adds a property setter to the native mpv bridges and the two player-controller methods that use it.
use strict;
use warnings;

my $root = shift or die "usage: patch-quality-choice.pl <desktop checkout>\n";
my $src = "$root/composeApp/src/desktopMain";
# Note: resources live at $src/resources.

sub patch_file {
    my ($path, $marker, $edit) = @_;
    open(my $in, '<:raw', $path) or die "cannot read $path: $!\n";
    local $/;
    my $text = <$in>;
    close $in;
    return if index($text, $marker) >= 0;    # already patched
    my $eol = $text =~ /\r\n/ ? "\r\n" : "\n";
    $text = $edit->($text, $eol);
    index($text, $marker) >= 0 or die "quality choice patch did not apply to $path\n";
    open(my $out, '>:raw', $path) or die "cannot write $path: $!\n";
    print $out $text;
    close $out;
}

sub insert_before {
    my ($text, $anchor, $insert) = @_;
    my $at = index($text, $anchor);
    die "anchor not found: $anchor\n" if $at < 0;
    die "anchor not unique: $anchor\n" if index($text, $anchor, $at + 1) >= 0;
    return substr($text, 0, $at) . $insert . substr($text, $at);
}

sub lines { my ($eol, @l) = @_; return join($eol, @l) . $eol; }

# Kotlin: JNI declaration.
patch_file("$src/kotlin/com/nuvio/app/features/player/desktop/NativePlayerBridge.kt", 'setStringPropertyMeowio', sub {
    my ($t, $e) = @_;
    insert_before($t, '    external fun selectAudioTrack(handle: Long, trackId: Int)',
        lines($e, '    external fun setStringPropertyMeowio(handle: Long, name: String, value: String)'));
});

# Kotlin: the controller lists renditions and selects one.
patch_file("$src/kotlin/com/nuvio/app/features/player/desktop/NativePlayerController.kt", 'mpvVideoTrackForCap', sub {
    my ($t, $e) = @_;
    $t = insert_before($t, '    override fun getAudioTracks(): List<AudioTrack> =', lines($e,
        '    override fun getVideoQualityHeights(): List<Int> {',
        '        val current = handle.takeIf { it != 0L } ?: return emptyList()',
        '        return com.nuvio.app.features.player.mpvVideoQualityHeights { name ->',
        '            NativePlayerBridge.stringProperty(current, name).takeIf { it.isNotBlank() }',
        '        }',
        '    }',
        '',
        '    override fun setVideoQualityCap(maxHeight: Int?) {',
        '        val current = handle.takeIf { it != 0L } ?: return',
        '        val vid = com.nuvio.app.features.player.mpvVideoTrackForCap(maxHeight) { name ->',
        '            NativePlayerBridge.stringProperty(current, name).takeIf { it.isNotBlank() }',
        '        }',
        '        log.d { "setVideoQualityCap maxHeight=$maxHeight vid=$vid handle=$current" }',
        '        NativePlayerBridge.setStringPropertyMeowio(current, "vid", vid)',
        '    }',
        ''));
    $t;
});

# Kotlin: the controls page gets the quality choice with the rest of its state.
patch_file("$src/kotlin/com/nuvio/app/features/player/desktop/NativePlayerController.kt", 'appendJsonField("videoQualityHeights"', sub {
    my ($t, $e) = @_;
    insert_before($t, '        appendJsonField("noAudioTracksLabel", noAudioTracksLabel)', lines($e,
        '        appendJsonField("videoQualityHeights", videoQualityHeights)',
        "        append(',')",
        '        appendJsonField("videoQualityCap", videoQualityCap)',
        "        append(',')",
        '        appendJsonField("videoQualityLabel", videoQualityLabel)',
        "        append(',')",
        '        appendJsonField("videoQualityAutoLabel", videoQualityAutoLabel)',
        "        append(',')"));
});

# Controls page: ask for the renditions when the audio panel opens and list them above the audio tracks.
patch_file("$src/resources/player-ui/controls.js", 'renderVideoQualityChoice', sub {
    my ($t, $e) = @_;
    $t = insert_before($t, '  if (modal === "subtitles") {' . $e . '    resetSubtitleSelectionState();', lines($e,
        '  if (modal === "audio") send("refreshVideoQualities");'));
    $t = insert_before($t, '  const tracks = normalizeTracks(state.audioTracks);' . $e . '  if (tracks.length === 0) {' . $e . '    appendEmptyTrackState(audioTrackList',
        lines($e, '  renderVideoQualityChoice(audioTrackList);'));
    $t . lines($e, '',
        'function renderVideoQualityChoice(container) {',
        '  const heights = String(state.videoQualityHeights || "")',
        '    .split(",")',
        '    .map(value => Number(value))',
        '    .filter(value => Number.isFinite(value) && value > 0);',
        '  if (heights.length < 2) return;',
        '  const current = Number(state.videoQualityCap);',
        '  const selected = Number.isFinite(current) && current > 0 ? current : -1;',
        '  const heading = document.createElement("div");',
        '  heading.className = "audio-track-language";',
        '  heading.style.padding = "4px 12px 6px";',
        '  heading.textContent = state.videoQualityLabel || "Video quality";',
        '  container.appendChild(heading);',
        '  [-1, ...heights].forEach(height => {',
        '    const row = document.createElement("button");',
        '    row.type = "button";',
        '    row.className = `track-row audio-track-row${height === selected ? " selected" : ""}`;',
        '    row.addEventListener("click", event => {',
        '      event.stopPropagation();',
        '      state.videoQualityCap = height;',
        '      send("videoQualityCap", height);',
        '      window.setTimeout(closePlayerModal, 120);',
        '    });',
        '    const copy = document.createElement("span");',
        '    copy.className = "audio-track-copy";',
        '    const name = document.createElement("span");',
        '    name.className = "audio-track-name";',
        '    name.textContent = height > 0 ? `${height}p` : (state.videoQualityAutoLabel || "Auto");',
        '    copy.appendChild(name);',
        '    row.appendChild(copy);',
        '    row.appendChild(buildCheckIcon());',
        '    container.appendChild(row);',
        '  });',
        '  const divider = document.createElement("div");',
        '  divider.style.height = "10px";',
        '  container.appendChild(divider);',
        '}');
});

# Windows bridge.
patch_file("$src/native/windows/player_bridge.cpp", 'setStringPropertyMeowio', sub {
    my ($t, $e) = @_;
    $t = insert_before($t, '    void setAudioLanguages(const std::string &languages) {', lines($e,
        '    void setStringPropertyMeowio(const std::string &name, const std::string &value) {',
        '        std::lock_guard<std::mutex> lock(mpvMutex);',
        '        if (!mpv) return;',
        '        mpvApi().setPropertyString(mpv, name.c_str(), value.c_str());',
        '    }',
        ''));
    $t . lines($e, '',
        'extern "C" JNIEXPORT void JNICALL',
        'Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setStringPropertyMeowio(JNIEnv *env, jobject, jlong handle, jstring name, jstring value) {',
        '    auto player = playerFromHandle(handle);',
        '    if (player) player->setStringPropertyMeowio(jstringToUtf8(env, name), jstringToUtf8(env, value));',
        '}');
});

# Mac bridge.
patch_file("$src/native/macos/player_bridge.mm", 'setStringPropertyMeowio', sub {
    my ($t, $e) = @_;
    $t = insert_before($t, '- (void)setAudioLanguages:(NSString *)languages;',
        lines($e, '- (void)setStringPropertyMeowio:(NSString *)name value:(NSString *)value;'));
    $t = insert_before($t, '- (void)setAudioLanguages:(NSString *)languages {', lines($e,
        '- (void)setStringPropertyMeowio:(NSString *)name value:(NSString *)value {',
        '    if (!_mpv) return;',
        '    mpv_set_property_string(_mpv, name.UTF8String, value.UTF8String);',
        '}',
        ''));
    $t . lines($e, '',
        'extern "C" JNIEXPORT void JNICALL',
        'Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setStringPropertyMeowio(',
        '    JNIEnv *env,',
        '    jobject /* bridge */,',
        '    jlong handle,',
        '    jstring name,',
        '    jstring value',
        ') {',
        '    if (handle == 0) return;',
        '    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;',
        '    NSString *propertyName = [NSString stringWithUTF8String:jstringToString(env, name).c_str()] ?: @"";',
        '    NSString *propertyValue = [NSString stringWithUTF8String:jstringToString(env, value).c_str()] ?: @"";',
        '    runOnMainAsync(^{',
        '        [player setStringPropertyMeowio:propertyName value:propertyValue];',
        '    });',
        '}');
});

# Linux bridge (not shipped, kept in step so the JNI symbol exists everywhere).
patch_file("$src/native/linux/player_bridge.cpp", 'setStringPropertyMeowio', sub {
    my ($t, $e) = @_;
    $t . lines($e, '',
        'extern "C" JNIEXPORT void JNICALL',
        'Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setStringPropertyMeowio(JNIEnv *env, jobject, jlong handle, jstring name, jstring value) {',
        '    Player *p = asPlayer(handle);',
        '    if (!p || !p->mpv) return;',
        '    std::string n = jstringToUtf8(env, name);',
        '    std::string v = jstringToUtf8(env, value);',
        '    mpv_set_property_string(p->mpv, n.c_str(), v.c_str());',
        '}');
});

print "Quality choice patch applied\n";
