import SwiftUI

/// Meowio account (the same Supabase account server the Android apps use when it is set up). Signing in
/// brings the account's profiles, add-ons, My List, watch progress and watched marks to this Apple TV and keeps
/// sending changes back. Off when the build has no account server.
@MainActor
final class AccountStore: ObservableObject {
    @Published private(set) var email: String?
    @Published private(set) var busy = false
    @Published private(set) var lastSync: Date?
    @Published var message: String?
    /// MDBList API key saved on the account by the phone or Android TV, picked up when this profile has no sign-in.
    private(set) var mdblistKey: String?

    @Published private var accessToken: String?
    private var refreshToken: String?
    private var expiresAt: Date?
    private var userId: String?
    private var ownerId: String?
    /// Identifies this device in sync calls so the server can skip echoing its own changes back.
    private let clientId: String

    static var serverURL: String {
        var s = BuildSecrets.supabaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }
    static var anonKey: String { BuildSecrets.supabaseAnonKey.trimmingCharacters(in: .whitespacesAndNewlines) }
    static var available: Bool { !serverURL.isEmpty && !anonKey.isEmpty }

    var signedIn: Bool { accessToken != nil }

    init() {
        let d = UserDefaults.standard
        accessToken = d.string(forKey: "accountAccess")
        refreshToken = d.string(forKey: "accountRefresh")
        if let t = d.object(forKey: "accountExpires") as? Double { expiresAt = Date(timeIntervalSince1970: t) }
        userId = d.string(forKey: "accountUser")
        email = d.string(forKey: "accountEmail")
        if let id = d.string(forKey: "syncClientId") {
            clientId = id
        } else {
            clientId = UUID().uuidString.lowercased()
            d.set(clientId, forKey: "syncClientId")
        }
    }

    // MARK: Sign-in

    func signIn(email: String, password: String, create: Bool) async {
        guard Self.available else { return }
        busy = true
        message = nil
        defer { busy = false }
        let path = create ? "/auth/v1/signup" : "/auth/v1/token?grant_type=password"
        // The tvOS keyboard capitalises the first letter and can autocorrect; the account server stores
        // emails in lower case, so "Name@…" was rejected as wrong credentials.
        let email = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let r = await call(path, body: ["email": email, "password": password], authed: false)
        guard let obj = r.json as? [String: Any] else {
            message = "Could not reach the account server."
            return
        }
        if obj["access_token"] != nil {
            save(obj)
            self.email = email
            UserDefaults.standard.set(self.email, forKey: "accountEmail")
            message = nil
            busy = false
            await onSignedIn?()
        } else if create, r.status < 300 {
            message = "Check your email to confirm the account, then sign in."
        } else {
            let text = (obj["error_description"] as? String) ?? (obj["msg"] as? String) ?? (obj["message"] as? String)
            if r.status == 400, !create {
                message = "Wrong email or password. If you signed up with Google or Apple, use \"Sign in with a code\" instead."
            } else {
                message = text ?? "Sign-in failed. Check the email and password."
            }
        }
    }

    // MARK: Code sign-in (the Android TV flow: scan or type a code on nuvio.tv, no typing on the TV)

    struct LinkCode: Equatable {
        var code: String
        var userCode: String
        var url: String
        var nonce: String
        var interval: Int
    }

    /// The code shown while waiting for the user to approve this Apple TV on their phone.
    @Published private(set) var linkCode: LinkCode?
    private var linkTask: Task<Void, Never>?
    /// Called once a code sign-in finishes, so the app can pull the account's data.
    var onSignedIn: (() async -> Void)?

    private static let linkBase = "https://nuvio.tv/link"
    private static let legacyLinkBase = "https://nuvio.tv/tv-login"

    func startCodeSignIn() async {
        guard Self.available else { return }
        cancelCodeSignIn()
        message = nil
        let nonce = (UUID().uuidString + UUID().uuidString).replacingOccurrences(of: "-", with: "").lowercased()
        var link: LinkCode?
        let v2 = await call("/rest/v1/rpc/start_device_login_session",
                            body: ["p_device_nonce": nonce, "p_redirect_base_url": Self.linkBase, "p_device_type": "tv",
                                   "p_device_name": "Apple TV"], authed: false)
        if let row = (v2.json as? [[String: Any]])?.first, let code = row["device_code"] as? String {
            link = LinkCode(code: code, userCode: row["user_code"] as? String ?? code,
                            url: row["verification_uri_complete"] as? String ?? Self.linkBase, nonce: nonce,
                            interval: max(2, row["poll_interval_seconds"] as? Int ?? 3))
        } else {
            // Older account servers only have the first version of TV sign-in.
            let v1 = await call("/rest/v1/rpc/start_tv_login_session",
                                body: ["p_device_nonce": nonce, "p_redirect_base_url": Self.legacyLinkBase], authed: false)
            if let row = (v1.json as? [[String: Any]])?.first, let code = row["code"] as? String {
                link = LinkCode(code: code, userCode: code, url: row["web_url"] as? String ?? Self.legacyLinkBase, nonce: nonce,
                                interval: max(2, row["poll_interval_seconds"] as? Int ?? 3))
            }
        }
        guard let link else {
            message = "Could not get a sign-in code. Check the connection and try again."
            return
        }
        linkCode = link
        linkTask = Task { [weak self] in await self?.pollCode(link) }
    }

    func cancelCodeSignIn() {
        linkTask?.cancel()
        linkTask = nil
        linkCode = nil
    }

    private func pollCode(_ link: LinkCode) async {
        // Codes last about ten minutes.
        for _ in 0..<(600 / link.interval) {
            try? await Task.sleep(nanoseconds: UInt64(link.interval) * 1_000_000_000)
            guard !Task.isCancelled, linkCode == link else { return }
            let r = await call("/rest/v1/rpc/poll_tv_login_session", body: ["p_code": link.code, "p_device_nonce": link.nonce],
                               authed: false)
            let status = ((r.json as? [[String: Any]])?.first?["status"] as? String ?? "").lowercased()
            switch status {
            case "approved":
                let x = await call("/functions/v1/tv-logins-exchange", body: ["code": link.code, "device_nonce": link.nonce],
                                   authed: false)
                linkCode = nil
                if let obj = x.json as? [String: Any], obj["access_token"] != nil {
                    save(obj)
                    if email == nil { email = "your Nuvio account" }
                    await onSignedIn?()
                } else {
                    message = "Sign-in was approved but could not finish. Try again."
                }
                return
            case "expired", "used", "cancelled":
                linkCode = nil
                message = "That code expired. Get a new one."
                return
            default:
                continue
            }
        }
        if linkCode == link { linkCode = nil; message = "That code expired. Get a new one." }
    }

    func signOut() {
        if accessToken != nil { Task { _ = await call("/auth/v1/logout", body: [String: String]()) } }
        accessToken = nil
        refreshToken = nil
        expiresAt = nil
        userId = nil
        ownerId = nil
        email = nil
        pulledAddonProfiles = []
        for k in ["accountAccess", "accountRefresh", "accountExpires", "accountUser", "accountEmail"] {
            UserDefaults.standard.removeObject(forKey: k)
        }
    }

    private func save(_ obj: [String: Any]) {
        accessToken = obj["access_token"] as? String
        refreshToken = obj["refresh_token"] as? String ?? refreshToken
        expiresAt = Date().addingTimeInterval(obj["expires_in"] as? Double ?? 3600)
        if let user = obj["user"] as? [String: Any] {
            userId = user["id"] as? String ?? userId
            if let mail = user["email"] as? String { email = mail; UserDefaults.standard.set(mail, forKey: "accountEmail") }
        }
        // The TV code exchange can answer without a user object: the token itself names the user.
        if let claims = accessToken.flatMap(Self.jwtClaims) {
            if let sub = claims["sub"] as? String, !sub.isEmpty {
                if sub != userId { ownerId = nil }
                userId = sub
            }
            if email == nil || email == "your Nuvio account", let mail = claims["email"] as? String, !mail.isEmpty {
                email = mail
                UserDefaults.standard.set(mail, forKey: "accountEmail")
            }
        }
        let d = UserDefaults.standard
        d.set(accessToken, forKey: "accountAccess")
        d.set(refreshToken, forKey: "accountRefresh")
        d.set(expiresAt?.timeIntervalSince1970, forKey: "accountExpires")
        d.set(userId, forKey: "accountUser")
    }

    private static func jwtClaims(_ token: String) -> [String: Any]? {
        let parts = token.split(separator: ".")
        guard parts.count >= 2 else { return nil }
        var b64 = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private func refresh() async -> Bool {
        guard let refreshToken else { return false }
        let r = await call("/auth/v1/token?grant_type=refresh_token", body: ["refresh_token": refreshToken], authed: false)
        if r.status == 200, let obj = r.json as? [String: Any], obj["access_token"] != nil {
            save(obj)
            return true
        }
        if r.status == 400 || r.status == 401 {
            signOut()
            message = "You were signed out. Sign in again to keep syncing."
        }
        return false
    }

    // MARK: HTTP

    struct Reply { var status: Int; var json: Any? }

    private func call(_ path: String, body: Any?, method: String = "POST", authed: Bool = true) async -> Reply {
        if authed, let expiresAt, expiresAt.timeIntervalSinceNow < 60 { _ = await refresh() }
        var reply = await send(path, body: body, method: method, authed: authed)
        if authed, reply.status == 401, await refresh() {
            reply = await send(path, body: body, method: method, authed: authed)
        }
        return reply
    }

    private func send(_ path: String, body: Any?, method: String, authed: Bool) async -> Reply {
        guard let url = URL(string: Self.serverURL + path) else { return Reply(status: 0, json: nil) }
        var r = URLRequest(url: url)
        r.httpMethod = body == nil ? "GET" : method
        r.timeoutInterval = 20
        r.setValue(Self.anonKey, forHTTPHeaderField: "apikey")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if authed { r.setValue("Bearer \(accessToken ?? Self.anonKey)", forHTTPHeaderField: "Authorization") }
        if let body { r.httpBody = try? JSONSerialization.data(withJSONObject: body) }
        guard let result = try? await URLSession.shared.data(for: r) else { return Reply(status: 0, json: nil) }
        let status = (result.1 as? HTTPURLResponse)?.statusCode ?? 0
        return Reply(status: status, json: try? JSONSerialization.jsonObject(with: result.0, options: [.fragmentsAllowed]))
    }

    private func rpc(_ name: String, _ params: [String: Any] = [:]) async -> Reply {
        await call("/rest/v1/rpc/\(name)", body: params)
    }

    /// Calls an account-server function as the signed-in user. Watch party uses it.
    func rpcCall(_ name: String, _ params: [String: Any] = [:]) async -> Reply {
        await rpc(name, params)
    }

    /// This device's id on the account server (sync and watch party both identify it).
    var deviceId: String { clientId }

    /// A session token that is not about to expire, for the watch party live connection.
    func freshAccessToken() async -> String? {
        if let expiresAt, expiresAt.timeIntervalSinceNow < 60 { _ = await refresh() }
        return accessToken
    }

    // MARK: Collections

    func pullCollections(profileId: Int) async -> [[String: Any]]? {
        guard signedIn else { return nil }
        let r = await rpc("sync_pull_collections", ["p_profile_id": profileId])
        guard r.status < 300, let rows = r.json as? [[String: Any]], let first = rows.first else { return nil }
        return first["collections_json"] as? [[String: Any]]
    }

    /// Writes this profile's collections back to the account, as the phone and Android TV apps do.
    func pushCollections(_ raw: [[String: Any]], profileId: Int) async -> Bool {
        guard signedIn else { return false }
        let r = await rpc("sync_push_collections", ["p_profile_id": profileId, "p_collections_json": raw,
                                                     "p_origin_client_id": clientId])
        return r.status < 300
    }

    // MARK: Sync

    private func owner() async -> String? {
        if let ownerId { return ownerId }
        let r = await rpc("get_sync_owner")
        if r.status < 300 {
            if let s = r.json as? String { ownerId = s }
            else if let rows = r.json as? [[String: Any]], let s = rows.first?.values.first as? String { ownerId = s }
            else if let obj = r.json as? [String: Any], let s = obj.values.first as? String { ownerId = s }
        }
        // Like Android, don't guess: a linked device's data lives under the owner, not its own id.
        return ownerId
    }

    /// The account's profiles, for the "Who's watching?" screen.
    func pullProfiles(into profiles: ProfileStore) async {
        guard signedIn else { return }
        let r = await rpc("sync_pull_profiles")
        guard let rows = r.json as? [[String: Any]] else { return }
        func text(_ value: Any?) -> String? { (value as? String).flatMap { $0.isEmpty ? nil : $0 } }
        var catalog: [String: String] = [:]
        if rows.contains(where: { text($0["avatar_url"]) == nil && text($0["avatar_id"]) != nil }) {
            catalog = await avatarCatalog()
        }
        let remote = rows.compactMap { row -> Profile? in
            guard let index = row["profile_index"] as? Int else { return nil }
            let name = text(row["name"]) ?? (index == 1 ? "Main" : "Profile \(index)")
            var p = Profile(id: index, name: name, colorHex: row["avatar_color_hex"] as? String ?? "#1E88E5",
                            usesPrimaryAddons: row["uses_primary_addons"] as? Bool ?? false)
            p.avatarId = text(row["avatar_id"])
            p.avatarURL = text(row["avatar_url"]) ?? p.avatarId.flatMap { catalog[$0] }
            p.backgroundId = text(row["profile_background_id"])
            p.backgroundURL = text(row["profile_background_url"])
            p.usesPrimaryPlugins = row["uses_primary_plugins"] as? Bool
            return p
        }
        // PINs set on the account (from the phone or Android TV) lock the profile here too.
        let locks = (await rpc("sync_pull_profile_locks").json as? [[String: Any]]) ?? []
        let locked = Set(locks.filter { $0["pin_enabled"] as? Bool ?? false }.compactMap { $0["profile_index"] as? Int })
        profiles.adopt(remote: remote.map { var p = $0; p.accountLocked = locked.contains(p.id); return p })
    }

    /// Picture addresses for the account's standard avatars, by id.
    private func avatarCatalog() async -> [String: String] {
        let rows = (await rpc("get_avatar_catalog").json as? [[String: Any]]) ?? []
        var map: [String: String] = [:]
        for row in rows {
            guard let id = row["id"] as? String, let path = row["storage_path"] as? String, !path.isEmpty else { continue }
            map[id] = path.hasPrefix("http") ? path : "\(Self.serverURL)/storage/v1/object/public/avatars/\(path)"
        }
        return map
    }

    /// Profiles made or changed on this Apple TV go up to the account so other devices see them.
    /// Sends the whole list with every account field, like the Android apps, so nothing set elsewhere is lost.
    func pushProfiles(_ profiles: [Profile]) async {
        guard signedIn else { return }
        func value(_ s: String?) -> Any { s.flatMap { $0.isEmpty ? nil : $0 } ?? NSNull() }
        let rows: [[String: Any]] = profiles.prefix(ProfileStore.maxProfiles).map { p in
            let ownPicture = p.avatarURL.flatMap { $0.hasPrefix(Self.serverURL + "/storage/v1/object/public/avatars/") ? nil : $0 }
            return ["profile_index": p.id, "name": p.name, "avatar_color_hex": p.colorHex,
                    "uses_primary_addons": p.usesPrimaryAddons, "uses_primary_plugins": p.usesPrimaryPlugins ?? false,
                    "avatar_id": ownPicture == nil ? value(p.avatarId) : NSNull(), "avatar_url": value(ownPicture),
                    "profile_background_id": value(p.backgroundId), "profile_background_url": value(p.backgroundURL)]
        }
        _ = await rpc("sync_push_profiles", ["p_profiles": rows, "p_client_max_profiles": ProfileStore.maxProfiles,
                                             "p_origin_client_id": clientId])
    }

    /// Clears a removed profile's add-ons, library and progress on the account.
    func deleteProfileData(_ profileId: Int) async {
        guard signedIn, profileId != 1 else { return }
        _ = await rpc("sync_delete_profile_data", ["p_profile_id": profileId])
    }

    enum PinResult { case ok, currentPinRequired, failed }

    /// Sets a profile PIN on the account, so the phone and Android TV ask for it too.
    func setPin(profileId: Int, pin: String, currentPin: String?) async -> PinResult {
        var params: [String: Any] = ["p_profile_id": profileId, "p_pin": pin]
        if let currentPin { params["p_current_pin"] = currentPin }
        let r = await rpc("set_profile_pin", params)
        if r.status < 300 { return .ok }
        let text = String(describing: r.json ?? "").lowercased()
        return text.contains("current pin is required") ? .currentPinRequired : .failed
    }

    func clearPin(profileId: Int, currentPin: String?) async -> Bool {
        var params: [String: Any] = ["p_profile_id": profileId]
        if let currentPin { params["p_current_pin"] = currentPin }
        return await rpc("clear_profile_pin", params).status < 300
    }

    /// Checks a PIN set on the account. True when it opens the profile.
    func verifyPin(profileId: Int, pin: String) async -> Bool {
        let r = await rpc("verify_profile_pin", ["p_profile_id": profileId, "p_pin": pin])
        let row = (r.json as? [[String: Any]])?.first ?? (r.json as? [String: Any])
        return row?["unlocked"] as? Bool ?? false
    }

    // MARK: Plugin repositories (shared with the phone and Android TV)

    /// All of the profile's plugin rows on the account, in order. Nil when they could not be read.
    private func pluginRows(profileId: Int) async -> [[String: Any]]? {
        guard let owner = await owner() else { return nil }
        let r = await call("/rest/v1/plugins?select=url,name,enabled,sort_order,repo_type&user_id=eq.\(owner)&profile_id=eq.\(profileId)&order=sort_order.asc",
                           body: nil, method: "GET")
        return r.status < 300 ? r.json as? [[String: Any]] : nil
    }

    /// The JavaScript plugin repositories (the kind Apple TV can run) saved on the account for this profile.
    func pullPluginRepos(profileId: Int) async -> [PluginStore.Repo]? {
        guard signedIn, let rows = await pluginRows(profileId: profileId) else { return nil }
        return rows.filter { (($0["repo_type"] as? String) ?? "NUVIO_JS") == "NUVIO_JS" }.compactMap { row in
            guard let url = row["url"] as? String else { return nil }
            return PluginStore.Repo(url: url, name: (row["name"] as? String) ?? "Plugins", enabled: row["enabled"] as? Bool ?? true)
        }
    }

    /// Sends this profile's JavaScript repositories up, keeping the account's other repositories (Android-only kinds).
    func pushPluginRepos(_ repos: [PluginStore.Repo], profileId: Int) async {
        guard signedIn, let rows = await pluginRows(profileId: profileId) else { return }
        let others = rows.filter { (($0["repo_type"] as? String) ?? "NUVIO_JS") != "NUVIO_JS" }
        var list: [[String: Any]] = repos.map { ["url": $0.url, "name": $0.name, "enabled": $0.enabled, "repo_type": "NUVIO_JS"] }
        list += others.map { ["url": $0["url"] ?? "", "name": $0["name"] ?? NSNull(), "enabled": $0["enabled"] ?? true,
                              "repo_type": $0["repo_type"] ?? NSNull()] }
        for i in list.indices { list[i]["sort_order"] = i }
        _ = await rpc("sync_push_plugins", ["p_plugins": list, "p_profile_id": profileId, "p_origin_client_id": clientId])
    }

    // MARK: Settings made on Android TV

    /// Takes the playback settings this profile uses on Android TV (autoplay, failover, languages, Skip Intro, theme,
    /// subtitle look) whenever they change there. A change made here stays until Android TV changes them again.
    func pullTVSettings(profileId: Int, settings: SettingsStore) async {
        let r = await rpc("sync_pull_profile_settings_blob", ["p_profile_id": profileId, "p_platform": "tv"])
        let row = (r.json as? [[String: Any]])?.first ?? (r.json as? [String: Any])
        guard r.status < 300, let features = (row?["settings_json"] as? [String: Any])?["features"] as? [String: Any] else { return }
        let player = features["player_settings"] as? [String: Any] ?? [:]
        let theme = features["theme_settings"] as? [String: Any] ?? [:]
        func value(_ group: [String: Any], _ key: String) -> Any? { (group[key] as? [String: Any])?["value"] }
        func bool(_ key: String) -> Bool? { value(player, key) as? Bool }
        func int(_ key: String) -> Int? { (value(player, key) as? NSNumber)?.intValue }
        func string(_ key: String) -> String? { value(player, key) as? String }

        // Only what Android TV actually stored, so a fingerprint tells when it changed there.
        let keys = ["stream_auto_play_mode", "stream_auto_play_next_episode_enabled", "stream_auto_play_fallback_on_failure",
                    "stream_auto_play_fallback_max_sources", "stream_auto_play_fallback_startup_timeout_seconds",
                    "stream_auto_play_reuse_binge_group", "preload_next_episode_sources", "stream_pinned_sources",
                    "skip_intro_enabled", "preferred_audio_language", "subtitle_preferred_language", "subtitle_size",
                    "subtitle_vertical_offset", "subtitle_bold", "subtitle_text_color", "subtitle_background_color",
                    "subtitle_outline_enabled", "subtitle_outline_color"]
        let picked = keys.compactMap { k in value(player, k).map { "\(k)=\($0)" } } + [String(describing: value(theme, "selected_theme") ?? "")]
        let fingerprint = picked.joined(separator: "|")
        let store = ProfileScope.defaults(for: profileId)
        guard fingerprint != store.string(forKey: "tvSettingsFingerprint") else { return }
        store.set(fingerprint, forKey: "tvSettingsFingerprint")

        if let mode = string("stream_auto_play_mode") { settings.streamMode = mode == "MANUAL_USER" ? .manual : .smart }
        if let v = bool("stream_auto_play_next_episode_enabled") { settings.autoNext = v }
        if let v = bool("stream_auto_play_fallback_on_failure") { settings.failoverEnabled = v }
        if let v = int("stream_auto_play_fallback_max_sources"), v > 0 { settings.maxSwitches = v }
        if let v = int("stream_auto_play_fallback_startup_timeout_seconds"), v > 0 { settings.startupTimeout = v }
        if let v = bool("stream_auto_play_reuse_binge_group") { settings.sameRelease = v }
        if let v = bool("preload_next_episode_sources") { settings.preloadNext = v }
        if let v = value(player, "stream_pinned_sources") as? [String] { settings.pinnedSources = v }
        if let v = bool("skip_intro_enabled") { settings.skipIntro = v }
        let device = Locale.current.language.languageCode?.identifier ?? ""
        func code(_ raw: String) -> String {
            let c = raw.lowercased()
            if let hit = Languages.all.first(where: { $0.code == c || $0.alt.contains(c) }) { return hit.code }
            return String(c.prefix(2))
        }
        if let audio = string("preferred_audio_language") {
            switch audio {
            case "default", "original", "": settings.audioLanguage = ""
            case "device": settings.audioLanguage = code(device)
            default: settings.audioLanguage = code(audio)
            }
        }
        if let sub = string("subtitle_preferred_language") {
            switch sub {
            case "none": settings.subtitlesOn = false
            case "forced": break
            case "device", "": settings.subtitlesOn = true; settings.subtitleLanguage = ""
            default: settings.subtitlesOn = true; settings.subtitleLanguage = code(sub)
            }
        }
        var style = settings.subtitleStyle
        if let v = int("subtitle_size") { style.size = min(200, max(50, v)) }
        if let v = int("subtitle_vertical_offset") { style.verticalOffset = min(50, max(-20, v)) }
        if let v = bool("subtitle_bold") { style.bold = v }
        if let v = int("subtitle_text_color") { style.textColor = UInt32(truncatingIfNeeded: v) }
        if let v = int("subtitle_background_color") { style.backgroundColor = UInt32(truncatingIfNeeded: v) }
        if let v = bool("subtitle_outline_enabled") { style.outline = v }
        if let v = int("subtitle_outline_color") { style.outlineColor = UInt32(truncatingIfNeeded: v) }
        if style != settings.subtitleStyle { settings.subtitleStyle = style }
        let themes: [String: MeowioTheme] = ["VIOLET": .meowio, "OCEAN": .ocean, "EMERALD": .emerald, "AMBER": .amber,
                                             "ROSE": .rose, "CRIMSON": .crimson, "SUNSET": .sunset, "AURORA": .aurora,
                                             "MIDNIGHT": .midnight, "WHITE": .white]
        if let name = value(theme, "selected_theme") as? String, let t = themes[name] { settings.theme = t }
    }

    // MARK: Debrid keys (the account's provider credentials, shared with the phone and Android TV)

    private static func credentialId(_ s: DebridService) -> String? {
        switch s {
        case .none: return nil
        case .realDebrid: return "debrid:realdebrid"
        case .torbox: return "debrid:torbox"
        case .premiumize: return "debrid:premiumize"
        }
    }

    /// Brings the account's debrid key to this profile, or sends a key entered here up to the account.
    /// A key changed on this Apple TV since the last sync wins; otherwise the account's key does.
    func syncCredentials(profileId: Int, settings: SettingsStore) async {
        guard signedIn else { return }
        let r = await rpc("sync_pull_provider_credentials", ["p_profile_id": profileId])
        guard r.status < 300, let rows = r.json as? [[String: Any]] else { return }
        var remote: [String: [String: Any]] = [:]
        for row in rows {
            if let id = row["provider"] as? String { remote[id] = row["credential_json"] as? [String: Any] ?? [:] }
        }
        mdblistKey = (remote["mdblist"]?["api_key"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        func key(_ s: DebridService) -> String {
            guard let id = Self.credentialId(s) else { return "" }
            return (remote[id]?["api_key"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let store = ProfileScope.defaults(for: profileId)
        let localKey = settings.debridKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let local = "\(settings.debridService.rawValue)|\(localKey)"
        let synced = store.string(forKey: "syncedDebrid")
        let remoteKey = key(settings.debridService)
        // First sync on this device: the account wins unless it has no key for this service.
        let pushLocal = settings.debridReady && remoteKey != localKey && (synced == nil ? remoteKey.isEmpty : local != synced)
        if pushLocal, let id = Self.credentialId(settings.debridService) {
            // Send every provider back so nothing else on the account is lost.
            remote[id, default: [:]]["api_key"] = localKey
            let list: [[String: Any]] = remote.map { ["provider": $0.key, "credential_json": $0.value] }
            _ = await rpc("sync_push_provider_credentials", ["p_profile_id": profileId, "p_credentials": list,
                                                              "p_origin_client_id": clientId])
        } else if !remoteKey.isEmpty {
            if remoteKey != localKey { settings.debridKey = remoteKey }
        } else if !settings.debridReady, let other = [DebridService.torbox, .premiumize, .realDebrid].first(where: { !key($0).isEmpty }) {
            settings.debridService = other
            settings.debridKey = key(other)
        }
        store.set("\(settings.debridService.rawValue)|\(settings.debridKey.trimmingCharacters(in: .whitespacesAndNewlines))",
                  forKey: "syncedDebrid")
    }

    /// Full two-way sync for one profile: add-ons, My List, progress and watched marks.
    func sync(profile: Profile, settings: SettingsStore, library: LibraryStore) async {
        guard signedIn else { return }
        // A sync already running (sign-in, the scene waking, a profile switch) used to make this one do nothing,
        // so the add-ons of the profile just opened never came down. Wait for it, then run.
        for _ in 0..<120 where busy { try? await Task.sleep(nanoseconds: 500_000_000) }
        guard signedIn, !busy else { return }
        busy = true
        defer { busy = false; lastSync = Date() }
        await syncAddons(profile: profile, settings: settings)
        await syncCredentials(profileId: profile.id, settings: settings)
        await pullTVSettings(profileId: profile.id, settings: settings)
        await syncLibrary(profileId: profile.id, library: library)
        await syncProgress(profileId: profile.id, library: library)
        await syncWatched(profileId: profile.id, library: library)
    }

    private func syncAddons(profile: Profile, settings: SettingsStore) async {
        let addonProfile = profile.usesPrimaryAddons ? 1 : profile.id
        guard let owner = await owner() else { return }
        let r = await call("/rest/v1/addons?select=url,sort_order,enabled&user_id=eq.\(owner)&profile_id=eq.\(addonProfile)&order=sort_order.asc",
                           body: nil, method: "GET")
        guard r.status < 300, let rows = r.json as? [[String: Any]] else { return }
        let remote = rows.filter { ($0["enabled"] as? Bool) ?? true }.compactMap { $0["url"] as? String }
        pulledAddonProfiles.insert(addonProfile)
        if !remote.isEmpty {
            var cleaned: [String] = []
            for raw in remote {
                let s = Self.addonBase(raw)
                if !s.isEmpty, !cleaned.contains(s) { cleaned.append(s) }
            }
            if cleaned != settings.addons { settings.addons = cleaned }
        } else if !settings.addons.isEmpty, !profile.usesPrimaryAddons || profile.id == 1,
                  Set(settings.addons) != Set([AddonClient.cinemeta, AddonClient.openSubtitles]) {
            // An account with no add-ons yet gets the ones set up here, but never just the stock pair.
            await pushAddons(settings.addons, profileId: profile.id)
        }
    }

    /// Profiles whose add-on list has come down from the account since sign-in. Until then nothing is pushed,
    /// so the stock add-ons of a fresh install can't replace the account's list.
    private var pulledAddonProfiles: Set<Int> = []

    /// "https://x/manifest.json?a=b" and "https://x/" both become "https://x" (query kept), like Android.
    static func addonBase(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        var query = ""
        if let q = s.firstIndex(of: "?") { query = String(s[q...]); s = String(s[..<q]) }
        while s.hasSuffix("/") { s.removeLast() }
        if s.lowercased().hasSuffix("/manifest.json") { s = String(s.dropLast("/manifest.json".count)) }
        while s.hasSuffix("/") { s.removeLast() }
        return s.isEmpty ? "" : s + query
    }

    private static func manifestURL(_ base: String) -> String {
        guard let q = base.firstIndex(of: "?") else { return base + "/manifest.json" }
        return String(base[..<q]) + "/manifest.json" + String(base[q...])
    }

    func pushAddons(_ addons: [String], profileId: Int) async {
        guard signedIn, pulledAddonProfiles.contains(profileId) else { return }
        let list: [[String: Any]] = addons.enumerated().map { ["url": Self.manifestURL($0.element), "sort_order": $0.offset, "enabled": true] }
        _ = await rpc("sync_push_addons", ["p_addons": list, "p_profile_id": profileId, "p_origin_client_id": clientId])
    }

    private func syncLibrary(profileId: Int, library: LibraryStore) async {
        var remote: [MetaItem] = []
        var offset = 0
        while offset < 2000 {
            let r = await rpc("sync_pull_library", ["p_profile_id": profileId, "p_limit": 200, "p_offset": offset])
            guard let rows = r.json as? [[String: Any]] else { break }
            for row in rows {
                guard let id = row["content_id"] as? String, let type = row["content_type"] as? String else { continue }
                var m = MetaItem(id: id, type: type, name: row["name"] as? String ?? id)
                m.poster = row["poster"] as? String
                m.background = row["background"] as? String
                m.description = row["description"] as? String
                m.releaseInfo = row["release_info"] as? String
                if let rating = row["imdb_rating"] as? Double { m.imdbRating = String(format: "%.1f", rating) }
                m.genres = row["genres"] as? [String]
                m.addonBase = row["addon_base_url"] as? String
                remote.append(m)
            }
            if rows.count < 200 { break }
            offset += 200
        }
        let missing = library.saved.filter { s in !remote.contains { $0.id == s.id } }
        library.mergeSaved(remote)
        if !missing.isEmpty { await pushLibrary(missing, profileId: profileId) }
    }

    func pushLibrary(_ items: [MetaItem], profileId: Int) async {
        guard signedIn, !items.isEmpty else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let rows: [[String: Any]] = items.map { (m: MetaItem) -> [String: Any] in
            var row: [String: Any] = ["content_id": m.id, "content_type": m.type, "name": m.name, "poster_shape": "POSTER",
                                      "genres": m.genres ?? [], "added_at": now]
            row["poster"] = m.poster ?? NSNull()
            row["background"] = m.background ?? NSNull()
            row["description"] = m.description ?? NSNull()
            row["release_info"] = m.releaseInfo ?? NSNull()
            row["imdb_rating"] = m.imdbRating.flatMap(Double.init) ?? NSNull()
            row["addon_base_url"] = m.addonBase ?? NSNull()
            return row
        }
        _ = await rpc("sync_push_library_items", ["p_items": rows, "p_profile_id": profileId, "p_origin_client_id": clientId])
    }

    func removeFromLibrary(_ item: MetaItem, profileId: Int) async {
        guard signedIn else { return }
        _ = await rpc("sync_delete_library_items", ["p_keys": [["content_id": item.id, "content_type": item.type]],
                                                    "p_profile_id": profileId, "p_origin_client_id": clientId])
    }

    private static func progressKey(contentId: String, videoId: String?) -> String {
        if let ref = EpisodeRef(videoId) { return "\(contentId)_s\(ref.season)e\(ref.episode)" }
        return contentId
    }

    private func syncProgress(profileId: Int, library: LibraryStore) async {
        let r = await rpc("sync_pull_watch_progress", ["p_profile_id": profileId])
        guard let rows = r.json as? [[String: Any]] else { return }
        var needMeta = Set<String>()
        for row in rows {
            guard let contentId = row["content_id"] as? String, let type = row["content_type"] as? String else { continue }
            let videoId = row["video_id"] as? String
            let isEpisode = type == "series" && (row["season"] as? Int) != nil
            let itemId = isEpisode ? (videoId ?? contentId) : contentId
            let position = (row["position"] as? Double ?? 0) / 1000
            let duration = (row["duration"] as? Double ?? 0) / 1000
            let at = Date(timeIntervalSince1970: (row["last_watched"] as? Double ?? 0) / 1000)
            let known = library.continueWatching.first { $0.id == contentId }?.meta
                ?? library.saved.first { $0.id == contentId }
            let meta = known ?? MetaItem(id: contentId, type: type, name: contentId)
            if known == nil { needMeta.insert("\(type)|\(contentId)") }
            library.mergeProgress(itemId: itemId, meta: meta, isEpisode: isEpisode, seconds: position,
                                  duration: duration > 0 ? duration : nil,
                                  completed: duration > 0 && position / duration > 0.92, at: at)
        }
        // Names and artwork for titles this device has never opened.
        for key in needMeta.prefix(20) {
            let parts = key.split(separator: "|", maxSplits: 1).map(String.init)
            if parts.count == 2, let m = await AddonClient.meta(type: parts[0], id: parts[1]) {
                library.refreshContinueMeta(m)
            }
        }
        // Send up what this device has that the account does not.
        let remoteKeys = Set(rows.compactMap { $0["progress_key"] as? String })
        let local = library.records.suffix(200).filter { !$0.completed && $0.seconds > 30 }
        let missing = local.filter { !remoteKeys.contains(Self.progressKey(contentId: $0.contentId, videoId: $0.isEpisode ? $0.id : nil)) }
        if !missing.isEmpty { await pushProgress(Array(missing), profileId: profileId) }
    }

    func pushProgress(_ records: [WatchRecord], profileId: Int, metaType: String? = nil) async {
        guard signedIn, !records.isEmpty else { return }
        let entries: [[String: Any]] = records.map { (r: WatchRecord) -> [String: Any] in
            let ref = r.isEpisode ? EpisodeRef(r.id) : nil
            var e: [String: Any] = [
                "content_id": r.contentId,
                "content_type": metaType ?? (r.isEpisode ? "series" : "movie"),
                "video_id": r.id,
                "position": Int64(r.seconds * 1000),
                "duration": Int64((r.duration ?? 0) * 1000),
                "last_watched": Int64(r.at.timeIntervalSince1970 * 1000),
                "progress_key": Self.progressKey(contentId: r.contentId, videoId: r.isEpisode ? r.id : nil)
            ]
            if let ref { e["season"] = ref.season; e["episode"] = ref.episode }
            return e
        }
        _ = await rpc("sync_push_watch_progress", ["p_entries": entries, "p_profile_id": profileId, "p_origin_client_id": clientId])
    }

    private func syncWatched(profileId: Int, library: LibraryStore) async {
        var page = 1
        var remoteKeys = Set<String>()
        while page <= 20 {
            let r = await rpc("sync_pull_watched_items", ["p_profile_id": profileId, "p_page": page, "p_page_size": 500])
            guard let rows = r.json as? [[String: Any]], !rows.isEmpty else { break }
            for row in rows {
                guard let contentId = row["content_id"] as? String, let type = row["content_type"] as? String else { continue }
                let season = row["season"] as? Int
                let episode = row["episode"] as? Int
                var meta = MetaItem(id: contentId, type: type, name: row["title"] as? String ?? contentId)
                if let known = library.saved.first(where: { $0.id == contentId }) { meta = known }
                let videoId = (season != nil && episode != nil) ? "\(contentId):\(season!):\(episode!)" : nil
                remoteKeys.insert(videoId ?? contentId)
                let at = Date(timeIntervalSince1970: (row["watched_at"] as? Double ?? 0) / 1000)
                library.markWatched(meta: meta, videoId: videoId, notify: false, at: at)
            }
            if rows.count < 500 { break }
            page += 1
        }
        let missing = library.records.filter { $0.completed && !remoteKeys.contains($0.id) }.suffix(300)
        if !missing.isEmpty { await pushWatched(Array(missing), profileId: profileId) }
    }

    func pushWatched(_ records: [WatchRecord], profileId: Int) async {
        guard signedIn, !records.isEmpty else { return }
        let items: [[String: Any]] = records.map { (r: WatchRecord) -> [String: Any] in
            let ref = r.isEpisode ? EpisodeRef(r.id) : nil
            return [
                "content_id": r.contentId,
                "content_type": r.isEpisode ? "series" : "movie",
                "title": r.title,
                "season": ref.map { $0.season as Any } ?? NSNull(),
                "episode": ref.map { $0.episode as Any } ?? NSNull(),
                "watched_at": Int64(r.at.timeIntervalSince1970 * 1000)
            ]
        }
        _ = await rpc("sync_push_watched_items", ["p_items": items, "p_profile_id": profileId, "p_origin_client_id": clientId])
    }

    func removeWatched(_ record: WatchRecord, profileId: Int) async {
        guard signedIn else { return }
        var key: [String: Any] = ["content_id": record.contentId]
        if record.isEpisode, let ref = EpisodeRef(record.id) { key["season"] = ref.season; key["episode"] = ref.episode }
        _ = await rpc("sync_delete_watched_items", ["p_keys": [key], "p_profile_id": profileId])
    }
}

/// Sign in with a code approved from the phone. Used on the Account page and on the first-launch screen.
/// Email sign-in is left out on Apple TV: typing with the remote was unreliable, and the code needs no typing.
struct AccountSignInForm: View {
    @EnvironmentObject var account: AccountStore

    var body: some View {
        if let code = account.linkCode {
            Section {
                HStack(alignment: .center, spacing: 60) {
                    if let qr = QRCode.image(code.url) {
                        Image(uiImage: qr).interpolation(.none).resizable().frame(width: 320, height: 320)
                    }
                    VStack(alignment: .leading, spacing: 20) {
                        Text("Scan the code with your phone, or go to").foregroundStyle(.secondary)
                        Text(code.url.replacingOccurrences(of: "https://", with: "")
                            .components(separatedBy: "?").first ?? code.url).font(.title2.bold())
                        Text("and enter this code:").foregroundStyle(.secondary)
                        Text(code.userCode).font(.system(size: 72, weight: .heavy, design: .monospaced)).kerning(8)
                        HStack(spacing: 14) { ProgressView(); Text("Waiting for you to approve…") }
                    }
                }
                .padding(.vertical, 20)
                Button("Cancel") { account.cancelCodeSignIn() }
            }
        } else {
            Section {
                Button("Sign in with a code") { Task { await account.startCodeSignIn() } }
            } footer: {
                Text("The same Nuvio account as your phone and Android TV. Approve this Apple TV from your phone, no typing needed.")
            }
        }
    }
}

/// What a fresh install opens on, like Android TV: sign in to bring your profiles, add-ons and library, or skip.
struct FirstLaunchSignInView: View {
    @EnvironmentObject var account: AccountStore
    @EnvironmentObject var profiles: ProfileStore
    let onFinish: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Welcome to Meowio").font(.largeTitle.bold())
                        Text("Sign in to bring your profiles, add-ons, debrid keys, My List and watch progress from your phone or Android TV.")
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 10)
                }
                AccountSignInForm()
                if let m = account.message { Text(m).foregroundStyle(.orange) }
                Section {
                    Button("Continue without signing in") {
                        account.cancelCodeSignIn()
                        onFinish()
                    }
                } footer: {
                    Text("You can sign in later in Settings, Account.")
                }
            }
        }
        // Shows the code straight away, like Android TV's first-launch QR screen.
        .task { if account.linkCode == nil { await account.startCodeSignIn() } }
        // Signing in ends here: bring the account's profiles, then open the app.
        .onChange(of: account.signedIn) { _, signedIn in
            guard signedIn else { return }
            Task {
                await account.pullProfiles(into: profiles)
                onFinish()
            }
        }
    }
}

// MARK: - Settings page

struct AccountSettingsView: View {
    @EnvironmentObject var account: AccountStore
    @EnvironmentObject var profiles: ProfileStore
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var library: LibraryStore
    var body: some View {
        List {
            if !AccountStore.available {
                Section {
                    Text("Accounts are off in this build because no account server is set up. Everything still works on this Apple TV, and you can move settings with Backup and restore.")
                        .foregroundStyle(.secondary)
                }
            } else if account.signedIn {
                Section {
                    Text("Signed in as \(account.email ?? "your account")")
                    Button(account.busy ? "Syncing…" : "Sync now") {
                        Task {
                            await account.pullProfiles(into: profiles)
                            if let p = profiles.active { await account.sync(profile: p, settings: settings, library: library) }
                        }
                    }
                    .disabled(account.busy)
                    if let last = account.lastSync {
                        Text("Last synced \(last.formatted(date: .omitted, time: .shortened))").foregroundStyle(.secondary)
                    }
                    Button("Sign out", role: .destructive) { account.signOut() }
                } footer: {
                    Text("Profiles, PINs, add-ons, debrid keys, My List, watch progress and watched episodes sync with your phone and Android TV.")
                }
            } else {
                AccountSignInForm()
            }
            if let m = account.message { Text(m).foregroundStyle(.orange) }
        }
        .navigationTitle("Account")
        .onDisappear { account.cancelCodeSignIn() }
    }
}
