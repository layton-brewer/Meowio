import SwiftUI
import AVKit

struct LiveTVView: View {
    @EnvironmentObject var settings: SettingsStore
    @State private var channels: [Channel] = []
    @State private var group = "All"
    @State private var playing: Channel?
    @State private var loading = true

    static let freeTV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"

    private var groups: [String] { ["All"] + Array(Set(channels.map(\.group))).sorted() }
    private var shown: [Channel] { group == "All" ? channels : channels.filter { $0.group == group } }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading) {
                Text("Live TV").font(.largeTitle.bold()).padding(.leading, 60)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(groups, id: \.self) { g in
                            Button(g) { group = g }
                        }
                    }
                    .padding(.horizontal, 60).padding(.vertical, 10)
                }
                if loading { ProgressView().padding(60) }
                ScrollView {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 30), count: 4), spacing: 30) {
                        ForEach(shown) { ch in
                            Button { playing = ch } label: {
                                VStack {
                                    AsyncImage(url: ch.logo) { phase in
                                        if case .success(let img) = phase { img.resizable().scaledToFit() }
                                        else { Image(systemName: "tv").font(.largeTitle) }
                                    }
                                    .frame(height: 100)
                                    Text(ch.name).lineLimit(1)
                                }
                                .frame(maxWidth: .infinity)
                                .padding()
                            }
                            .buttonStyle(.card)
                        }
                    }
                    .padding(.horizontal, 60)
                }
            }
        }
        .task(id: settings.playlistURL) {
            loading = true
            let urlString = settings.playlistURL.isEmpty ? Self.freeTV : settings.playlistURL
            if let url = URL(string: urlString),
               let (data, _) = try? await URLSession.shared.data(from: url),
               let text = String(data: data, encoding: .utf8) {
                channels = AddonClient.parsePlaylist(text)
            }
            loading = false
        }
        .fullScreenCover(item: $playing) { ch in
            PlayerScreen(request: PlayRequest(
                meta: MetaItem(id: ch.id, type: "tv", name: ch.name),
                videoId: nil,
                candidates: [StreamItem(name: ch.name, title: nil, description: nil, url: ch.url.absoluteString, infoHash: nil, behaviorHints: nil)]))
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject var settings: SettingsStore
    @State private var newAddon = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Stream add-ons") {
                    ForEach(settings.addons, id: \.self) { a in
                        Text(a).lineLimit(1)
                    }
                    .onDelete { settings.addons.remove(atOffsets: $0) }
                    TextField("Paste add-on URL (manifest.json)", text: $newAddon)
                        .onSubmit {
                            settings.addAddon(newAddon)
                            newAddon = ""
                        }
                    Button("Add add-on") {
                        settings.addAddon(newAddon)
                        newAddon = ""
                    }
                }
                Section("Live TV") {
                    TextField("Custom M3U playlist URL (optional)", text: $settings.playlistURL)
                }
                Section("About") {
                    Text("Meowio picks the best working source for you and falls back automatically if one fails.")
                }
            }
        }
    }
}
