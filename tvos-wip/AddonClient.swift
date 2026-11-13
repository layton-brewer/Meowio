import Foundation

enum AddonClient {
    static let cinemeta = "https://v3-cinemeta.strem.io"

    static func fetch<T: Decodable>(_ type: T.Type, from urlString: String) async -> T? {
        guard let url = URL(string: urlString) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            return nil
        }
    }

    static func catalog(type: String, id: String = "top", search: String? = nil) async -> [MetaItem] {
        var path = "\(cinemeta)/catalog/\(type)/\(id)"
        if let search, let q = search.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) {
            path += "/search=\(q)"
        }
        let response = await fetch(CatalogResponse.self, from: path + ".json")
        return (response?.metas ?? []).map { item in
            var m = item
            m.type = type
            return m
        }
    }

    static func meta(type: String, id: String) async -> MetaItem? {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        var m = await fetch(MetaResponse.self, from: "\(cinemeta)/meta/\(type)/\(encoded).json")?.meta
        m?.type = type
        return m
    }

    private struct Manifest: Decodable { var name: String? }
    private static var manifestNames: [String: String] = [:]
    private static let nameLock = NSLock()

    /// The add-on's display name from its manifest, used for pinned sources and the source list.
    static func addonName(for base: String) async -> String {
        nameLock.lock()
        let cached = manifestNames[base]
        nameLock.unlock()
        if let cached { return cached }
        let name = await fetch(Manifest.self, from: "\(base)/manifest.json")?.name
            ?? URL(string: base)?.host ?? base
        nameLock.lock()
        manifestNames[base] = name
        nameLock.unlock()
        return name
    }

    /// Names of the configured add-ons, in order.
    static func addonNames(_ addons: [String]) async -> [String] {
        var names: [String] = []
        for base in addons { names.append(await addonName(for: base)) }
        return names
    }

    /// Queries every configured add-on in parallel. Torrent-only results are dropped because Apple
    /// platforms play direct and debrid HTTP links only. The list is in add-on order, not ranked.
    static func streams(type: String, id: String, addons: [String]) async -> [StreamItem] {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        var perAddon: [Int: [StreamItem]] = [:]
        await withTaskGroup(of: (Int, [StreamItem]).self) { group in
            for (index, base) in addons.enumerated() {
                group.addTask {
                    let name = await addonName(for: base)
                    var list = await fetch(StreamResponse.self, from: "\(base)/stream/\(type)/\(encoded).json")?.streams ?? []
                    for i in list.indices { list[i].addonName = name }
                    return (index, list)
                }
            }
            for await (index, list) in group { perAddon[index] = list }
        }
        var all: [StreamItem] = []
        for index in addons.indices { all.append(contentsOf: perAddon[index] ?? []) }
        return all.filter { $0.playableURL != nil && $0.behaviorHints?.notWebReady != true }
    }
}
