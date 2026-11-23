#!/usr/bin/env perl
# "Are you still watching?" on Mac and Windows. The desktop player draws its controls in a web page above
# the native video, so the prompt is drawn there too: the controller sends its text with the rest of the
# controls state and the page sends back stillWatchingContinue / stillWatchingExit.
use strict;
use warnings;

my $root = shift or die "usage: patch-still-watching.pl <desktop checkout>\n";
my $src = "$root/composeApp/src/desktopMain";

sub patch_file {
    my ($path, $marker, $edit) = @_;
    open(my $in, '<:raw', $path) or die "cannot read $path: $!\n";
    local $/;
    my $text = <$in>;
    close $in;
    return if index($text, $marker) >= 0;    # already patched
    my $eol = $text =~ /\r\n/ ? "\r\n" : "\n";
    $text = $edit->($text, $eol);
    index($text, $marker) >= 0 or die "still watching patch did not apply to $path\n";
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

patch_file("$src/kotlin/com/nuvio/app/features/player/desktop/NativePlayerController.kt", 'appendJsonField("stillWatchingVisible"', sub {
    my ($t, $e) = @_;
    insert_before($t, '        appendJsonField("p2pConsentTitle", p2pConsentTitle)', lines($e,
        '        appendJsonField("stillWatchingVisible", stillWatchingVisible)',
        "        append(',')",
        '        appendJsonField("stillWatchingTitle", stillWatchingTitle)',
        "        append(',')",
        '        appendJsonField("stillWatchingMessage", stillWatchingMessage)',
        "        append(',')",
        '        appendJsonField("stillWatchingContinueLabel", stillWatchingContinueLabel)',
        "        append(',')",
        '        appendJsonField("stillWatchingExitLabel", stillWatchingExitLabel)',
        "        append(',')"));
});

patch_file("$src/resources/player-ui/controls.js", 'renderStillWatchingPrompt', sub {
    my ($t, $e) = @_;
    $t = insert_before($t, '  const preventModalAutoScrolling = activeModal === "sources" || activeModal === "episodes";',
        lines($e, '  renderStillWatchingPrompt();'));
    $t . lines($e, '',
        'function renderStillWatchingPrompt() {',
        '  let layer = document.getElementById("meowio-still-watching");',
        '  if (!state.stillWatchingVisible) {',
        '    if (layer) layer.remove();',
        '    return;',
        '  }',
        '  if (!layer) {',
        '    layer = document.createElement("div");',
        '    layer.id = "meowio-still-watching";',
        '    layer.style.cssText = "position:fixed;inset:0;z-index:2147483000;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,0.72);pointer-events:auto;";',
        '    const card = document.createElement("div");',
        '    card.style.cssText = "max-width:420px;padding:24px 28px;border-radius:24px;background:rgba(28,27,34,0.94);color:#fff;text-align:center;font-family:inherit;";',
        '    const title = document.createElement("div");',
        '    title.className = "meowio-sw-title";',
        '    title.style.cssText = "font-size:22px;font-weight:600;margin-bottom:10px;";',
        '    const message = document.createElement("div");',
        '    message.className = "meowio-sw-message";',
        '    message.style.cssText = "font-size:14px;opacity:0.7;margin-bottom:18px;";',
        '    const buttons = document.createElement("div");',
        '    buttons.style.cssText = "display:flex;gap:12px;justify-content:center;";',
        '    const makeButton = (className, primary, command) => {',
        '      const button = document.createElement("button");',
        '      button.type = "button";',
        '      button.className = className;',
        '      button.style.cssText = `border:0;border-radius:999px;padding:10px 22px;font-size:15px;font-weight:600;cursor:pointer;${primary ? "background:#fff;color:#000;" : "background:rgba(255,255,255,0.16);color:#fff;"}`;',
        '      button.addEventListener("click", event => {',
        '        event.stopPropagation();',
        '        send(command, 0);',
        '      });',
        '      return button;',
        '    };',
        '    buttons.appendChild(makeButton("meowio-sw-exit", false, "stillWatchingExit"));',
        '    buttons.appendChild(makeButton("meowio-sw-continue", true, "stillWatchingContinue"));',
        '    card.appendChild(title);',
        '    card.appendChild(message);',
        '    card.appendChild(buttons);',
        '    layer.appendChild(card);',
        '    layer.addEventListener("click", event => event.stopPropagation());',
        '    document.body.appendChild(layer);',
        '    window.setTimeout(() => layer.querySelector(".meowio-sw-continue")?.focus(), 0);',
        '  }',
        '  layer.querySelector(".meowio-sw-title").textContent = state.stillWatchingTitle || "Are you still watching?";',
        '  layer.querySelector(".meowio-sw-message").textContent = state.stillWatchingMessage || "";',
        '  layer.querySelector(".meowio-sw-exit").textContent = state.stillWatchingExitLabel || "Exit";',
        '  layer.querySelector(".meowio-sw-continue").textContent = state.stillWatchingContinueLabel || "Keep watching";',
        '}');
});

print "Still watching patch applied\n";
