import SwiftUI

@main
struct MeowioApp: App {
    @StateObject private var profiles = ProfileStore()
    @StateObject private var account = AccountStore()

    var body: some Scene {
        WindowGroup {
            AppRoot()
                .environmentObject(profiles)
                .environmentObject(account)
                .preferredColorScheme(.dark)
        }
    }
}

/// "Who's watching?" first when needed, then the app for the chosen profile.
struct AppRoot: View {
    @EnvironmentObject var profiles: ProfileStore
    @EnvironmentObject var account: AccountStore

    var body: some View {
        Group {
            if let profile = profiles.active {
                // A new identity per profile, so every store below is made fresh from that profile's storage.
                ProfileRoot(profile: profile).id(profile.id)
            } else {
                ProfilePickerView()
            }
        }
        .task { await account.pullProfiles(into: profiles) }
    }
}

/// Everything for one profile: its settings, library, sources, Live TV and Trakt link.
struct ProfileRoot: View {
    let profile: Profile
    @EnvironmentObject var account: AccountStore
    @EnvironmentObject var profiles: ProfileStore
    @StateObject private var settings: SettingsStore
    @StateObject private var library = LibraryStore()
    @StateObject private var streams = StreamStore()
    @StateObject private var live = LiveTVStore()
    @StateObject private var trakt = TraktStore()
    @StateObject private var simkl = SimklStore()
    @StateObject private var mdblist = MDBListStore()
    @StateObject private var servers = MediaServerStore()
    @StateObject private var collections = CollectionsStore()
    @StateObject private var party = PartyRepository()
    @StateObject private var plugins = PluginStore()
    @Environment(\.scenePhase) private var scenePhase

    init(profile: Profile) {
        self.profile = profile
        ProfileScope.activeId = profile.id
        _settings = StateObject(wrappedValue: SettingsStore(sharesPrimaryAddons: profile.usesPrimaryAddons))
    }

    var body: some View {
        RootView()
            .environmentObject(settings)
            .environmentObject(library)
            .environmentObject(streams)
            .environmentObject(live)
            .environmentObject(trakt)
            .environmentObject(simkl)
            .environmentObject(mdblist)
            .environmentObject(servers)
            .environmentObject(collections)
            .environmentObject(party)
            .environmentObject(plugins)
            .tint(settings.theme.accent)
            .task {
                wire()
                await syncAll()
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await syncAll() } }
            }
            .task(id: "\(settings.debridService.rawValue)|\(settings.debridKey)") {
                // A debrid key typed here reaches the account a moment after the last keystroke.
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard !Task.isCancelled, account.signedIn, !account.busy else { return }
                await account.syncCredentials(profileId: profile.id, settings: settings)
            }
            .onChange(of: settings.addons) { _, addons in
                guard !profile.usesPrimaryAddons || profile.id == 1 else { return }
                Task { await account.pushAddons(addons, profileId: profile.id) }
            }
    }

    /// Profiles set to share the main profile's plugins use profile 1's list, like Android.
    private var pluginProfile: Int { profile.usesPrimaryPlugins == true ? 1 : profile.id }

    private func syncAll() async {
        if account.signedIn {
            await account.sync(profile: profile, settings: settings, library: library)
            if let key = account.mdblistKey { await mdblist.useAccountKey(key) }
        }
        if trakt.connected { await trakt.sync(into: library) }
        if simkl.connected { await simkl.sync(into: library) }
        if mdblist.connected { await mdblist.sync(into: library) }
        if account.signedIn { await collections.sync(account: account, profileId: profile.id) }
        if let remote = await account.pullPluginRepos(profileId: pluginProfile) { await plugins.adopt(remote: remote) }
        party.configure(account: account, profiles: profiles)
        await party.refreshAvailability()
    }

    /// My List and play progress go to the account and Trakt as they change.
    private func wire() {
        let id = profile.id
        let acc = account
        let tr = trakt
        let sk = simkl
        let md = mdblist
        streams.servers = servers
        streams.plugins = plugins
        let pluginId = pluginProfile
        plugins.onReposChanged = { [weak acc] repos in Task { await acc?.pushPluginRepos(repos, profileId: pluginId) } }
        account.onSignedIn = { [profiles] in
            await acc.pullProfiles(into: profiles)
            await self.syncAll()
        }
        party.configure(account: account, profiles: profiles)
        library.onSavedChanged = { [weak acc, weak tr, weak sk, weak md] item, added in
            Task { @MainActor in
                tr?.setInWatchlist(added, meta: item)
                sk?.setInWatchlist(added, meta: item)
                md?.setInWatchlist(added, meta: item)
                if added { await acc?.pushLibrary([item], profileId: id) }
                else { await acc?.removeFromLibrary(item, profileId: id) }
            }
        }
        library.onRecordChanged = { [weak acc] record, meta in
            Task { @MainActor in
                if record.completed {
                    await acc?.pushWatched([record], profileId: id)
                } else if record.seconds > 0 {
                    await acc?.pushProgress([record], profileId: id, metaType: meta?.type)
                } else {
                    await acc?.removeWatched(record, profileId: id)
                }
            }
        }
        // Played titles reach Trakt through scrobbling. Marks made by hand go straight to its history.
        library.onManualMark = { [weak tr, weak sk, weak md] meta, videoId, watched in
            tr?.setWatched(watched, meta: meta, videoId: videoId)
            sk?.setWatched(watched, meta: meta, videoId: videoId)
            md?.setWatched(watched, meta: meta, videoId: videoId)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var streams: StreamStore
    @EnvironmentObject var servers: MediaServerStore
    @EnvironmentObject var party: PartyRepository
    /// What a watch party is playing, opened full screen after joining.
    @State private var partyRequest: PlayRequest?

    var body: some View {
        TabView {
            HomeView()
                .tabItem { Label("Home", systemImage: "house.fill") }
            SearchView()
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
            LibraryView()
                .tabItem { Label("Library", systemImage: "bookmark.fill") }
            if settings.liveTabVisible {
                LiveTVView()
                    .tabItem { Label("Live TV", systemImage: "antenna.radiowaves.left.and.right") }
            }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
        }
        .background(settings.theme.background.ignoresSafeArea())
        .onChange(of: party.launch) { _, launch in
            guard let media = launch?.media, partyRequest == nil else { return }
            Task {
                let req = await PartyLauncher.request(for: media, shared: party.sharedStream, settings: settings, streams: streams,
                                                      library: library, servers: servers)
                if let req { partyRequest = req } else { party.showToast("This device could not find \(media.displayTitle).") }
            }
        }
        .fullScreenCover(item: $partyRequest) { req in
            PlayerScreen(request: req, settings: settings)
        }
    }
}
