import Foundation
import JavaScriptCore
import SwiftUI

// MARK: - Runtime

/// Runs one Nuvio plugin (a JavaScript scraper) the way the Android TV app does: the same polyfill script,
/// a blocking fetch bridge, cheerio on top of the system HTML parser and CryptoJS when the build bundles it.
/// Each run gets its own JavaScript context on its own thread.
final class PluginRunner: @unchecked Sendable {
    struct Result {
        var name: String?
        var title: String?
        var url: String
        var quality: String?
        var size: String?
        var headers: [String: String]
    }

    private static let polyfill: String? = Bundle.main.url(forResource: "PluginPolyfill", withExtension: "js")
        .flatMap { try? String(contentsOf: $0, encoding: .utf8) }
    private static let cryptoJS: String? = (Bundle.main.url(forResource: "crypto-js", withExtension: "js")
        ?? Bundle.main.url(forResource: "crypto-js.min", withExtension: "js"))
        .flatMap { try? String(contentsOf: $0, encoding: .utf8) }

    static var available: Bool { polyfill != nil }

    private let lock = NSLock()
    private var cancelled = false
    private var docs: [String: HTMLDocument] = [:]
    private var docOrder: [String] = []
    /// Elements handed to JavaScript by id, with the page that owns them.
    private var nodes: [String: (doc: String, node: HTMLDocument.Node)] = [:]
    private var counter = 0

    func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    private var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }

    /// Streams from [code] for a TMDB id, or [] when it fails, finds nothing or runs past [timeout] seconds.
    static func run(code: String, scraperId: String, tmdbId: String, mediaType: String, season: Int?, episode: Int?,
                    timeout: Double = 25) async -> [Result] {
        let runner = PluginRunner()
        return await withTaskGroup(of: [Result]?.self) { group in
            group.addTask {
                await withCheckedContinuation { cont in
                    let thread = Thread {
                        cont.resume(returning: runner.execute(code: code, scraperId: scraperId, tmdbId: tmdbId,
                                                              mediaType: mediaType, season: season, episode: episode))
                    }
                    thread.stackSize = 4 << 20
                    thread.qualityOfService = .utility
                    thread.start()
                }
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            runner.cancel()
            group.cancelAll()
            return first ?? []
        }
    }

    private func execute(code: String, scraperId: String, tmdbId: String, mediaType: String, season: Int?, episode: Int?) -> [Result] {
        guard let polyfill = Self.polyfill, let context = JSContext() else { return [] }
        var captured = "[]"
        func define(_ name: String, _ body: @escaping ([JSValue]) -> Any?) {
            let block: @convention(block) () -> Any? = {
                body((JSContext.currentArguments() as? [JSValue]) ?? [])
            }
            context.setObject(block, forKeyedSubscript: name as NSString)
        }
        func arg(_ a: [JSValue], _ i: Int) -> String {
            guard i < a.count, !a[i].isUndefined, !a[i].isNull else { return "" }
            return a[i].toString() ?? ""
        }
        let console = JSValue(newObjectIn: context)
        let quiet: @convention(block) () -> Void = {}
        for level in ["log", "error", "warn", "info", "debug"] { console?.setObject(quiet, forKeyedSubscript: level as NSString) }
        context.setObject(console, forKeyedSubscript: "console" as NSString)

        define("__get_scraper_id") { _ in scraperId }
        define("__get_scraper_settings") { _ in "{}" }
        define("__get_tmdb_api_key") { _ in TMDB.keyForPlugins }
        define("__native_fetch") { [weak self] a in
            self?.fetch(url: arg(a, 0), method: arg(a, 1), headersJSON: arg(a, 2), bodyKind: arg(a, 3), body: arg(a, 4),
                        follow: a.count > 5 ? a[5].toBool() : true) ?? "{}"
        }
        define("__parse_url") { a in Self.parseURL(arg(a, 0)) }
        define("__cheerio_load") { [weak self] a in self?.load(arg(a, 0)) ?? "" }
        define("__cheerio_select") { [weak self] a in self?.select(doc: arg(a, 0), scope: nil, selector: arg(a, 1)) ?? "[]" }
        define("__cheerio_find") { [weak self] a in self?.select(doc: arg(a, 0), scope: arg(a, 1), selector: arg(a, 2)) ?? "[]" }
        define("__cheerio_text") { [weak self] a in
            arg(a, 1).split(separator: ",").compactMap { self?.node(String($0)) }.map(HTMLDocument.text).joined(separator: " ")
        }
        define("__cheerio_html") { [weak self] a in
            let id = arg(a, 1)
            if id.isEmpty { return self?.document(arg(a, 0))?.html() ?? "" }
            return self?.node(id).map(HTMLDocument.innerHTML) ?? ""
        }
        define("__cheerio_inner_html") { [weak self] a in self?.node(arg(a, 1)).map(HTMLDocument.innerHTML) ?? "" }
        define("__cheerio_attr") { [weak self] a in
            let v = self?.node(arg(a, 1)).flatMap { HTMLDocument.attr($0, arg(a, 2)) } ?? ""
            return v.isEmpty ? "__UNDEFINED__" : v
        }
        define("__cheerio_next") { [weak self] a in
            guard let self, let n = self.node(arg(a, 1)), let next = HTMLDocument.nextElement(n) else { return "__NONE__" }
            return self.register(doc: arg(a, 0), next)
        }
        define("__cheerio_prev") { [weak self] a in
            guard let self, let n = self.node(arg(a, 1)), let prev = HTMLDocument.previousElement(n) else { return "__NONE__" }
            return self.register(doc: arg(a, 0), prev)
        }
        define("__capture_result") { a in captured = arg(a, 0); return nil }
        define("__get_call_args") { _ in
            let args: [String: Any] = ["tmdbId": tmdbId, "mediaType": mediaType,
                                       "season": season.map { $0 as Any } ?? NSNull(), "episode": episode.map { $0 as Any } ?? NSNull()]
            return (try? JSONSerialization.data(withJSONObject: args)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        }

        context.evaluateScript(polyfill)
        if let crypto = Self.cryptoJS {
            // The UMD build sees no module system here, so it puts CryptoJS on the global object.
            context.evaluateScript("(function(){ var module = undefined, exports = undefined, define = undefined;\n\(crypto)\n; if (typeof CryptoJS !== 'undefined') globalThis.CryptoJS = CryptoJS; }).call(globalThis);")
        }
        context.evaluateScript("var module = { exports: {} };\nvar exports = module.exports;\n(function() {\n\(code)\n})();")
        // Promises settle as the call returns, because fetch blocks instead of waiting on a timer.
        context.evaluateScript("""
            (async function() {
                try {
                    var getStreams = module.exports.getStreams || globalThis.getStreams;
                    if (!getStreams) { __capture_result("[]"); return; }
                    var args = JSON.parse(__get_call_args());
                    var result = await getStreams(args.tmdbId, args.mediaType, args.season, args.episode);
                    __capture_result(JSON.stringify(result || []));
                } catch (e) {
                    __capture_result("[]");
                }
            })();
            """)
        lock.lock(); docs.removeAll(); nodes.removeAll(); lock.unlock()
        return Self.parseResults(captured)
    }

    // MARK: cheerio bridge

    private func document(_ id: String) -> HTMLDocument? { lock.lock(); defer { lock.unlock() }; return docs[id] }
    private func node(_ id: String) -> HTMLDocument.Node? { lock.lock(); defer { lock.unlock() }; return nodes[id]?.node }

    private func load(_ html: String) -> String {
        guard let doc = HTMLDocument(html) else { return "" }
        lock.lock(); defer { lock.unlock() }
        counter += 1
        let id = "d\(counter)"
        docs[id] = doc
        docOrder.append(id)
        // Like Android, keep at most eight pages so a long scrape does not grow without limit.
        if docOrder.count > 8 {
            let old = docOrder.removeFirst()
            docs[old] = nil
            nodes = nodes.filter { $0.value.doc != old }
        }
        return id
    }

    private func register(doc: String, _ n: HTMLDocument.Node) -> String {
        lock.lock(); defer { lock.unlock() }
        counter += 1
        let id = "e\(counter)"
        nodes[id] = (doc, n)
        return id
    }

    private func select(doc: String, scope: String?, selector: String) -> String {
        guard let d = document(doc) else { return "[]" }
        var scopeNode: HTMLDocument.Node?
        if let scope {
            guard let n = node(scope) else { return "[]" }
            scopeNode = n
        }
        let found = d.select(selector, in: scopeNode).map { register(doc: doc, $0) }
        return "[" + found.map { "\"\($0)\"" }.joined(separator: ",") + "]"
    }

    // MARK: fetch bridge

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 20
        c.timeoutIntervalForResource = 30
        return URLSession(configuration: c)
    }()

    private final class NoRedirect: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }

    private static let noRedirectSession: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 20
        c.timeoutIntervalForResource = 30
        return URLSession(configuration: c, delegate: NoRedirect(), delegateQueue: nil)
    }()

    private static let maxBody = 8 * 1024 * 1024

    private func fetch(url: String, method: String, headersJSON: String, bodyKind: String, body: String, follow: Bool) -> String {
        func failure(_ message: String) -> String {
            Self.json(["ok": false, "status": 0, "statusText": message, "url": url, "body": "", "bodyBase64": "", "headers": [String: String]()])
        }
        guard !isCancelled, let u = URL(string: url), u.scheme == "http" || u.scheme == "https" else { return failure("Fetch failed") }
        var r = URLRequest(url: u)
        let verb = method.isEmpty ? "GET" : method.uppercased()
        r.httpMethod = verb
        let headers = ((try? JSONSerialization.jsonObject(with: Data(headersJSON.utf8))) as? [String: Any]) ?? [:]
        for (k, v) in headers where k.lowercased() != "accept-encoding" { r.setValue("\(v)", forHTTPHeaderField: k) }
        if r.value(forHTTPHeaderField: "User-Agent") == nil {
            r.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", forHTTPHeaderField: "User-Agent")
        }
        if verb != "GET", verb != "HEAD" {
            switch bodyKind {
            case "base64": r.httpBody = Data(base64Encoded: Self.paddedBase64(body))
            case "text": r.httpBody = Data(body.utf8)
            default: break
            }
            if r.httpBody != nil, r.value(forHTTPHeaderField: "Content-Type") == nil {
                r.setValue(verb == "POST" ? "application/x-www-form-urlencoded" : "application/json", forHTTPHeaderField: "Content-Type")
            }
        }
        let done = DispatchSemaphore(value: 0)
        var out: (Data?, URLResponse?, Error?) = (nil, nil, nil)
        let task = (follow ? Self.session : Self.noRedirectSession).dataTask(with: r) { d, resp, err in
            out = (d, resp, err)
            done.signal()
        }
        task.resume()
        // Wake every half second so a cancelled run stops waiting.
        while done.wait(timeout: .now() + 0.5) == .timedOut {
            if isCancelled { task.cancel(); return failure("Cancelled") }
        }
        guard let http = out.1 as? HTTPURLResponse else { return failure(out.2?.localizedDescription ?? "Fetch failed") }
        var data = out.0 ?? Data()
        let truncated = data.count > Self.maxBody
        if truncated { data = data.prefix(Self.maxBody) }
        var encoding = String.Encoding.utf8
        if let name = http.textEncodingName {
            let cf = CFStringConvertIANACharSetNameToEncoding(name as CFString)
            if cf != kCFStringEncodingInvalidId { encoding = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cf)) }
        }
        let text = String(data: data, encoding: encoding) ?? String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        var responseHeaders: [String: String] = [:]
        for (k, v) in http.allHeaderFields { responseHeaders["\(k)".lowercased()] = "\(v)" }
        return Self.json(["ok": (200..<300).contains(http.statusCode), "status": http.statusCode,
                          "statusText": HTTPURLResponse.localizedString(forStatusCode: http.statusCode),
                          "url": http.url?.absoluteString ?? url, "body": text, "bodyBase64": data.base64EncodedString(),
                          "headers": responseHeaders, "truncated": truncated])
    }

    private static func paddedBase64(_ s: String) -> String {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            .filter { !$0.isWhitespace }
        while t.count % 4 != 0 { t += "=" }
        return t
    }

    private static func json(_ obj: [String: Any]) -> String {
        (try? JSONSerialization.data(withJSONObject: obj)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    private static func parseURL(_ s: String) -> String {
        guard let c = URLComponents(string: s), let host = c.host else {
            return json(["protocol": "", "host": "", "hostname": "", "port": "", "pathname": "/", "search": "", "hash": ""])
        }
        let port = c.port.map(String.init) ?? ""
        return json(["protocol": "\(c.scheme ?? ""):", "host": port.isEmpty ? host : "\(host):\(port)", "hostname": host,
                     "port": port, "pathname": c.percentEncodedPath.isEmpty ? "/" : c.percentEncodedPath,
                     "search": c.percentEncodedQuery.map { "?\($0)" } ?? "", "hash": c.percentEncodedFragment.map { "#\($0)" } ?? ""])
    }

    static func parseResults(_ text: String) -> [Result] {
        guard let list = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [[String: Any]] else { return [] }
        func clean(_ v: Any?) -> String? {
            guard let v, !(v is NSNull) else { return nil }
            let s = "\(v)"
            return s.isEmpty || s.contains("[object") ? nil : s
        }
        return list.compactMap { item in
            let url = clean(item["url"]) ?? clean((item["url"] as? [String: Any])?["url"])
            guard let url, URL(string: url) != nil else { return nil }
            var headers: [String: String] = [:]
            for (k, v) in item["headers"] as? [String: Any] ?? [:] { if let s = v as? String { headers[k] = s } }
            return Result(name: clean(item["name"]), title: clean(item["title"]) ?? clean(item["name"]), url: url,
                          quality: clean(item["quality"]), size: clean(item["size"]), headers: headers)
        }
    }
}

extension TMDB {
    /// The key plugins get as TMDB_API_KEY (the build's key, or the user's own).
    static var keyForPlugins: String { key }

    private static let idCache = NSCache<NSString, NSString>()

    /// TMDB id for an IMDb id (tt…) or a "tmdb:123" id.
    static func tmdbId(for id: String, type: String) async -> String? {
        if id.hasPrefix("tmdb:") { return String(id.dropFirst(5)) }
        guard id.hasPrefix("tt") else { return nil }
        let cacheKey = "\(type)/\(id)" as NSString
        if let hit = idCache.object(forKey: cacheKey) { return hit as String }
        guard let found = await get("/find/\(id)", ["external_source": "imdb_id"]) else { return nil }
        let results = found[type == "movie" ? "movie_results" : "tv_results"] as? [[String: Any]]
        guard let n = results?.first?["id"] as? Int else { return nil }
        idCache.setObject(String(n) as NSString, forKey: cacheKey)
        return String(n)
    }
}

// MARK: - Repositories and scrapers

/// The profile's Nuvio plugin repositories and scrapers, synced with the account like on Android TV.
@MainActor
final class PluginStore: ObservableObject {
    struct Repo: Codable, Hashable, Identifiable {
        var url: String
        var name: String
        var enabled = true
        var id: String { url }
    }

    struct Scraper: Codable, Hashable, Identifiable {
        var key: String       // repo url + scraper id
        var id: String
        var name: String
        var repoURL: String
        var codeURL: String
        var version: String
        var supportedTypes: [String]
        var enabled: Bool
        var logo: String?
    }

    @Published var enabled: Bool { didSet { store.set(enabled, forKey: "pluginsEnabled") } }
    @Published private(set) var repos: [Repo] = []
    @Published private(set) var scrapers: [Scraper] = []
    @Published private(set) var refreshing = false
    @Published var message: String?

    private let store = ProfileScope.defaults
    private let profileId = ProfileScope.activeId
    private var results: [String: (at: Date, list: [StreamItem])] = [:]
    private var inflight: [String: Task<[StreamItem], Never>] = [:]
    /// Pushes repository changes to the account. Set when the profile opens.
    var onReposChanged: (([Repo]) -> Void)?

    init() {
        enabled = store.object(forKey: "pluginsEnabled") as? Bool ?? true
        if let d = store.data(forKey: "pluginRepos"), let r = try? JSONDecoder().decode([Repo].self, from: d) { repos = r }
        if let d = store.data(forKey: "pluginScrapers"), let s = try? JSONDecoder().decode([Scraper].self, from: d) { scrapers = s }
    }

    var hasActiveScrapers: Bool { enabled && PluginRunner.available && scrapers.contains { $0.enabled && isRepoOn($0.repoURL) } }

    private func isRepoOn(_ url: String) -> Bool { repos.first { $0.url == url }?.enabled ?? false }

    private func persist() {
        if let d = try? JSONEncoder().encode(repos) { store.set(d, forKey: "pluginRepos") }
        if let d = try? JSONEncoder().encode(scrapers) { store.set(d, forKey: "pluginScrapers") }
    }

    // MARK: Editing

    /// Adds a repository by its manifest link. Returns why it failed, or nil.
    func add(_ raw: String) async -> String? {
        guard let url = Self.manifestURL(raw) else { return "That is not a plugin repository link." }
        if repos.contains(where: { $0.url == url }) { return "That repository is already added." }
        guard let manifest = await Self.fetchManifest(url) else { return "No Nuvio plugin manifest at that link." }
        repos.append(Repo(url: url, name: manifest.name))
        apply(manifest, repoURL: url)
        persist()
        onReposChanged?(repos)
        return nil
    }

    func remove(_ repo: Repo) {
        repos.removeAll { $0.url == repo.url }
        scrapers.removeAll { $0.repoURL == repo.url }
        persist()
        onReposChanged?(repos)
    }

    func setRepo(_ repo: Repo, enabled on: Bool) {
        guard let i = repos.firstIndex(where: { $0.url == repo.url }) else { return }
        repos[i].enabled = on
        persist()
        onReposChanged?(repos)
    }

    func setScraper(_ s: Scraper, enabled on: Bool) {
        guard let i = scrapers.firstIndex(where: { $0.key == s.key }) else { return }
        scrapers[i].enabled = on
        persist()
    }

    /// Reloads every repository's manifest and drops downloaded code so new versions are fetched.
    func refreshAll() async {
        refreshing = true
        defer { refreshing = false }
        for repo in repos {
            if let m = await Self.fetchManifest(repo.url) { apply(m, repoURL: repo.url) }
        }
        try? FileManager.default.removeItem(at: Self.codeFolder)
        persist()
    }

    /// Takes the account's repository list (Android's NUVIO_JS repositories) for this profile.
    func adopt(remote: [Repo]) async {
        let urls = remote.compactMap { Self.manifestURL($0.url) }
        guard urls != repos.map(\.url) || zip(remote, repos).contains(where: { $0.enabled != $1.enabled }) else { return }
        var next: [Repo] = []
        for (r, url) in zip(remote, urls) {
            var repo = repos.first { $0.url == url } ?? Repo(url: url, name: r.name)
            repo.enabled = r.enabled
            if !scrapers.contains(where: { $0.repoURL == url }), let m = await Self.fetchManifest(url) {
                repo.name = m.name
                apply(m, repoURL: url)
            }
            next.append(repo)
        }
        repos = next
        scrapers.removeAll { s in !next.contains { $0.url == s.repoURL } }
        persist()
    }

    private func apply(_ m: Manifest, repoURL: String) {
        let base = (repoURL as NSString).deletingLastPathComponent
        var list: [Scraper] = []
        for info in m.scrapers {
            // Android blocks this provider; Apple TV does the same.
            if [info.id, info.name, info.filename].contains(where: { $0.lowercased().contains("videasy") }) { continue }
            let key = "\(repoURL)#\(info.id)"
            let code = info.filename.hasPrefix("http") ? info.filename : "\(base)/\(info.filename)"
            let previous = scrapers.first { $0.key == key }
            list.append(Scraper(key: key, id: info.id, name: info.name, repoURL: repoURL, codeURL: code, version: info.version,
                                supportedTypes: info.supportedTypes ?? ["movie", "tv"],
                                enabled: previous?.enabled ?? (info.enabled ?? true), logo: info.logo))
        }
        scrapers.removeAll { $0.repoURL == repoURL }
        scrapers.append(contentsOf: list)
    }

    // MARK: Running

    /// Streams from every enabled scraper for a movie or an episode, as stream results for Smart ranking.
    /// Cached for ten minutes, so preloading makes playback start at once.
    func streams(type: String, id: String, timeout: Double = 12) async -> [StreamItem] {
        guard hasActiveScrapers else { return [] }
        let key = "\(type)/\(id)"
        if let hit = results[key], Date().timeIntervalSince(hit.at) < 600 { return hit.list }
        if let task = inflight[key] { return await task.value }
        let active = scrapers.filter { s in s.enabled && isRepoOn(s.repoURL) && s.supports(type) }
        let task = Task<[StreamItem], Never> {
            let ref = EpisodeRef(id)
            let baseId = ref?.showId ?? id
            let mediaType = type == "movie" ? "movie" : "tv"
            guard !active.isEmpty, let tmdb = await TMDB.tmdbId(for: baseId, type: type) else { return [] }
            var perScraper: [Int: [StreamItem]] = [:]
            await withTaskGroup(of: (Int, [StreamItem]).self) { group in
                for (i, s) in active.enumerated() {
                    group.addTask {
                        guard let code = await Self.code(for: s) else { return (i, []) }
                        let found = await PluginRunner.run(code: code, scraperId: s.id, tmdbId: tmdb, mediaType: mediaType,
                                                           season: ref?.season, episode: ref?.episode, timeout: timeout)
                        return (i, found.map { Self.stream($0, scraper: s) })
                    }
                }
                for await (i, list) in group { perScraper[i] = list }
            }
            return active.indices.flatMap { perScraper[$0] ?? [] }
        }
        inflight[key] = task
        let list = await task.value
        inflight[key] = nil
        if !list.isEmpty { results[key] = (Date(), list) }
        return list
    }

    private nonisolated static func stream(_ r: PluginRunner.Result, scraper: Scraper) -> StreamItem {
        let name = [r.name ?? scraper.name, r.quality].compactMap { $0 }.joined(separator: "\n")
        let title = [r.title, r.size].compactMap { $0 }.joined(separator: "\n")
        var s = StreamItem(name: name, title: title.isEmpty ? nil : title, url: r.url,
                           behaviorHints: r.headers.isEmpty ? nil : .init(proxyHeaders: .init(request: r.headers)))
        // The plugin's name is what pinned sources and the source list show, like Android.
        s.addonName = scraper.name
        return s
    }

    // MARK: Code and manifests

    private nonisolated static var codeFolder: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("plugins", isDirectory: true)
    }

    /// The scraper's code, downloaded once per version and kept in Caches (re-downloaded if the system clears it).
    private nonisolated static func code(for s: Scraper) async -> String? {
        let safe = String(s.key.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : "_" }.suffix(120))
        let file = codeFolder.appendingPathComponent("\(safe)-\(s.version).js")
        if let text = try? String(contentsOf: file, encoding: .utf8) { return text }
        guard let u = URL(string: s.codeURL) else { return nil }
        var r = URLRequest(url: u)
        r.timeoutInterval = 20
        r.setValue("NuvioTV/1.0", forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await URLSession.shared.data(for: r), (resp as? HTTPURLResponse)?.statusCode == 200,
              data.count < 5 * 1024 * 1024, let text = String(data: data, encoding: .utf8) else { return nil }
        try? FileManager.default.createDirectory(at: codeFolder, withIntermediateDirectories: true)
        try? text.write(to: file, atomically: true, encoding: .utf8)
        return text
    }

    struct Manifest: Decodable {
        struct Info: Decodable {
            var id: String
            var name: String
            var version: String
            var filename: String
            var supportedTypes: [String]?
            var enabled: Bool?
            var logo: String?
        }
        var name: String
        var scrapers: [Info]
    }

    static func manifestURL(_ raw: String) -> String? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        if s.hasPrefix("nuvio://") { s = "https://" + s.dropFirst("nuvio://".count) }
        if !s.contains("://") { s = "https://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        if !(s as NSString).lastPathComponent.lowercased().hasSuffix(".json") { s += "/manifest.json" }
        guard let u = URL(string: s), u.scheme == "https" || u.scheme == "http" else { return nil }
        return s
    }

    private static func fetchManifest(_ url: String) async -> Manifest? {
        guard let u = URL(string: url) else { return nil }
        var r = URLRequest(url: u)
        r.timeoutInterval = 15
        guard let (data, _) = try? await URLSession.shared.data(for: r) else { return nil }
        return try? JSONDecoder().decode(Manifest.self, from: data)
    }
}

extension PluginStore.Scraper {
    func supports(_ type: String) -> Bool {
        func norm(_ t: String) -> String {
            let l = t.lowercased()
            return l == "series" || l == "show" || l == "tv" ? "tv" : l
        }
        return supportedTypes.contains { norm($0) == norm(type) }
    }
}

// MARK: - Settings

struct PluginsSettingsView: View {
    @EnvironmentObject var plugins: PluginStore
    @State private var newURL = ""
    @State private var adding = false

    var body: some View {
        Form {
            Section {
                Toggle("Use plugins", isOn: $plugins.enabled)
                Text("Nuvio plugins find extra sources for Smart autoplay, like on Android TV. Repositories added on your phone or Android TV appear here when you're signed in.")
                    .font(.callout).foregroundStyle(.secondary)
                if !PluginRunner.available {
                    Text("This build can't run plugins.").foregroundStyle(.orange)
                }
            }
            Section("Add a repository") {
                TextField("Repository link", text: $newURL)
                    .textContentType(.URL)
                Button(adding ? "Adding…" : "Add") {
                    adding = true
                    Task {
                        plugins.message = await plugins.add(newURL)
                        if plugins.message == nil { newURL = "" }
                        adding = false
                    }
                }
                .disabled(adding || newURL.trimmingCharacters(in: .whitespaces).isEmpty)
                if let m = plugins.message { Text(m).foregroundStyle(.orange) }
            }
            ForEach(plugins.repos) { repo in
                Section(repo.name) {
                    Toggle("Repository on", isOn: Binding(get: { repo.enabled }, set: { plugins.setRepo(repo, enabled: $0) }))
                    ForEach(plugins.scrapers.filter { $0.repoURL == repo.url }) { s in
                        Toggle(isOn: Binding(get: { s.enabled }, set: { plugins.setScraper(s, enabled: $0) })) {
                            VStack(alignment: .leading) {
                                Text(s.name)
                                Text("v\(s.version) · \(s.supportedTypes.joined(separator: ", "))").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .disabled(!repo.enabled)
                    }
                    Button("Remove repository", role: .destructive) { plugins.remove(repo) }
                }
            }
            if !plugins.repos.isEmpty {
                Section {
                    Button(plugins.refreshing ? "Updating…" : "Update all plugins") { Task { await plugins.refreshAll() } }
                        .disabled(plugins.refreshing)
                }
            }
        }
        .meowioPageTitle("Plugins")
    }
}
