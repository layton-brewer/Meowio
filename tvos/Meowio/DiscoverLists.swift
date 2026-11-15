import Foundation
import SwiftUI

/// One public Trakt list in the popular or trending directory.
struct TraktListInfo: Identifiable, Hashable {
    let id: Int
    let name: String
    let detail: String
}

/// Browses the most popular and trending public lists. Trakt serves these to any app with a client id, so no
/// sign-in is needed. Simkl has no public lists and MDBList's top lists need the user's own key.
@MainActor
final class DiscoverListsModel: ObservableObject {
    enum Kind: String, CaseIterable, Identifiable {
        case popular, trending
        var id: String { rawValue }
        var title: String { self == .popular ? "Most popular lists" : "Trending lists" }
    }

    @Published var kind: Kind = .popular
    @Published private(set) var lists: [TraktListInfo] = []
    @Published private(set) var loadingLists = false
    @Published private(set) var selected: TraktListInfo?
    @Published private(set) var items: [MetaItem] = []
    @Published private(set) var loadingItems = false
    @Published private(set) var message: String?

    var configured: Bool { !TraktStore.clientId.isEmpty }
    private var started = false

    func start() async {
        guard configured, !started else { return }
        started = true
        await loadLists()
    }

    func choose(_ kind: Kind) async {
        guard kind != self.kind || lists.isEmpty else { return }
        self.kind = kind
        selected = nil
        items = []
        await loadLists()
    }

    func select(_ list: TraktListInfo) async {
        selected = list
        items = []
        message = nil
        loadingItems = true
        defer { loadingItems = false }
        var found: [MetaItem] = []
        for page in 1...3 {
            guard let rows = await Self.get("/lists/\(list.id)/items/movie,show?limit=100&page=\(page)&extended=full") else {
                if found.isEmpty { message = "Could not load this list." }
                break
            }
            if selected?.id != list.id { return }
            for row in rows {
                if let m = row["movie"] as? [String: Any], let meta = TraktStore.meta(from: m, type: "movie") { found.append(meta) }
                if let s = row["show"] as? [String: Any], let meta = TraktStore.meta(from: s, type: "series") { found.append(meta) }
            }
            items = found
            if rows.count < 100 { break }
        }
        if found.isEmpty && message == nil { message = "This list is empty." }
    }

    private func loadLists() async {
        loadingLists = true
        message = nil
        defer { loadingLists = false }
        var found: [TraktListInfo] = []
        for page in 1...3 {
            guard let rows = await Self.get("/lists/\(kind.rawValue)?limit=100&page=\(page)") else {
                if found.isEmpty { message = "Could not load lists." }
                break
            }
            for row in rows {
                guard let list = row["list"] as? [String: Any],
                      let ids = list["ids"] as? [String: Any], let id = ids["trakt"] as? Int else { continue }
                let owner = (list["user"] as? [String: Any])?["username"] as? String
                var parts: [String] = []
                if let owner { parts.append(owner) }
                if let n = list["item_count"] as? Int { parts.append("\(n) titles") }
                if let likes = (row["like_count"] as? Int) ?? (list["likes"] as? Int) { parts.append("\(likes) likes") }
                found.append(TraktListInfo(id: id, name: list["name"] as? String ?? "List \(id)",
                                           detail: parts.joined(separator: " · ")))
            }
            if rows.count < 100 { break }
        }
        var seen = Set<Int>()
        lists = found.filter { seen.insert($0.id).inserted }
    }

    private static func get(_ path: String) async -> [[String: Any]]? {
        guard let url = URL(string: TraktStore.api + path) else { return nil }
        var r = URLRequest(url: url)
        r.timeoutInterval = 20
        r.setValue("2", forHTTPHeaderField: "trakt-api-version")
        r.setValue(TraktStore.clientId, forHTTPHeaderField: "trakt-api-key")
        r.setValue("Meowio", forHTTPHeaderField: "User-Agent")
        guard let result = try? await URLSession.shared.data(for: r),
              (result.1 as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return (try? JSONSerialization.jsonObject(with: result.0)) as? [[String: Any]]
    }
}

struct DiscoverListsView: View {
    @EnvironmentObject var settings: SettingsStore
    @StateObject private var model = DiscoverListsModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 30) {
                Text("Popular lists").font(.largeTitle.bold())
                HStack(spacing: 30) {
                    Menu {
                        Button { } label: { Label(model.configured ? "Trakt" : "Trakt · Not set up yet", systemImage: "checkmark") }
                        Button { } label: { Label("Simkl · No public lists", systemImage: "lock") }.disabled(true)
                        Button { } label: { Label("MDBList · Not set up yet", systemImage: "lock") }.disabled(true)
                    } label: { Label("Service: Trakt", systemImage: "square.stack") }
                    Menu {
                        ForEach(DiscoverListsModel.Kind.allCases) { kind in
                            Button { Task { await model.choose(kind) } } label: {
                                Label(kind.title, systemImage: model.kind == kind ? "checkmark" : "circle")
                            }
                        }
                    } label: { Label(model.kind.title, systemImage: "line.3.horizontal.decrease.circle") }
                    .disabled(!model.configured)
                    Menu {
                        ForEach(model.lists) { list in
                            Button { Task { await model.select(list) } } label: {
                                Text(list.detail.isEmpty ? list.name : "\(list.name) · \(list.detail)")
                            }
                        }
                    } label: { Label(model.selected?.name ?? "Choose a list (\(model.lists.count))", systemImage: "list.star") }
                    .disabled(!model.configured || model.lists.isEmpty)
                }
                if !model.configured {
                    Text("Not set up yet. Popular lists need this build's Trakt keys, which have not been added.")
                        .foregroundStyle(.secondary)
                } else if model.loadingLists && model.lists.isEmpty {
                    ProgressView()
                } else if let message = model.message, model.items.isEmpty {
                    Text(message).foregroundStyle(.secondary)
                } else if model.selected == nil {
                    Text("Pick a list above to see its titles here.").foregroundStyle(.secondary)
                } else if model.loadingItems && model.items.isEmpty {
                    ProgressView()
                } else {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 40), count: 5), spacing: 40) {
                        ForEach(model.items) { item in
                            NavigationLink(value: item) { PosterCard(item: item) }
                                .buttonStyle(.card)
                        }
                    }
                }
            }
            .padding(60)
        }
        .background(settings.theme.background.ignoresSafeArea())
        .task { await model.start() }
    }
}
