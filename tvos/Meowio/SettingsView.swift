import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var profiles: ProfileStore
    @EnvironmentObject var account: AccountStore
    @EnvironmentObject var plugins: PluginStore
    @EnvironmentObject var trakt: TraktStore
    @EnvironmentObject var simkl: SimklStore
    @EnvironmentObject var mdblist: MDBListStore
    @EnvironmentObject var servers: MediaServerStore
    @EnvironmentObject var party: PartyRepository
    @StateObject private var updates = UpdateChecker()

    @State private var category: SettingsCategory = .account
    @FocusState private var railFocus: SettingsCategory?

    var body: some View {
        NavigationStack {
            // Android TV's settings: a slim rail of categories on the left, the open category on the right
            // under a large title and subtitle, its rows grouped on a rounded card.
            HStack(alignment: .top, spacing: 40) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(SettingsCategory.groups.enumerated()), id: \.offset) { i, group in
                            if i > 0 { Divider().padding(.vertical, 12).padding(.horizontal, 30) }
                            ForEach(group) { c in
                                Button { category = c } label: {
                                    HStack(spacing: 22) {
                                        Image(systemName: c.icon)
                                            .font(.system(size: 26, weight: .semibold))
                                            .frame(width: 36)
                                        Text(c.title).lineLimit(1)
                                    }
                                }
                                .buttonStyle(RailButtonStyle(selected: c == category, accent: settings.theme.accent))
                                .focused($railFocus, equals: c)
                            }
                        }
                    }
                    .padding(.vertical, 8)
                }
                .scrollClipDisabled()
                .frame(width: 500)
                // Moving along the rail opens each category, like Android TV.
                .onChange(of: railFocus) { _, c in if let c { category = c } }
                .padding(.leading, 80)
                .padding(.top, 120)
                VStack(alignment: .leading, spacing: 10) {
                    Text(category.pageTitle)
                        .font(.system(size: 54, weight: .medium))
                    Text(category.subtitle)
                        .font(.system(size: 26))
                        .foregroundStyle(.secondary)
                        .padding(.bottom, 16)
                    List {
                        switch category {
                        case .account: accountSection
                        case .profiles:
                            profilesSection
                            statsSection
                            aiSection
                        case .party: partySection
                        case .appearance: appearanceSection
                        case .layout: layoutSection
                        case .content:
                            contentSection
                            liveSection
                        case .playback: playbackSection
                        case .integrations: integrationsSection
                        case .tracking: trackingSection
                        case .advanced: advancedSection
                        case .about: aboutSection
                        }
                    }
                    // Each choice reads as a row with its current value, like Android TV, rather than a segmented bar.
                    .pickerStyle(.navigationLink)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 32))
                }
                .padding(.top, 110)
                .padding(.trailing, 70)
                .padding(.bottom, 40)
            }
            .background(settings.theme.background.ignoresSafeArea())
        }
        .task { if case .idle = updates.state { await updates.check() } }
    }

    @ViewBuilder private var accountSection: some View {
        // Android TV's account card: who is signed in, what this profile holds, and the way out.
        Section {
            if account.signedIn {
                HStack(spacing: 18) {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(settings.theme.accent)
                    Text("Signed in").foregroundStyle(.secondary)
                    Text(account.email ?? "Nuvio account")
                }
                Text("Your add-ons, library, progress and watched marks sync with this account when Meowio opens and after you sign in.")
                    .font(.system(size: 22)).foregroundStyle(.secondary)
                HStack(spacing: 0) {
                    Text(profiles.active?.name ?? "Profile").frame(maxWidth: .infinity, alignment: .leading)
                    AccountCount(value: settings.addons.count, label: "addons")
                    AccountCount(value: plugins.scrapers.count, label: "plugins")
                    AccountCount(value: library.saved.count, label: "library")
                    AccountCount(value: library.records.filter { !$0.completed && $0.seconds > 0 }.count, label: "progress")
                    AccountCount(value: library.records.filter(\.completed).count, label: "watched")
                }
                Button(account.busy ? "Syncing\u{2026}" : "Sync now") {
                    guard let p = profiles.active else { return }
                    Task { await account.sync(profile: p, settings: settings, library: library) }
                }
                .disabled(account.busy)
                NavigationLink { AccountSettingsView() } label: {
                    Label("Sign out or switch account", systemImage: "rectangle.portrait.and.arrow.right")
                        .foregroundStyle(.red)
                }
            } else {
                NavigationLink { AccountSettingsView() } label: {
                    SettingsRow(title: "Sign in to Nuvio",
                                detail: AccountStore.available ? "Bring your add-ons, library and progress from your other devices"
                                    : "Accounts are not available in this build")
                }
            }
        }
    }

    @ViewBuilder private var profilesSection: some View {
        Section {
            NavigationLink { ProfilesSettingsView() } label: {
                HStack(spacing: 20) {
                    if let p = profiles.active { ProfileAvatar(profile: p, size: 50) }
                    Text("Manage Profiles")
                    Spacer()
                    Text(profiles.active?.name ?? "").foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder private var partySection: some View {
        Section("Watch party") {
            NavigationLink { PartySettingsView() } label: {
                HStack {
                    Text("Watch party")
                    Spacer()
                    Text(party.party.map { "In party \($0.formattedCode)" } ?? "Join or host").foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder private var appearanceSection: some View {
        Section("Appearance") {
            Picker("Theme", selection: $settings.theme) {
                ForEach(MeowioTheme.allCases) { Text($0.title).tag($0) }
            }
            Toggle("Show Live TV", isOn: $settings.liveTabVisible)
            if settings.theme == .custom {
                NavigationLink { CustomThemeView() } label: {
                    HStack {
                        Text("Custom colours")
                        Spacer()
                        Circle().fill(settings.theme.accent).frame(width: 30, height: 30)
                        Circle().fill(settings.theme.background).frame(width: 30, height: 30)
                            .overlay(Circle().stroke(.white.opacity(0.4), lineWidth: 1))
                    }
                }
            }
        }
        Section {
            Picker("Start after", selection: $settings.ambientMinutes) {
                Text("Off").tag(0)
                ForEach([1, 3, 5, 10, 15], id: \.self) { Text("\($0) min").tag($0) }
            }
        } header: {
            Text("Ambient mode")
        } footer: {
            Text("When Home is left alone, Meowio shows a slideshow of backdrops from your catalogs. Any button brings you back.")
        }
    }

    @ViewBuilder private var layoutSection: some View {
        Section("Home layout") {
            Picker("Layout", selection: $settings.homeLayout) {
                ForEach(HomeLayout.allCases) { Text($0.title).tag($0) }
            }
            Toggle("Landscape posters", isOn: $settings.landscapePosters)
            Toggle("Show hero section", isOn: $settings.showHero)
            Toggle("Show Continue Watching", isOn: $settings.showContinueWatching)
            Toggle("Show poster labels", isOn: $settings.posterLabels)
            Toggle("Hide unreleased content", isOn: $settings.hideUnreleased)
        }
        Section {
            NavigationLink("Edit collections") { CollectionEditorView() }
        }

    }

    @ViewBuilder private var contentSection: some View {
        // Android TV's three rows, each opening its own page.
        Section {
            NavigationLink { AddonsSettingsView() } label: {
                SettingsIconRow(icon: "puzzlepiece.extension.fill", title: "Addons",
                                detail: "Manage add-ons, catalog order, and collections", value: "\(settings.addons.count)")
            }
            NavigationLink { MediaServersSettingsView() } label: {
                SettingsIconRow(icon: "server.rack", title: "Media servers",
                                detail: "Connect Jellyfin or Emby to browse and play your own library.",
                                value: servers.hasEnabled ? "\(servers.enabled.count) connected" : nil)
            }
            NavigationLink { PluginsSettingsView() } label: {
                SettingsIconRow(icon: "powerplug.fill", title: "Plugins", detail: "Manage repositories and stream providers",
                                value: "\(plugins.scrapers.filter(\.enabled).count) on")
            }
        }
    }

    @ViewBuilder private var playbackSection: some View {
        Section("Playback") {
            Picker("Source selection", selection: $settings.streamMode) {
                ForEach(StreamMode.allCases) { Text($0.title).tag($0) }
            }
            Toggle("Play next episode automatically", isOn: $settings.autoNext)
            Toggle("Prefer the same release for every episode", isOn: $settings.sameRelease)
            Toggle("Find the next episode's sources early", isOn: $settings.preloadNext)
            Toggle("Switch sources silently when one fails", isOn: $settings.failoverEnabled)
            Picker("Most source switches per title", selection: $settings.maxSwitches) {
                ForEach([2, 4, 6, 10, 15], id: \.self) { Text("\($0)").tag($0) }
            }
            Picker("Wait for a source to start", selection: $settings.startupTimeout) {
                ForEach([10, 15, 20, 30, 45], id: \.self) { Text("\($0) seconds").tag($0) }
            }
        }

        Section("Player") {
            Toggle("Show Skip Intro on intro and recap chapters", isOn: $settings.skipIntro)
            Toggle("Show Next Episode near the end", isOn: $settings.upNextCard)
            Toggle("Play trailers behind the Home header", isOn: $settings.heroTrailers)
            Picker("Audio language", selection: $settings.audioLanguage) {
                Text("File default").tag("")
                ForEach(Languages.all, id: \.code) { Text($0.name).tag($0.code) }
            }
            Toggle("Turn subtitles on", isOn: $settings.subtitlesOn)
            Picker("Subtitle language", selection: $settings.subtitleLanguage) {
                Text("Apple TV language").tag("")
                ForEach(Languages.all, id: \.code) { Text($0.name).tag($0.code) }
            }
            Toggle(isOn: $settings.autoSubtitleSync) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Automatic subtitle sync")
                    Text("Off by default. When you turn on an add-on subtitle, its timing is lined up with the subtitles built into the video. Only confident matches are applied, and subtitles are never turned on for you.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            NavigationLink("Subtitle style") { SubtitleStyleView() }
        }
    }

    @ViewBuilder private var integrationsSection: some View {
        Section {
            NavigationLink { DebridSettingsView() } label: {
                SettingsIconRow(icon: "icloud.and.arrow.down.fill", title: "Connected Services",
                                detail: "Debrid accounts that turn torrent results into direct links",
                                value: settings.debridService == .none ? "Off" : settings.debridService.title)
            }
            NavigationLink { TMDBSettingsView() } label: {
                SettingsIconRow(icon: "film.stack", title: "TMDB", detail: "Metadata enrichment, cast pages, list folders and trailers",
                                value: settings.tmdbKey.isEmpty ? "Not set" : "Key set")
            }
            NavigationLink { MDBListSettingsView() } label: {
                SettingsIconRow(icon: "star.square.fill", title: "MDBList Ratings", detail: "External ratings providers",
                                value: mdblist.connected ? "Connected" : nil)
            }
        }
    }

    @ViewBuilder private var trackingSection: some View {
        Section("Accounts") {
            // Tracking cards are always shown. Without sign-in keys in this build they read "Not set up yet".
            NavigationLink { TraktSettingsView() } label: {
                SettingsIconRow(icon: "checkmark.seal.fill", title: "Trakt",
                                detail: "Sync your watchlist, watch progress, continue watching, scrobbles, and personal lists with Trakt.",
                                value: trakt.connected ? (trakt.username ?? "Connected") : (TraktStore.configured ? "Not connected" : "Not set up yet"))
            }
            NavigationLink { SimklSettingsView() } label: {
                SettingsIconRow(icon: "s.square.fill", title: "Simkl",
                                detail: "Connect Simkl to sync lists, watched history, playback progress, and scrobbles.",
                                value: simkl.connected ? (simkl.username ?? "Connected") : (SimklStore.configured ? "Not connected" : "Not set up yet"))
            }
            NavigationLink { MDBListSettingsView() } label: {
                SettingsIconRow(icon: "m.square.fill", title: "MDBList",
                                detail: "Sync watched history and playback progress across devices",
                                value: mdblist.connected ? (mdblist.username ?? "Connected") : "Not connected")
            }
        }
    }

    @ViewBuilder private var liveSection: some View {
        Section {
            NavigationLink { LiveSettingsView() } label: {
                SettingsIconRow(icon: "antenna.radiowaves.left.and.right", title: "Live TV playlists & VPN",
                                detail: "Your own M3U playlists or Xtream logins, and a VPN used only by Live TV",
                                value: settings.liveSources.isEmpty ? "Free channels" : "\(settings.liveSources.count)")
            }
        }
    }

    @ViewBuilder private var advancedSection: some View {
        Section("Backup and restore") {
            NavigationLink { BackupView() } label: {
                SettingsRow(title: "Back up or restore settings", detail: "Move your settings, add-ons and library to another device")
            }
        }
    }

    @ViewBuilder private var statsSection: some View {
        Section {
            NavigationLink { InsightsView() } label: {
                SettingsRow(title: "Your watch stats", detail: "Watch time, streaks and your most-watched shows")
            }
            NavigationLink { AiAssistantView() } label: {
                SettingsRow(title: "AI assistant", detail: "Describe a mood and get ideas for what to watch")
            }
        }
    }

    @ViewBuilder private var aiSection: some View {
        Section("AI assistant key (stays on this device)") {
            SecureField("Anthropic API key", text: $settings.aiKey)
            TextField("Model (optional)", text: $settings.aiModel)
        }
    }

    @ViewBuilder private var aboutSection: some View {
        Section {
            VStack(spacing: 12) {
                Text("Meowio")
                    .font(.system(size: 56, weight: .bold))
                    .foregroundStyle(settings.theme.accent)
                Text("Made with \u{2764}\u{FE0F} by Meowio team").font(.system(size: 22)).foregroundStyle(.secondary)
                Text("Version \(UpdateChecker.currentVersion)").font(.system(size: 22)).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 20)
            .focusable(false)
        }
        Section("Updates") {
            switch updates.state {
            case .idle, .checking:
                Text("Checking for updates…").foregroundStyle(.secondary)
            case .upToDate:
                Text("You have the latest version.").foregroundStyle(.secondary)
            case .available(let tag):
                UpdateCard(tag: tag, url: updates.releaseURL, notes: updates.notes)
            case .failed:
                Text("Could not check for updates.").foregroundStyle(.secondary)
            }
            Button("Check for updates") { Task { await updates.check() } }
            Text("MKV and other formats play through the built-in mpv engine. TV apps do not offer downloads. Surround sound passes through Apple TV's own audio settings.")
                .font(.caption).foregroundStyle(.secondary)
        }

    }
}

/// Settings categories, in Android TV's order and groups.
enum SettingsCategory: String, CaseIterable, Identifiable {
    case account, profiles, party, appearance, layout, content, playback, integrations, tracking, advanced, about
    var id: String { rawValue }

    static let groups: [[SettingsCategory]] = [
        [.account, .profiles, .party],
        [.appearance, .layout],
        [.content, .playback],
        [.integrations, .tracking],
        [.advanced, .about]
    ]

    var title: String {
        switch self {
        case .account: "Account"
        case .profiles: "Profiles"
        case .party: "Watch party"
        case .appearance: "Appearance"
        case .layout: "Layout"
        case .content: "Content & Discovery"
        case .playback: "Playback"
        case .integrations: "Integrations"
        case .tracking: "Tracking"
        case .advanced: "Advanced"
        case .about: "About"
        }
    }
    /// The heading over the open category, as Android TV words it.
    var pageTitle: String {
        switch self {
        case .layout: "Layout Settings"
        case .playback: "Playback Settings"
        default: title
        }
    }
    var subtitle: String {
        switch self {
        case .account: "Account and sync status"
        case .profiles: "Manage user profiles for this account"
        case .party: "Watch together with friends using a party code"
        case .appearance: "Choose your color theme and ambient mode"
        case .layout: "Adjust home layout, content visibility, and poster behavior"
        case .content: "Add-ons, plugins, catalogs, Live TV and discovery sources"
        case .playback: "Configure video playback and subtitle options"
        case .integrations: "Manage available integrations"
        case .tracking: "Connect tracking providers and choose which one powers your Library and Continue Watching."
        case .advanced: "Backup, restore and moving settings between devices"
        case .about: "App information and updates"
        }
    }
    var icon: String {
        switch self {
        case .account: "person.fill"
        case .profiles: "person.2.fill"
        case .party: "person.3.fill"
        case .appearance: "paintpalette.fill"
        case .layout: "square.grid.2x2"
        case .content: "safari.fill"
        case .playback: "play.fill"
        case .integrations: "link"
        case .tracking: "arrow.triangle.2.circlepath"
        case .advanced: "wrench.fill"
        case .about: "info.circle.fill"
        }
    }
}

/// A settings rail item: filled when it is the open category, ringed when focused.
struct RailButtonStyle: ButtonStyle {
    var selected: Bool
    var accent: Color = .pink
    func makeBody(configuration: Configuration) -> some View { StyledBody(configuration: configuration, selected: selected, accent: accent) }
    private struct StyledBody: View {
        let configuration: ButtonStyleConfiguration
        let selected: Bool
        let accent: Color
        @Environment(\.isFocused) private var focused
        var body: some View {
            // Android TV: the open category sits on a soft grey pill with an accent bar on its left edge.
            configuration.label
                .font(.system(size: 30, weight: focused || selected ? .medium : .regular))
                .foregroundStyle(focused || selected ? .white : .white.opacity(0.65))
                .padding(.leading, 44)
                .padding(.trailing, 20)
                .frame(maxWidth: .infinity, minHeight: 84, alignment: .leading)
                .background(focused || selected ? AnyShapeStyle(.white.opacity(focused ? 0.2 : 0.11)) : AnyShapeStyle(.clear),
                            in: RoundedRectangle(cornerRadius: 18))
                .overlay(alignment: .leading) {
                    if selected {
                        Capsule().fill(accent).frame(width: 5, height: 30).padding(.leading, 20)
                    }
                }
                .scaleEffect(focused ? 1.03 : 1)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

struct InsightsView: View {
    @EnvironmentObject var library: LibraryStore

    private var week: [WatchRecord] {
        let cutoff = Date().addingTimeInterval(-7 * 86400)
        return library.records.filter { $0.at >= cutoff }
    }
    private func hours(_ list: [WatchRecord]) -> String {
        String(format: "%.1f h", list.reduce(0) { $0 + $1.seconds } / 3600)
    }
    private var topShows: [(String, Double)] {
        var totals: [String: Double] = [:]
        for r in library.records { totals[r.title, default: 0] += r.seconds }
        return totals.sorted { $0.value > $1.value }.prefix(8).map { ($0.key, $0.value) }
    }

    var body: some View {
        List {
            Section("Totals") {
                row("Watch time", hours(library.records))
                row("This week", hours(week))
                row("Movies finished", "\(library.records.filter { $0.completed && !$0.isEpisode }.count)")
                row("Episodes finished", "\(library.records.filter { $0.completed && $0.isEpisode }.count)")
            }
            Section("Most watched") {
                ForEach(topShows, id: \.0) { t in
                    row(t.0, String(format: "%.1f h", t.1 / 3600))
                }
                if topShows.isEmpty { Text("Nothing watched yet.") }
            }
        }
        .meowioPageTitle("Watch insights")
    }

    private func row(_ a: String, _ b: String) -> some View {
        HStack { Text(a); Spacer(); Text(b).foregroundStyle(.secondary) }
    }
}

// MARK: - AI assistant

struct AiPick: Identifiable, Hashable {
    let id = UUID()
    var title: String
    var year: Int?
    var isSeries: Bool
    var reason: String
}

enum AiClient {
    static let endpoint = "https://api.anthropic.com/v1/messages"
    static let defaultModel = "claude-opus-5-5"

    enum Failure: Error { case noKey, badKey, rateLimited, declined, network, unexpected }

    private static let system = "You help someone decide what to watch in a streaming app. Suggest up to 8 real, released movies or TV series that fit their request, best match first. Use each title's most common English name so it can be found by search, and give one short sentence on why it fits. Treat the titles they have watched or saved as hints about their taste. If the request is not about finding something to watch, return no picks."

    static func ask(prompt: String, key: String, model: String, watched: [String], saved: [String]) async throws -> [AiPick] {
        let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty else { throw Failure.noKey }
        var user = "Request: " + String(prompt.trimmingCharacters(in: .whitespacesAndNewlines).prefix(600))
        if !watched.isEmpty { user += "\n\nRecently watched (do not suggest these): " + watched.joined(separator: "; ") }
        let extra = saved.filter { !watched.contains($0) }
        if !extra.isEmpty { user += "\n\nSaved to their library: " + extra.joined(separator: "; ") }

        let schema: [String: Any] = [
            "type": "object",
            "properties": ["picks": ["type": "array", "items": [
                "type": "object",
                "properties": [
                    "title": ["type": "string"], "year": ["type": "integer"],
                    "type": ["type": "string", "enum": ["movie", "series"]], "reason": ["type": "string"]
                ],
                "required": ["title", "year", "type", "reason"],
                "additionalProperties": false
            ] as [String: Any]]],
            "required": ["picks"],
            "additionalProperties": false
        ]
        let body: [String: Any] = [
            "model": model.trimmingCharacters(in: .whitespaces).isEmpty ? defaultModel : model.trimmingCharacters(in: .whitespaces),
            "max_tokens": 8000,
            "fallbacks": "default",
            "system": system,
            "output_config": ["effort": "low", "format": ["type": "json_schema", "schema": schema]],
            "messages": [["role": "user", "content": user]]
        ]
        var request = URLRequest(url: URL(string: endpoint)!)
        request.httpMethod = "POST"
        request.timeoutInterval = 90
        request.setValue(trimmedKey, forHTTPHeaderField: "x-api-key")
        request.setValue("2026-10-21", forHTTPHeaderField: "anthropic-version")
        request.setValue("server-side-fallback-2026-07-01", forHTTPHeaderField: "anthropic-beta")
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        let data: Data
        let status: Int
        do {
            let (d, r) = try await URLSession.shared.data(for: request)
            data = d
            status = (r as? HTTPURLResponse)?.statusCode ?? 0
        } catch { throw Failure.network }
        guard (200..<300).contains(status) else {
            switch status {
            case 401, 403: throw Failure.badKey
            case 429, 529: throw Failure.rateLimited
            default: throw Failure.unexpected
            }
        }
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw Failure.unexpected }
        if (root["stop_reason"] as? String) == "refusal" { throw Failure.declined }
        let text = ((root["content"] as? [[String: Any]]) ?? [])
            .filter { ($0["type"] as? String) == "text" }
            .compactMap { $0["text"] as? String }.joined()
        guard let s = text.firstIndex(of: "{"), let e = text.lastIndex(of: "}"), s < e,
              let obj = try? JSONSerialization.jsonObject(with: Data(text[s...e].utf8)) as? [String: Any],
              let items = obj["picks"] as? [[String: Any]] else { throw Failure.unexpected }
        var seen = Set<String>()
        var picks: [AiPick] = []
        for item in items {
            guard let title = (item["title"] as? String)?.trimmingCharacters(in: .whitespaces), !title.isEmpty else { continue }
            let isSeries = (item["type"] as? String) == "series"
            guard seen.insert("\(title.lowercased())|\(isSeries)").inserted else { continue }
            picks.append(AiPick(title: title, year: item["year"] as? Int, isSeries: isSeries,
                                reason: (item["reason"] as? String) ?? ""))
        }
        if picks.isEmpty { throw Failure.unexpected }
        return Array(picks.prefix(8))
    }

    static func message(_ f: Failure) -> String {
        switch f {
        case .noKey: return "Add your Anthropic API key in Settings first."
        case .badKey: return "The API key was not accepted."
        case .rateLimited: return "The service is busy or rate limited. Try again in a moment."
        case .declined: return "The assistant declined that request."
        case .network: return "Could not reach the service."
        case .unexpected: return "The assistant gave no suggestions. Try describing what you want to watch."
        }
    }
}

struct AiAssistantView: View {
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var library: LibraryStore
    @State private var prompt = ""
    @State private var picks: [AiPick] = []
    @State private var busy = false
    @State private var error: String?
    @State private var opened: MetaItem?

    var body: some View {
        List {
            Section {
                TextField("What do you feel like watching?", text: $prompt)
                Button(busy ? "Thinking…" : "Suggest") { Task { await run() } }
                    .disabled(busy || prompt.trimmingCharacters(in: .whitespaces).isEmpty)
                if let error { Text(error).foregroundStyle(.orange) }
            }
            Section {
                ForEach(picks) { p in
                    Button { Task { await open(p) } } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("\(p.title)\(p.year.map { " (\($0))" } ?? "")").font(.headline)
                            if !p.reason.isEmpty { Text(p.reason).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
        }
        .meowioPageTitle("AI assistant")
        .navigationDestination(item: $opened) { DetailView(item: $0) }
    }

    private func run() async {
        busy = true
        error = nil
        let watched = library.continueWatching.map { $0.meta.name }
        let saved = library.saved.map(\.name)
        do {
            picks = try await AiClient.ask(prompt: prompt, key: settings.aiKey, model: settings.aiModel,
                                           watched: Array(watched.prefix(25)), saved: Array(saved.prefix(25)))
        } catch let f as AiClient.Failure {
            self.error = AiClient.message(f)
        } catch {
            self.error = AiClient.message(.unexpected)
        }
        busy = false
    }

    private func open(_ p: AiPick) async {
        let results = await AddonClient.catalog(type: p.isSeries ? "series" : "movie", search: p.title)
        if let hit = results.first(where: { $0.name.lowercased() == p.title.lowercased() }) ?? results.first {
            opened = hit
        } else {
            error = "Could not find \(p.title)."
        }
    }
}

private extension SettingsView {
}

/// Shows a short code to approve on the debrid service's site, then stores the account token as the key.
struct DebridSignInView: View {
    let service: DebridService
    @EnvironmentObject var settings: SettingsStore
    @Environment(\.dismiss) private var dismiss
    @State private var code: Debrid.DeviceCode?
    @State private var failed = false

    var body: some View {
        VStack(spacing: 30) {
            if let code {
                if let qr = QRCode.image(code.qrURL ?? code.url) {
                    Image(uiImage: qr).interpolation(.none).resizable().frame(width: 280, height: 280)
                }
                Text("Scan the code, or go to").foregroundStyle(.secondary)
                Text(code.url.replacingOccurrences(of: "https://", with: "")).font(.title2.bold())
                Text(code.userCode).font(.system(size: 72, weight: .heavy, design: .monospaced)).kerning(8)
                HStack(spacing: 14) { ProgressView(); Text("Waiting for \(service.title)…") }
            } else if failed {
                Text("Could not sign in to \(service.title). Try again, or paste an API key instead.").foregroundStyle(.orange)
            } else {
                ProgressView()
            }
        }
        .padding(60)
        .meowioPageTitle(service.title)
        .task { await run() }
    }

    private func run() async {
        guard let c = await Debrid.startSignIn(service) else { failed = true; return }
        code = c
        for _ in 0..<(600 / c.interval) {
            try? await Task.sleep(nanoseconds: UInt64(c.interval) * 1_000_000_000)
            if Task.isCancelled { return }
            do {
                if let token = try await Debrid.pollSignIn(service, c) {
                    settings.debridService = service
                    settings.debridKey = token
                    dismiss()
                    return
                }
            } catch {
                break
            }
        }
        code = nil
        failed = true
    }
}

/// Size, colours, outline and position of subtitles, like the Android TV player's subtitle settings.
struct SubtitleStyleView: View {
    @EnvironmentObject var settings: SettingsStore

    var body: some View {
        Form {
            Section {
                Text("The quick brown fox")
                    .font(.system(size: 34 * CGFloat(settings.subtitleStyle.size) / 100,
                                  weight: settings.subtitleStyle.bold ? .bold : .regular))
                    .foregroundStyle(color(settings.subtitleStyle.textColor))
                    .padding(.horizontal, 12).padding(.vertical, 4)
                    .background(color(settings.subtitleStyle.backgroundColor))
                    .shadow(color: settings.subtitleStyle.outline ? color(settings.subtitleStyle.outlineColor) : .clear, radius: 2)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
            }
            Section {
                Picker("Size", selection: $settings.subtitleStyle.size) {
                    ForEach([50, 75, 100, 125, 150, 175, 200], id: \.self) { Text("\($0)%").tag($0) }
                }
                Picker("Position", selection: $settings.subtitleStyle.verticalOffset) {
                    ForEach([-20, -10, 0, 5, 10, 20, 30, 40, 50], id: \.self) { Text($0 == 5 ? "Default" : "\($0)").tag($0) }
                }
                Toggle("Bold", isOn: $settings.subtitleStyle.bold)
                colorPicker("Text colour", $settings.subtitleStyle.textColor)
                colorPicker("Background", $settings.subtitleStyle.backgroundColor)
                Toggle("Outline", isOn: $settings.subtitleStyle.outline)
                if settings.subtitleStyle.outline { colorPicker("Outline colour", $settings.subtitleStyle.outlineColor) }
                Button("Reset to default") { settings.subtitleStyle = SubtitleStyle() }
            }
        }
        .meowioPageTitle("Subtitle style")
    }

    private func colorPicker(_ title: String, _ value: Binding<UInt32>) -> some View {
        Picker(title, selection: value) {
            ForEach(SubtitleStyle.colors, id: \.argb) { Text($0.name).tag($0.argb) }
            if !SubtitleStyle.colors.contains(where: { $0.argb == value.wrappedValue }) {
                Text(SubtitleStyle.hex(value.wrappedValue)).tag(value.wrappedValue)
            }
        }
    }

    private func color(_ argb: UInt32) -> Color {
        Color(red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
              blue: Double(argb & 0xFF) / 255, opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}


/// Content & Discovery › Addons, like Android TV: the add-on list, catalog order and collections.
struct AddonsSettingsView: View {
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var plugins: PluginStore
    @State private var newAddon = ""
    @State private var addonNames: [String: String] = [:]

    var body: some View {
        List {
        Section("Home") {
            NavigationLink { HomeCatalogsView() } label: {
                HStack {
                    Text("Home catalogs")
                    Spacer()
                    Text(settings.disabledHomeCatalogs.isEmpty ? "All shown" : "\(settings.disabledHomeCatalogs.count) hidden")
                        .foregroundStyle(.secondary)
                }
            }
        }
        Section {
            NavigationLink("Edit collections") { CollectionEditorView() }
        }
        Section("Add-ons") {
            ForEach(settings.addons, id: \.self) { base in
                HStack {
                    VStack(alignment: .leading) {
                        Text(addonNames[base] ?? base)
                        Text(base).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    Button("Remove") { settings.addons.removeAll { $0 == base } }
                }
            }
            TextField("Add-on link (https://… or stremio://…)", text: $newAddon)
            Button("Add add-on") {
                settings.addAddon(newAddon)
                newAddon = ""
            }
        }


        if !settings.addons.isEmpty {
            Section("Pinned sources (tried first at the same quality)") {
                ForEach(Array(addonNames.values).sorted(), id: \.self) { name in
                    Toggle(name, isOn: Binding(
                        get: { settings.pinnedSources.contains(name) },
                        set: { _ in settings.togglePinned(name) }))
                }
            }
        }
        }
        .meowioPageTitle("Addons")
        .task(id: settings.addons) {
            var map: [String: String] = [:]
            for base in settings.addons { map[base] = await AddonClient.addonName(for: base) }
            addonNames = map
        }
    }
}

/// Integrations › Connected Services: the debrid service and its sign-in.
struct DebridSettingsView: View {
    @EnvironmentObject var settings: SettingsStore

    var body: some View {
        List {
        Section {
            Picker("Service", selection: $settings.debridService) {
                ForEach(DebridService.allCases) { Text($0.title).tag($0) }
            }
            if settings.debridService != .none {
                if Debrid.canSignIn(settings.debridService) {
                    NavigationLink { DebridSignInView(service: settings.debridService) } label: {
                        LabeledContent("Sign in with a code", value: settings.debridReady ? "Connected" : "")
                    }
                }
                SecureField("\(settings.debridService.title) API key", text: $settings.debridKey)
            }
        } header: {
            Text("Debrid (plays torrent results)")
        } footer: {
            Text("Apple TV can't run torrents itself. With a debrid key, torrent results from your add-ons are turned into direct links, and uncached ones are skipped so playback never waits on a download. Signed in to your Nuvio account, the key comes from your phone or Android TV automatically. It is never in backups.")
        }

        }
        .pickerStyle(.navigationLink)
        .meowioPageTitle("Connected Services")
    }
}

/// Integrations › TMDB: the optional key for cast pages, list folders and trailers.
struct TMDBSettingsView: View {
    @EnvironmentObject var settings: SettingsStore

    var body: some View {
        List {
            Section {
                SecureField("TMDB API key (optional)", text: $settings.tmdbKey)
            } footer: {
                Text("Cast pages, folders built from TMDB lists, and trailers for titles without one in their metadata need a TMDB key. It stays on this device and is never in backups.")
            }
        }
        .meowioPageTitle("TMDB")
    }
}

/// A settings row with an icon, a grey description and the current value, like Android TV's.
struct SettingsIconRow: View {
    var icon: String
    var title: String
    var detail: String? = nil
    var value: String? = nil
    var body: some View {
        HStack(spacing: 26) {
            Image(systemName: icon).font(.system(size: 30)).frame(width: 44)
            SettingsRow(title: title, detail: detail, value: value)
        }
    }
}

/// Live TV playlists and the Live TV-only VPN, from Settings or the Live TV page's "Playlists & VPN".
struct LiveSettingsView: View {
    @EnvironmentObject var settings: SettingsStore

    var body: some View {
        List {
        Section("Live TV playlists (none = free built-in channels)") {
            ForEach(settings.liveSources) { source in
                NavigationLink {
                    LiveSourceEditor(original: source)
                } label: {
                    HStack {
                        Text(source.label)
                        Spacer()
                        Text(source.enabled ? (source.type == .xtream ? "Xtream" : "M3U") : "Off")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            NavigationLink("Add a playlist or login") { LiveSourceEditor(original: nil) }
        }

        Section("Live TV VPN (only Live TV uses it, never debrid)") {
            Toggle("Use for Live TV", isOn: $settings.liveVpnEnabled)
            TextField("Proxy, such as http://user:pass@host:8080", text: $settings.liveVpnProxy)
            Text(liveVpnStatus).font(.caption).foregroundStyle(.secondary)
        }
            }
        .meowioPageTitle("Playlists & VPN")
    }

    var liveVpnStatus: String {
        if !settings.liveVpnEnabled { return "Off. Live TV connects directly." }
        if LiveVpnGuard.parseProxy(settings.liveVpnProxy) == nil { return "Enter an http:// proxy address with a port." }
        return "Switches on by itself while a channel plays and off when you leave. Debrid and other playback never use it."
    }
}

/// One number in the account card's sync table.
struct AccountCount: View {
    var value: Int
    var label: String
    var body: some View {
        VStack(spacing: 2) {
            Text("\(value)").font(.system(size: 28, weight: .semibold)).foregroundStyle(value == 0 ? .secondary : .primary)
            Text(label).font(.system(size: 18)).foregroundStyle(.secondary)
        }
        .frame(width: 130)
    }
}

/// A settings row with Android TV's grey description line under the title.
struct SettingsRow: View {
    var title: String
    var detail: String? = nil
    var value: String? = nil
    var body: some View {
        HStack(spacing: 20) {
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                if let detail { Text(detail).font(.system(size: 22)).foregroundStyle(.secondary).lineLimit(2) }
            }
            Spacer()
            if let value { Text(value).foregroundStyle(.secondary) }
        }
    }
}

/// Android TV's Catalog order screen: every Home catalog from the user's add-ons, with Up, Down and Show or Hide.
struct HomeCatalogsView: View {
    @EnvironmentObject var settings: SettingsStore
    @State private var catalogs: [AddonClient.AddonCatalog] = []
    @State private var names: [String: String] = [:]
    @State private var loading = true

    private var ordered: [AddonClient.AddonCatalog] { settings.orderedHomeCatalogs(catalogs) }

    var body: some View {
        List {
            Section {
                Text("Choose which catalogs show on Home and in what order. Remove an add-on under Add-ons to drop all of its catalogs.")
                    .font(.callout).foregroundStyle(.secondary)
                if !settings.homeCatalogOrder.isEmpty {
                    Button("Follow the add-on order") { settings.homeCatalogOrder = [] }
                }
            }
            Section("Catalogs") {
                if loading {
                    ProgressView()
                } else if catalogs.isEmpty {
                    Text("Your add-ons have no catalogs to show on Home.").foregroundStyle(.secondary)
                }
                ForEach(Array(ordered.enumerated()), id: \.element.rowId) { index, catalog in
                    let hidden = settings.disabledHomeCatalogs.contains(catalog.rowId)
                    HStack(spacing: 24) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(catalog.title).opacity(hidden ? 0.5 : 1)
                            Text(hidden ? "\(names[catalog.base] ?? catalog.base) · Hidden on Home" : (names[catalog.base] ?? catalog.base))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        Button { move(catalog, by: -1) } label: { Image(systemName: "chevron.up") }
                            .disabled(index == 0)
                        Button { move(catalog, by: 1) } label: { Image(systemName: "chevron.down") }
                            .disabled(index == ordered.count - 1)
                        Button(hidden ? "Show" : "Hide") { toggle(catalog) }
                    }
                }
            }
        }
        .meowioPageTitle("Home catalogs")
        .task(id: settings.addons) {
            loading = true
            catalogs = await AddonClient.addonCatalogs(settings.addons)
            var map: [String: String] = [:]
            for base in settings.addons { map[base] = await AddonClient.addonName(for: base) }
            names = map
            loading = false
        }
    }

    private func move(_ catalog: AddonClient.AddonCatalog, by step: Int) {
        var keys = ordered.map(\.rowId)
        guard let i = keys.firstIndex(of: catalog.rowId) else { return }
        let j = i + step
        guard keys.indices.contains(j) else { return }
        keys.swapAt(i, j)
        settings.homeCatalogOrder = keys
    }

    private func toggle(_ catalog: AddonClient.AddonCatalog) {
        if let i = settings.disabledHomeCatalogs.firstIndex(of: catalog.rowId) {
            settings.disabledHomeCatalogs.remove(at: i)
        } else {
            settings.disabledHomeCatalogs.append(catalog.rowId)
        }
    }
}


/// Android TV's custom theme editor: an accent and a background colour, from swatches or a hex code.
struct CustomThemeView: View {
    @EnvironmentObject var settings: SettingsStore

    private let accents = ["#FF5C9E", "#B073F2", "#3899F2", "#33C785", "#FAB233", "#F26B5E", "#E63835", "#38B8E8",
                           "#5468FF", "#F5F5F5", "#FF9F0A", "#64D2FF", "#BF5AF2", "#30D158", "#FFD60A", "#AC8E68"]
    private let backgrounds = ["#120A14", "#0A0D1C", "#0A1712", "#1A140A", "#1A0F14", "#0D0D0D", "#000000", "#101820"]

    var body: some View {
        List {
            Section("Accent") { swatches(accents, selection: $settings.customAccent) }
            Section("Background") { swatches(backgrounds, selection: $settings.customBackground) }
            Section("Hex codes") {
                TextField("Accent, like #FF5C9E", text: $settings.customAccent)
                TextField("Background, like #120A14", text: $settings.customBackground)
            }
        }
        .meowioPageTitle("Custom colours")
    }

    private func swatches(_ list: [String], selection: Binding<String>) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(90), spacing: 24), count: 8), spacing: 24) {
            ForEach(list, id: \.self) { hex in
                Button { selection.wrappedValue = hex } label: {
                    Circle().fill(Color(hex: hex) ?? .clear).frame(width: 70, height: 70)
                        .overlay(Circle().stroke(.white, lineWidth: selection.wrappedValue.uppercased() == hex ? 4 : 0))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 12)
    }
}

/// Android TV's page heading: a large title at the top left of the page, in the content itself.
/// tvOS's own navigation title floated over the first rows (and over the search keyboard) once they scrolled.
struct PageTitleModifier: ViewModifier {
    let title: String
    func body(content: Content) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(title)
                .font(.system(size: 54, weight: .medium))
                .lineLimit(1)
                .padding(.horizontal, 90)
                .padding(.top, 40)
            content
        }
        .toolbar(.hidden, for: .navigationBar)
    }
}

extension View {
    func meowioPageTitle(_ title: String) -> some View { modifier(PageTitleModifier(title: title)) }
}
