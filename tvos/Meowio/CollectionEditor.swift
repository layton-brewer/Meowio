import SwiftUI

// Collection editor: add, rename and remove collections, folders and add-on catalog sources. Changes show on Home
// right away and go to the Meowio account, so the phone and Android TV apps get them too. TMDB and Trakt sources
// made in those apps stay as they are and can be removed here.

struct CollectionEditorView: View {
    @EnvironmentObject var collections: CollectionsStore
    @EnvironmentObject var account: AccountStore
    @State private var newTitle = ""

    var body: some View {
        List {
            Section {
                ForEach(collections.collections) { c in
                    NavigationLink { CollectionDetailEditor(collectionId: c.id) } label: {
                        HStack {
                            Text(c.title)
                            Spacer()
                            Text("\(c.folders?.count ?? 0) folders").foregroundStyle(.secondary)
                        }
                    }
                }
            } footer: {
                Text(account.signedIn ? "Changes are saved to your Meowio account."
                     : "Sign in to your Meowio account to keep collections in step with your other devices. Until then edits stay on this Apple TV.")
            }
            Section("New collection") {
                TextField("Collection name", text: $newTitle)
                Button("Add collection") {
                    collections.addCollection(newTitle, account: account, profileId: ProfileScope.activeId)
                    newTitle = ""
                }
                .disabled(newTitle.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .navigationTitle("Edit collections")
    }
}

struct CollectionDetailEditor: View {
    let collectionId: String
    @EnvironmentObject var collections: CollectionsStore
    @EnvironmentObject var account: AccountStore
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var newFolder = ""

    private var collection: MeowioCollection? { collections.collections.first { $0.id == collectionId } }

    var body: some View {
        List {
            if let c = collection {
                Section("Collection") {
                    TextField("Name", text: $title)
                        .onSubmit { collections.renameCollection(c.id, to: title, account: account, profileId: ProfileScope.activeId) }
                    Toggle("Pin to top of Home", isOn: Binding(
                        get: { c.pinToTop == true },
                        set: { collections.setPinned(c.id, $0, account: account, profileId: ProfileScope.activeId) }))
                    Button("Delete collection", role: .destructive) {
                        collections.removeCollection(c.id, account: account, profileId: ProfileScope.activeId)
                        dismiss()
                    }
                }
                Section("Folders") {
                    ForEach(c.folders ?? []) { f in
                        NavigationLink { FolderEditor(route: FolderRoute(collectionId: c.id, folderId: f.id)) } label: {
                            HStack {
                                Text(f.title)
                                Spacer()
                                Text("\(f.allSources.count) sources").foregroundStyle(.secondary)
                            }
                        }
                    }
                    TextField("New folder name", text: $newFolder)
                    Button("Add folder") {
                        collections.addFolder(newFolder, to: c.id, account: account, profileId: ProfileScope.activeId)
                        newFolder = ""
                    }
                    .disabled(newFolder.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            } else {
                Text("This collection was removed.")
            }
        }
        .navigationTitle(collection?.title ?? "Collection")
        .onAppear { title = collection?.title ?? "" }
    }
}

struct FolderEditor: View {
    let route: FolderRoute
    @EnvironmentObject var collections: CollectionsStore
    @EnvironmentObject var account: AccountStore
    @EnvironmentObject var settings: SettingsStore
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var catalogs: [AddonClient.AddonCatalog] = []
    @State private var loaded = false

    var body: some View {
        let folder = collections.folder(route)
        List {
            if let folder {
                Section("Folder") {
                    TextField("Name", text: $title)
                        .onSubmit { collections.renameFolder(route, to: title, account: account, profileId: ProfileScope.activeId) }
                    Button("Delete folder", role: .destructive) {
                        collections.removeFolder(route, account: account, profileId: ProfileScope.activeId)
                        dismiss()
                    }
                }
                Section("Sources in this folder") {
                    let sources = folder.allSources
                    ForEach(Array(sources.enumerated()), id: \.offset) { index, s in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(label(for: s))
                                Text((s.provider ?? "addon").uppercased()).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Remove") {
                                collections.removeSource(at: index, from: route, account: account, profileId: ProfileScope.activeId)
                            }
                        }
                    }
                    if sources.isEmpty { Text("No sources yet. Add a catalog below.").foregroundStyle(.secondary) }
                }
                Section("Add a catalog from your add-ons") {
                    if !loaded { HStack(spacing: 14) { ProgressView(); Text("Loading catalogs…") } }
                    ForEach(catalogs, id: \.rowId) { cat in
                        Button(cat.title) { add(cat) }
                    }
                    if loaded && catalogs.isEmpty { Text("None of your add-ons offer a browsable catalog.").foregroundStyle(.secondary) }
                }
            } else {
                Text("This folder was removed.")
            }
        }
        .navigationTitle(folder?.title ?? "Folder")
        .onAppear { title = folder?.title ?? "" }
        .task {
            catalogs = await AddonClient.addonCatalogs([AddonClient.cinemeta] + settings.addons)
            loaded = true
        }
    }

    private func label(for s: CollectionSource) -> String {
        if let t = s.title, !t.isEmpty { return t }
        switch (s.provider ?? "addon").lowercased() {
        case "tmdb": return "TMDB \((s.tmdbSourceType ?? "list").capitalized)"
        case "trakt": return "Trakt list"
        default: return [s.catalogId, s.type].compactMap { $0 }.joined(separator: " · ")
        }
    }

    private func add(_ cat: AddonClient.AddonCatalog) {
        Task {
            guard let manifestId = await AddonClient.manifest(for: cat.base)?.id else { return }
            let source: [String: Any] = ["provider": "addon", "addonId": manifestId, "type": cat.type,
                                         "catalogId": cat.id, "title": cat.title]
            collections.addSource(source, to: route, account: account, profileId: ProfileScope.activeId)
        }
    }
}
