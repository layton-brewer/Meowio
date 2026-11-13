import SwiftUI

@main
struct MeowioApp: App {
    @StateObject private var settings = SettingsStore()
    @StateObject private var library = LibraryStore()
    @StateObject private var streams = StreamStore()
    @StateObject private var live = LiveTVStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(settings)
                .environmentObject(library)
                .environmentObject(streams)
                .environmentObject(live)
                .preferredColorScheme(.dark)
                .tint(settings.theme.accent)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var settings: SettingsStore

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
    }
}
