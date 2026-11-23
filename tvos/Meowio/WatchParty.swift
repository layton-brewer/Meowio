import SwiftUI
import UIKit
import AVFoundation

// Watch party: the same rooms the Android and phone apps use. Needs the Nuvio account (the account server runs the
// rooms). Playback state travels over the account server's live channel, a room's members are listed by the server,
// and every member's player follows the same position, pause and speed.

// MARK: - Models (wire format shared with the other apps, snake_case on the wire)

struct PartySource: Codable, Hashable {
    var infoHash: String?
    var fileIdx: Int?
    var filename: String?
    var videoSize: Int64?
    var bingeGroup: String?
    var addonId: String?
    var name: String?

    func matches(_ stream: StreamItem) -> Bool {
        if let hash = infoHash?.lowercased(), !hash.isEmpty, let other = stream.infoHash?.lowercased(), !other.isEmpty {
            return hash == other && (fileIdx == nil || stream.fileIdx == nil || fileIdx == stream.fileIdx)
        }
        guard let file = filename?.trimmingCharacters(in: .whitespaces), !file.isEmpty,
              let other = stream.behaviorHints?.filename?.trimmingCharacters(in: .whitespaces), !other.isEmpty else { return false }
        return file.caseInsensitiveCompare(other) == .orderedSame
    }
}

struct PartyMedia: Codable, Hashable {
    var type: String
    var metaId: String
    var metaType: String
    var videoId: String?
    var season: Int?
    var episode: Int?
    var title: String?
    var episodeTitle: String?
    var description: String?
    var poster: String?
    var background: String?
    var logo: String?
    var source: PartySource?

    var key: String {
        [metaType.lowercased(), metaId, season.map(String.init) ?? "", episode.map(String.init) ?? ""].joined(separator: "|")
    }

    var displayTitle: String {
        let name = title ?? metaId
        if let season, let episode { return "\(name) S\(season) E\(episode)" }
        return name
    }

    /// Everything but the chosen source, so a host's source change does not look like a new title.
    func sameContent(as other: PartyMedia) -> Bool { key == other.key }
}

struct PartyStream: Codable, Hashable {
    var mediaKey: String
    var url: String
    var headers: [String: String]?
    var name: String?
    var description: String?
    var addonName: String?
    var filename: String?
}

struct PartyPlayback: Codable, Hashable {
    var seq: Int64
    var memberId: String?
    var paused: Bool
    var positionMs: Int64
    var rate: Double
    var at: Int64
    var mediaKey: String?
    var waiting: Bool

    enum CodingKeys: String, CodingKey { case seq, memberId, paused, positionMs, rate, at, mediaKey, waiting }

    init(seq: Int64, memberId: String?, paused: Bool, positionMs: Int64, rate: Double, at: Int64, mediaKey: String?, waiting: Bool) {
        self.seq = seq; self.memberId = memberId; self.paused = paused; self.positionMs = positionMs
        self.rate = rate; self.at = at; self.mediaKey = mediaKey; self.waiting = waiting
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        seq = (try? c.decode(Int64.self, forKey: .seq)) ?? 0
        memberId = try? c.decodeIfPresent(String.self, forKey: .memberId)
        paused = (try? c.decode(Bool.self, forKey: .paused)) ?? false
        positionMs = (try? c.decode(Int64.self, forKey: .positionMs)) ?? 0
        rate = (try? c.decode(Double.self, forKey: .rate)) ?? 1
        at = (try? c.decode(Int64.self, forKey: .at)) ?? 0
        mediaKey = try? c.decodeIfPresent(String.self, forKey: .mediaKey)
        waiting = (try? c.decode(Bool.self, forKey: .waiting)) ?? false
    }
}

struct PartyMember: Codable, Hashable, Identifiable {
    var id: String
    var displayName: String
    var avatarUrl: String?
    var avatarColor: String?
}

struct PartyPreview: Codable {
    var media: PartyMedia?
    var host: PartyMember?
    var members: Int?
    var maxMembers: Int?
    var error: String?

    var isFull: Bool {
        guard let members, let maxMembers else { return false }
        return maxMembers > 0 && members >= maxMembers
    }
}

enum PartyStatus {
    static let browsing = "browsing", loading = "loading", buffering = "buffering", ready = "ready", behind = "behind"
}

struct PartyPresence: Codable, Hashable {
    var memberId: String
    var status: String?
    var mediaKey: String?
    var behindMs: Int64?

    var statusValue: String { status ?? PartyStatus.browsing }
}

struct PartyInfo: Codable {
    var id: String
    var code: String
    var hostMemberId: String?
    var hostOnly: Bool?
    var media: PartyMedia
    var playback: PartyPlayback?
    var maxMembers: Int?
}

struct PartySnapshot: Codable {
    var party: PartyInfo?
    var members: [PartyMember]?
    var memberId: String?
    var serverTimeMs: Int64?
    var error: String?
    var ok: Bool?
}

enum PartyError: String {
    case notFound, full, removed, ended, rateLimited, signInRequired, unavailable, network

    var message: String {
        switch self {
        case .notFound: return "No watch party has that code."
        case .full: return "That watch party is full."
        case .removed: return "You were removed from the watch party."
        case .ended: return "That watch party has ended."
        case .rateLimited: return "Too many tries. Wait a moment and try again."
        case .signInRequired: return "Sign in to your Nuvio account first (Settings, Nuvio account)."
        case .unavailable: return "Watch party is not available on this account server."
        case .network: return "Could not reach the watch party. Try again."
        }
    }

    static func from(_ code: String?) -> PartyError? {
        switch code {
        case nil: return nil
        case "party_not_found": return .notFound
        case "party_full": return .full
        case "removed", "not_member": return .removed
        case "party_ended": return .ended
        case "rate_limited": return .rateLimited
        case "unavailable": return .unavailable
        default: return .network
        }
    }
}

/// A request to open the party's title. A new value each time, so opening it again after closing it works.
struct PartyLaunch: Equatable {
    let id = UUID()
    let media: PartyMedia
}

struct ActiveParty: Equatable {
    var id: String
    var code: String
    var memberId: String
    var hostMemberId: String?
    var members: [PartyMember]
    var presence: [String: PartyPresence]
    var media: PartyMedia
    var maxMembers: Int
    var connected: Bool
    var hostOnly: Bool
    var sharing: Bool

    static func == (a: ActiveParty, b: ActiveParty) -> Bool {
        a.id == b.id && a.memberId == b.memberId && a.hostMemberId == b.hostMemberId && a.members == b.members
            && a.presence == b.presence && a.media == b.media && a.connected == b.connected
            && a.hostOnly == b.hostOnly && a.sharing == b.sharing
    }

    var isHost: Bool { memberId == hostMemberId }
    var canControl: Bool { !hostOnly || isHost }
    func member(_ id: String?) -> PartyMember? { members.first { $0.id == id } }
    var formattedCode: String { PartyCode.format(code) }
}

enum PartyCode {
    static let alphabet = Set("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
    static let length = 6
    static func normalize(_ input: String) -> String { String(input.uppercased().filter { alphabet.contains($0) }) }
    static func isValid(_ input: String) -> Bool { normalize(input).count == length }
    static func format(_ code: String) -> String {
        let n = normalize(code)
        return n.count == length ? "\(n.prefix(3)) \(n.dropFirst(3))" : n
    }
}

// MARK: - JSON helpers

private enum PartyJSON {
    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()
    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.keyEncodingStrategy = .convertToSnakeCase
        return e
    }()

    static func decode<T: Decodable>(_ type: T.Type, from json: Any?) -> T? {
        guard let json, JSONSerialization.isValidJSONObject(json),
              let data = try? JSONSerialization.data(withJSONObject: json) else { return nil }
        return try? decoder.decode(type, from: data)
    }

    static func object<T: Encodable>(_ value: T) -> [String: Any]? {
        guard let data = try? encoder.encode(value) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
}

// MARK: - Clock and sync maths

/// Server time, estimated from round trips, so every member shares one timeline.
struct PartyClock {
    private var anchorServerMs: Int64?
    private var anchorUptime: TimeInterval = 0
    private var bestRoundTripMs = Int64.max

    static func uptimeNow() -> TimeInterval { ProcessInfo.processInfo.systemUptime }

    func nowMs() -> Int64 {
        guard let anchorServerMs else { return Int64(Date().timeIntervalSince1970 * 1000) }
        return anchorServerMs + Int64((Self.uptimeNow() - anchorUptime) * 1000)
    }

    mutating func addSample(serverMs: Int64, sentAt: TimeInterval) {
        let roundTrip = Int64(max(0, (Self.uptimeNow() - sentAt) * 1000))
        if roundTrip > bestRoundTripMs { return }
        bestRoundTripMs = roundTrip
        anchorServerMs = serverMs + roundTrip / 2
        anchorUptime = Self.uptimeNow()
    }

    mutating func reset() {
        anchorServerMs = nil
        bestRoundTripMs = Int64.max
    }
}

enum PartyCorrection {
    case inSync
    case seek(Int64)
    case nudge(Double)
}

enum PartySync {
    static let nudgeStartMs: Int64 = 250
    static let nudgeStopMs: Int64 = 80
    static let seekThresholdMs: Int64 = 1_500
    static let pausedToleranceMs: Int64 = 500
    static let nudgeFactor = 0.05

    static func expectedPositionMs(_ p: PartyPlayback, nowMs: Int64, durationMs: Int64 = 0) -> Int64 {
        var position = p.positionMs
        if !p.paused {
            let elapsed = max(0, nowMs - p.at)
            position += Int64(Double(elapsed) * p.rate)
        }
        position = max(0, position)
        return durationMs > 0 ? min(position, durationMs) : position
    }

    static func isNewer(_ candidate: PartyPlayback, than current: PartyPlayback?) -> Bool {
        guard let current else { return true }
        if candidate.seq != current.seq { return candidate.seq > current.seq }
        return (candidate.memberId ?? "") > (current.memberId ?? "")
    }

    static func correction(localMs: Int64, expectedMs: Int64, playback: PartyPlayback, nudging: Bool) -> PartyCorrection {
        let drift = localMs - expectedMs
        let distance = abs(drift)
        if playback.paused { return distance > pausedToleranceMs ? .seek(expectedMs) : .inSync }
        let threshold = nudging ? nudgeStopMs : nudgeStartMs
        if distance > seekThresholdMs { return .seek(expectedMs) }
        if distance > threshold {
            let factor = drift > 0 ? 1 - nudgeFactor : 1 + nudgeFactor
            return .nudge(playback.rate * factor)
        }
        return .inSync
    }

    /// The host while it is in the room, else the first member online. It decides when everyone waits for a slow member.
    static func coordinatorId(_ party: ActiveParty) -> String? {
        let online = Set(party.presence.keys).union([party.memberId])
        if let host = party.hostMemberId, online.contains(host) { return host }
        if party.hostOnly { return party.hostMemberId }
        return party.members.first { online.contains($0.id) }?.id ?? party.memberId
    }

    static func waitingMemberIds(_ party: ActiveParty, ignored: Set<String>) -> [String] {
        party.presence.values
            .filter { $0.mediaKey == party.media.key }
            .filter { $0.statusValue == PartyStatus.loading || $0.statusValue == PartyStatus.buffering }
            .map(\.memberId)
            .filter { !ignored.contains($0) }
    }
}

// MARK: - Live channel (Phoenix protocol of the account server's realtime service)

@MainActor
final class PartyChannel {
    private let topic: String
    private let memberId: String
    private let host: URL
    private let apiKey: String
    private let tokenProvider: () async -> String?
    private var task: URLSessionWebSocketTask?
    private var runner: Task<Void, Never>?
    private var heartbeat: Task<Void, Never>?
    private var refCounter = 0
    private var joinRef: String?
    private var closed = false
    private(set) var connected = false

    var onMessage: ((String, [String: Any]) -> Void)?
    var onPresence: ((_ joins: [String: [String: Any]], _ leaves: [String]) -> Void)?
    var onConnection: ((Bool) -> Void)?

    init?(partyId: String, memberId: String, serverURL: String, apiKey: String, tokenProvider: @escaping () async -> String?) {
        guard var comps = URLComponents(string: serverURL) else { return nil }
        comps.scheme = comps.scheme == "http" ? "ws" : "wss"
        comps.path = "/realtime/v1/websocket"
        comps.queryItems = [URLQueryItem(name: "apikey", value: apiKey), URLQueryItem(name: "vsn", value: "1.0.0")]
        guard let url = comps.url else { return nil }
        self.host = url
        self.apiKey = apiKey
        self.topic = "realtime:watch-party:\(partyId)"
        self.memberId = memberId
        self.tokenProvider = tokenProvider
    }

    func open() {
        guard runner == nil else { return }
        runner = Task { [weak self] in
            while let self, !self.closed {
                await self.runOnce()
                if self.closed { break }
                try? await Task.sleep(nanoseconds: 3_000_000_000)
            }
        }
    }

    func close() {
        closed = true
        runner?.cancel()
        heartbeat?.cancel()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        setConnected(false)
    }

    private func setConnected(_ value: Bool) {
        guard connected != value else { return }
        connected = value
        onConnection?(value)
    }

    private func nextRef() -> String {
        refCounter += 1
        return String(refCounter)
    }

    private func runOnce() async {
        let socket = URLSession.shared.webSocketTask(with: host)
        task = socket
        socket.resume()
        let token = await tokenProvider() ?? apiKey
        let ref = nextRef()
        joinRef = ref
        let config: [String: Any] = [
            "broadcast": ["self": false, "ack": false],
            "presence": ["key": memberId],
            "postgres_changes": [Any](),
            "private": true
        ]
        await send(topic: topic, event: "phx_join", payload: ["config": config, "access_token": token], ref: ref, joinRef: ref)
        heartbeat?.cancel()
        heartbeat = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                if Task.isCancelled { return }
                guard let self else { return }
                await self.send(topic: "phoenix", event: "heartbeat", payload: [:], ref: self.nextRef(), joinRef: nil)
            }
        }
        while !closed {
            do {
                let message = try await socket.receive()
                switch message {
                case .string(let text): handle(text)
                case .data(let data): if let text = String(data: data, encoding: .utf8) { handle(text) }
                @unknown default: break
                }
            } catch {
                break
            }
        }
        heartbeat?.cancel()
        setConnected(false)
        socket.cancel(with: .goingAway, reason: nil)
    }

    private func handle(_ text: String) {
        guard let data = text.data(using: .utf8),
              let msg = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let event = msg["event"] as? String else { return }
        let payload = msg["payload"] as? [String: Any] ?? [:]
        switch event {
        case "phx_reply":
            guard (msg["ref"] as? String) == joinRef else { return }
            setConnected((payload["status"] as? String) == "ok")
        case "phx_error", "phx_close":
            if (msg["topic"] as? String) == topic { setConnected(false) }
        case "broadcast":
            if let name = payload["event"] as? String {
                onMessage?(name, payload["payload"] as? [String: Any] ?? [:])
            }
        case "presence_state":
            onPresence?(Self.states(payload), [])
        case "presence_diff":
            let joins = Self.states(payload["joins"] as? [String: Any] ?? [:])
            let leaves = Array((payload["leaves"] as? [String: Any] ?? [:]).keys)
            onPresence?(joins, leaves)
        default:
            break
        }
    }

    /// presence key -> the member's latest state.
    private static func states(_ raw: [String: Any]) -> [String: [String: Any]] {
        var out: [String: [String: Any]] = [:]
        for (key, value) in raw {
            let metas = (value as? [String: Any])?["metas"] as? [[String: Any]]
            out[key] = metas?.last ?? [:]
        }
        return out
    }

    private func send(topic: String, event: String, payload: [String: Any], ref: String?, joinRef: String?) async {
        guard let task else { return }
        var message: [String: Any] = ["topic": topic, "event": event, "payload": payload]
        message["ref"] = ref ?? NSNull()
        message["join_ref"] = joinRef ?? NSNull()
        guard let data = try? JSONSerialization.data(withJSONObject: message),
              let text = String(data: data, encoding: .utf8) else { return }
        try? await task.send(.string(text))
    }

    func broadcast(_ event: String, _ payload: [String: Any]) async {
        guard connected else { return }
        await send(topic: topic, event: "broadcast",
                   payload: ["type": "broadcast", "event": event, "payload": payload], ref: nextRef(), joinRef: joinRef)
    }

    func track(_ payload: [String: Any]) async {
        guard connected else { return }
        await send(topic: topic, event: "presence",
                   payload: ["type": "presence", "event": "track", "payload": payload], ref: nextRef(), joinRef: joinRef)
    }
}

// MARK: - Repository

@MainActor
final class PartyRepository: ObservableObject {
    @Published private(set) var party: ActiveParty?
    @Published private(set) var busy = false
    @Published private(set) var available = false
    @Published private(set) var playback: PartyPlayback?
    @Published private(set) var sharedStream: PartyStream?
    /// Short message about what just happened in the room ("Sam paused").
    @Published var toast: String?
    /// Set after joining or when the host picks something else: the title every member should be watching.
    @Published private(set) var launch: PartyLaunch?
    /// The party's chat, oldest first.
    @Published var chat: [PartyChatMessage] = []
    @Published var chatUnread = 0
    /// True while the chat page is on screen, so new messages are not counted as unread.
    var chatOpen = false {
        didSet { if chatOpen { chatUnread = 0 } }
    }
    var chatLoaded = false

    weak var account: AccountStore?
    private weak var profiles: ProfileStore?
    private var clock = PartyClock()
    private var channel: PartyChannel?
    private var loops: [Task<Void, Never>] = []
    private var persistTask: Task<Void, Never>?
    private var presenceTask: Task<Void, Never>?
    private var toastTask: Task<Void, Never>?
    private var localPresence: PartyPresence?
    private var ignoredWaiting = Set<String>()
    private var waitStartedAt: Date?
    private var resumedAt: Date?
    private var rejoining = false

    func configure(account: AccountStore, profiles: ProfileStore) {
        self.account = account
        self.profiles = profiles
    }

    var canUse: Bool { available && (account?.signedIn ?? false) }
    func nowMs() -> Int64 { clock.nowMs() }

    func refreshAvailability() async {
        guard AccountStore.available, let account, account.signedIn else { available = false; return }
        let r = await account.rpcCall("watch_party_enabled")
        if r.status < 300, let on = (r.json as? NSNumber)?.boolValue { available = on } else { available = false }
    }

    // MARK: Create, join, leave

    func create(media: PartyMedia) async -> PartyError? {
        await begin("watch_party_create", extra: ["p_media": PartyJSON.object(media) ?? [:], "p_playback": NSNull()])
    }

    func join(code: String) async -> PartyError? {
        let error = await begin("watch_party_join", extra: ["p_code": PartyCode.normalize(code)])
        if error == nil, let media = party?.media { launch = PartyLaunch(media: media) }
        return error
    }

    func preview(code: String) async -> PartyPreview {
        guard let account else { return PartyPreview(error: "network") }
        let r = await account.rpcCall("watch_party_preview", ["p_code": PartyCode.normalize(code)])
        return PartyJSON.decode(PartyPreview.self, from: r.json) ?? PartyPreview(error: "network")
    }

    private func identity() -> [String: Any] {
        let profile = profiles?.active
        let name = (profile?.name.trimmingCharacters(in: .whitespaces)).flatMap { $0.isEmpty ? nil : String($0.prefix(40)) } ?? "Meowio"
        let color = profile?.colorHex
        let validColor = color.flatMap { $0.range(of: "^#[0-9A-Fa-f]{6}$", options: .regularExpression) != nil ? $0 : nil }
        return [
            "p_device_id": account?.deviceId ?? "",
            "p_display_name": name,
            "p_avatar_url": NSNull(),
            "p_avatar_color": validColor.map { $0 as Any } ?? NSNull()
        ]
    }

    private func begin(_ function: String, extra: [String: Any]) async -> PartyError? {
        guard available else { return .unavailable }
        guard let account, account.signedIn else { return .signInRequired }
        busy = true
        defer { busy = false }
        let params = identity().merging(extra) { _, n in n }
        let sentAt = PartyClock.uptimeNow()
        var reply = await account.rpcCall(function, params.merging(["p_profile_index": profiles?.active?.id ?? 1]) { _, n in n })
        if reply.status == 404 { reply = await account.rpcCall(function, params) }
        guard reply.status < 300, let snapshot = PartyJSON.decode(PartySnapshot.self, from: reply.json) else { return .network }
        if let error = PartyError.from(snapshot.error) {
            if error == .unavailable { available = false }
            return error
        }
        guard let active = makeActive(snapshot) else { return .network }
        await stopSession()
        clock.reset()
        if let server = snapshot.serverTimeMs { clock.addSample(serverMs: server, sentAt: sentAt) }
        startSession(active, playback: snapshot.party?.playback)
        return nil
    }

    private func makeActive(_ s: PartySnapshot) -> ActiveParty? {
        guard let info = s.party, let me = s.memberId else { return nil }
        return ActiveParty(id: info.id, code: info.code, memberId: me, hostMemberId: info.hostMemberId,
                           members: s.members ?? [], presence: [:], media: info.media, maxMembers: info.maxMembers ?? 3,
                           connected: false, hostOnly: info.hostOnly ?? false, sharing: false)
    }

    func leave() async {
        guard let current = party, let account else { return }
        await stopSession()
        _ = await account.rpcCall("watch_party_leave", ["p_party_id": current.id, "p_device_id": account.deviceId])
    }

    func end() async {
        guard let current = party, let account else { return }
        let r = await account.rpcCall("watch_party_end", ["p_party_id": current.id, "p_device_id": account.deviceId])
        if PartyJSON.decode(PartySnapshot.self, from: r.json)?.error == nil { await stopSession() }
    }

    func setHostOnly(_ on: Bool) async {
        guard let current = party, current.isHost, current.hostOnly != on, let account else { return }
        update { $0.hostOnly = on }
        let r = await account.rpcCall("watch_party_set_host_only",
                                      ["p_party_id": current.id, "p_device_id": account.deviceId, "p_host_only": on])
        if let snapshot = PartyJSON.decode(PartySnapshot.self, from: r.json), snapshot.party != nil { apply(snapshot) }
        else { update { $0.hostOnly = current.hostOnly } }
    }

    func setSharing(_ on: Bool) async {
        guard let current = party, current.isHost, current.sharing != on else { return }
        update { $0.sharing = on }
        if !on { await sendStream(nil) }
    }

    func shareStream(_ stream: PartyStream?) async {
        guard let current = party, current.isHost, current.sharing, stream != sharedStream else { return }
        await sendStream(stream)
    }

    private func sendStream(_ stream: PartyStream?) async {
        guard let current = party else { return }
        sharedStream = stream
        let value: Any = stream.flatMap { PartyJSON.object($0) }.map { $0 as Any } ?? NSNull()
        await channel?.broadcast("stream", ["member_id": current.memberId, "stream": value])
    }

    // MARK: Session

    private func startSession(_ active: ActiveParty, playback initial: PartyPlayback?) {
        guard let url = URL(string: AccountStore.serverURL), url.host != nil else { return }
        let channel = PartyChannel(partyId: active.id, memberId: active.memberId, serverURL: AccountStore.serverURL,
                                   apiKey: AccountStore.anonKey) { [weak self] in await self?.account?.freshAccessToken() }
        self.channel = channel
        localPresence = nil
        ignoredWaiting.removeAll()
        waitStartedAt = nil
        resumedAt = nil
        playback = (initial?.mediaKey == nil || initial?.mediaKey == active.media.key) ? initial : nil
        party = active
        channel?.onConnection = { [weak self] connected in Task { @MainActor in await self?.onConnection(connected) } }
        channel?.onMessage = { [weak self] event, payload in self?.onMessage(event, payload) }
        channel?.onPresence = { [weak self] joins, leaves in self?.onPresence(joins, leaves) }
        channel?.open()
        loops = [
            Task { [weak self] in await self?.heartbeatLoop() },
            Task { [weak self] in await self?.refineClock() },
            Task { [weak self] in await self?.coordinatorLoop() }
        ]
    }

    private func stopSession() async {
        loops.forEach { $0.cancel() }
        loops = []
        persistTask?.cancel()
        presenceTask?.cancel()
        channel?.close()
        channel = nil
        localPresence = nil
        playback = nil
        sharedStream = nil
        party = nil
        launch = nil
        chat = []
        chatUnread = 0
        chatLoaded = false
    }

    private func finish(_ message: String) {
        guard party != nil else { return }
        Task { [self] in
            await stopSession()
            showToast(message)
        }
    }

    /// Opens the party's current title again (after closing the player).
    func reopen() {
        if let media = party?.media { launch = PartyLaunch(media: media) }
    }

    func showToast(_ text: String) {
        toast = text
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled { self?.toast = nil }
        }
    }

    private func update(_ change: (inout ActiveParty) -> Void) {
        guard var current = party else { return }
        change(&current)
        party = current
    }

    // MARK: Channel events

    private func onConnection(_ connected: Bool) async {
        guard let current = party else { return }
        if !connected {
            update { $0.connected = false; $0.presence = $0.presence.filter { $0.key == current.memberId } }
            return
        }
        update { $0.connected = true }
        let presence = encodePresence(ownPresence(current))
        await channel?.track(presence)
        await channel?.broadcast("status", presence)
        if let pb = playback, let obj = PartyJSON.object(pb) { await channel?.broadcast("state", obj) }
        if current.isHost, current.sharing, let s = sharedStream { await sendStream(s) }
        await channel?.broadcast("state_request", ["member_id": current.memberId])
        await syncChat()
        await resendFailedChat()
    }

    private func onMessage(_ event: String, _ payload: [String: Any]) {
        guard let current = party else { return }
        switch event {
        case "state":
            if let pb = PartyJSON.decode(PartyPlayback.self, from: payload) { accept(pb) }
        case "state_request":
            if (payload["member_id"] as? String) == current.memberId { return }
            Task { [self] in
                await channel?.broadcast("status", encodePresence(ownPresence(current)))
                if current.isHost, current.sharing, let s = sharedStream { await sendStream(s) }
                if let pb = playback, let obj = PartyJSON.object(pb) { await channel?.broadcast("state", obj) }
            }
        case "status":
            if let status = PartyJSON.decode(PartyPresence.self, from: payload), status.memberId != current.memberId {
                if status.statusValue == PartyStatus.ready { ignoredWaiting.remove(status.memberId) }
                update { $0.presence[status.memberId] = status }
            }
        case "control":
            if let hostOnly = payload["host_only"] as? Bool, hostOnly != current.hostOnly {
                update { $0.hostOnly = hostOnly }
                if !current.isHost { showToast(hostOnly ? "Only the host controls playback now." : "Everyone can control playback now.") }
            }
        case "member_joined":
            Task { [self] in await refreshMembers(announce: payload["member_id"] as? String) }
        case "member_left":
            onMemberLeft(memberId: payload["member_id"] as? String, reason: payload["reason"] as? String,
                         hostMemberId: payload["host_member_id"] as? String)
        case "ended":
            if (payload["reason"] as? String) == "disabled" { available = false }
            finish("The watch party ended.")
        case "media":
            if let mediaObject = payload["media"] as? [String: Any], let media = PartyJSON.decode(PartyMedia.self, from: mediaObject) {
                applyMedia(media, PartyJSON.decode(PartyPlayback.self, from: payload["playback"]))
            }
        case "chat":
            if let m = PartyChatMessage.from(payload, myMemberId: current.memberId) { applyChat([m], replaceOlder: false, notify: true) }
        case "stream":
            if current.isHost || (payload["member_id"] as? String) != current.hostMemberId { return }
            if let obj = payload["stream"] as? [String: Any], let stream = PartyJSON.decode(PartyStream.self, from: obj),
               stream.url.lowercased().hasPrefix("http"), !stream.url.contains("://localhost"), !stream.url.contains("://127.") {
                sharedStream = stream
            } else {
                sharedStream = nil
            }
        default:
            break
        }
    }

    private func onPresence(_ joins: [String: [String: Any]], _ leaves: [String]) {
        guard let current = party else { return }
        var presence = current.presence
        for key in leaves where key != current.memberId && joins[key] == nil { presence[key] = nil }
        for (key, state) in joins where key != current.memberId && presence[key] == nil {
            var decoded = PartyJSON.decode(PartyPresence.self, from: state) ?? PartyPresence(memberId: key)
            decoded.memberId = key
            presence[key] = decoded
            if decoded.statusValue == PartyStatus.ready { ignoredWaiting.remove(key) }
        }
        update { $0.presence = presence }
        if !leaves.isEmpty { Task { [self] in await channel?.broadcast("state_request", ["member_id": current.memberId]) } }
    }

    private func onMemberLeft(memberId: String?, reason: String?, hostMemberId: String?) {
        guard let current = party, let memberId else { return }
        if memberId == current.memberId {
            if reason == "removed" { finish(PartyError.removed.message) } else { rejoinOrFinish() }
            return
        }
        let name = current.member(memberId)?.displayName
        if memberId == current.hostMemberId { sharedStream = nil }
        update {
            $0.members.removeAll { $0.id == memberId }
            $0.presence[memberId] = nil
            if let hostMemberId { $0.hostMemberId = hostMemberId }
        }
        ignoredWaiting.remove(memberId)
        if let name { showToast("\(name) left the party.") }
    }

    private func refreshMembers(announce: String?) async {
        guard let current = party, let account else { return }
        let r = await account.rpcCall("watch_party_heartbeat", ["p_party_id": current.id, "p_device_id": account.deviceId])
        guard let snapshot = PartyJSON.decode(PartySnapshot.self, from: r.json) else { return }
        apply(snapshot)
        if let announce, announce != current.memberId, let name = party?.member(announce)?.displayName {
            showToast("\(name) joined the party.")
        }
    }

    private func apply(_ snapshot: PartySnapshot) {
        switch snapshot.error {
        case nil: break
        case "not_member": rejoinOrFinish(); return
        case "party_ended": finish("The watch party ended."); return
        default: return
        }
        guard let info = snapshot.party else { return }
        let members = snapshot.members ?? []
        update {
            $0.members = members
            $0.hostMemberId = info.hostMemberId
            $0.maxMembers = info.maxMembers ?? $0.maxMembers
            $0.hostOnly = info.hostOnly ?? $0.hostOnly
            $0.presence = $0.presence.filter { key, _ in members.contains { $0.id == key } }
        }
        if info.media.key != party?.media.key { applyMedia(info.media, info.playback) }
        if let pb = info.playback { accept(pb) }
    }

    private func applyMedia(_ media: PartyMedia, _ pb: PartyPlayback?) {
        guard let current = party else { return }
        let changed = media.key != current.media.key
        update { $0.media = media }
        if changed {
            playback = (pb?.mediaKey == nil || pb?.mediaKey == media.key) ? pb : nil
            if sharedStream?.mediaKey != media.key { sharedStream = nil }
            ignoredWaiting.removeAll()
            waitStartedAt = nil
            if !current.isHost { launch = PartyLaunch(media: media) }
            showToast("Now playing \(media.displayTitle).")
        } else if let pb {
            accept(pb)
        }
    }

    private func accept(_ candidate: PartyPlayback) {
        guard let current = party else { return }
        if let key = candidate.mediaKey, key != current.media.key { return }
        if current.hostOnly, candidate.memberId != current.hostMemberId { return }
        let previous = playback
        guard PartySync.isNewer(candidate, than: previous) else { return }
        if let previous, previous.paused, !candidate.paused {
            resumedAt = Date()
            if previous.waiting { ignoredWaiting.formUnion(PartySync.waitingMemberIds(current, ignored: [])) }
        }
        playback = candidate
        guard candidate.memberId != current.memberId, let previous, !previous.waiting, !candidate.waiting,
              previous.mediaKey == candidate.mediaKey,
              let name = current.member(candidate.memberId)?.displayName else { return }
        if previous.paused != candidate.paused {
            showToast("\(name) \(candidate.paused ? "paused" : "resumed").")
        } else if abs(previous.rate - candidate.rate) > 0.01 {
            showToast("\(name) changed the speed to \(String(format: "%gx", candidate.rate)).")
        } else if abs(candidate.positionMs - PartySync.expectedPositionMs(previous, nowMs: candidate.at)) > 2_000 {
            showToast("\(name) skipped to \(PartyPlayerBridge.clock(Double(candidate.positionMs) / 1000)).")
        }
    }

    // MARK: Publishing (the player calls these)

    func publishPlayback(paused: Bool, positionMs: Int64, rate: Double, waiting: Bool = false) {
        guard let current = party, current.canControl else { return }
        let at = clock.nowMs()
        let next = nextPlayback(current, paused: paused, positionMs: positionMs, rate: rate, waiting: waiting,
                                mediaKey: current.media.key, at: at)
        if let previous = playback, previous.paused, !paused {
            resumedAt = Date()
            if previous.waiting, !waiting { ignoredWaiting.formUnion(PartySync.waitingMemberIds(current, ignored: [])) }
        }
        if !waiting { waitStartedAt = nil }
        playback = next
        Task { [self] in if let obj = PartyJSON.object(next) { await channel?.broadcast("state", obj) } }
        schedulePersist()
    }

    func publishMedia(_ media: PartyMedia, positionMs: Int64) {
        guard let current = party, current.isHost, media != current.media, let account else { return }
        let sameContent = media.key == current.media.key
        update { $0.media = media }
        var next = playback
        if !sameContent {
            next = nextPlayback(current, paused: false, positionMs: positionMs, rate: 1, waiting: false,
                                mediaKey: media.key, at: clock.nowMs())
            playback = next
        }
        persistTask?.cancel()
        var params: [String: Any] = ["p_party_id": current.id, "p_device_id": account.deviceId,
                                     "p_media": PartyJSON.object(media) ?? [:]]
        if let next, let obj = PartyJSON.object(next) { params["p_playback"] = obj }
        Task { [self] in
            let r = await account.rpcCall("watch_party_update", params)
            if let snapshot = PartyJSON.decode(PartySnapshot.self, from: r.json) { apply(snapshot) }
        }
    }

    func setLocalStatus(_ status: String, mediaKey: String?, behindMs: Int64? = nil) {
        guard let current = party else { return }
        let next = PartyPresence(memberId: current.memberId, status: status, mediaKey: mediaKey, behindMs: behindMs)
        if next == localPresence { return }
        localPresence = next
        if status == PartyStatus.ready { ignoredWaiting.remove(current.memberId) }
        update { $0.presence[current.memberId] = next }
        presenceTask?.cancel()
        presenceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 250_000_000)
            if Task.isCancelled { return }
            await self?.channel?.broadcast("status", self?.encodePresence(next) ?? [:])
        }
    }

    private func nextPlayback(_ party: ActiveParty, paused: Bool, positionMs: Int64, rate: Double, waiting: Bool,
                              mediaKey: String, at: Int64) -> PartyPlayback {
        PartyPlayback(seq: (playback?.seq ?? 0) + 1, memberId: party.memberId, paused: paused,
                      positionMs: max(0, positionMs), rate: min(4, max(0.25, rate)), at: at, mediaKey: mediaKey, waiting: waiting)
    }

    private func schedulePersist() {
        persistTask?.cancel()
        persistTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled, let self, let current = self.party, let pb = self.playback,
                  let account = self.account, let obj = PartyJSON.object(pb) else { return }
            let r = await account.rpcCall("watch_party_update",
                                          ["p_party_id": current.id, "p_device_id": account.deviceId, "p_playback": obj])
            if let snapshot = PartyJSON.decode(PartySnapshot.self, from: r.json) { self.apply(snapshot) }
        }
    }

    private func ownPresence(_ current: ActiveParty) -> PartyPresence {
        localPresence ?? PartyPresence(memberId: current.memberId)
    }

    private func encodePresence(_ p: PartyPresence) -> [String: Any] { PartyJSON.object(p) ?? [:] }

    // MARK: Loops

    private func heartbeatLoop() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 120_000_000_000)
            if Task.isCancelled { return }
            await refreshMembers(announce: nil)
        }
    }

    private func refineClock() async {
        guard let account else { return }
        for _ in 0..<4 {
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
            let sent = PartyClock.uptimeNow()
            let r = await account.rpcCall("watch_party_clock")
            if let ms = (r.json as? NSNumber)?.int64Value { clock.addSample(serverMs: ms, sentAt: sent) }
        }
    }

    /// One member (the host while present) pauses everyone while a member loads or buffers, and resumes when it is ready.
    private func coordinatorLoop() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 500_000_000)
            if Task.isCancelled { return }
            guard let current = party, current.connected else { continue }
            if PartySync.coordinatorId(current) != current.memberId { waitStartedAt = nil; continue }
            guard let pb = playback, pb.mediaKey == nil || pb.mediaKey == current.media.key else { continue }
            let waiting = PartySync.waitingMemberIds(current, ignored: ignoredWaiting)
            if !pb.paused {
                let graceOver = resumedAt.map { Date().timeIntervalSince($0) > 3 } ?? true
                if !waiting.isEmpty, graceOver {
                    let position = PartySync.expectedPositionMs(pb, nowMs: clock.nowMs())
                    publishWaiting(current, pb, paused: true, positionMs: position, waiting: true)
                    waitStartedAt = Date()
                }
            } else if pb.waiting {
                let started = waitStartedAt ?? Date()
                waitStartedAt = started
                if waiting.isEmpty || Date().timeIntervalSince(started) > 15 {
                    ignoredWaiting.formUnion(waiting)
                    publishWaiting(current, pb, paused: false, positionMs: pb.positionMs, waiting: false)
                }
            }
        }
    }

    /// The coordinator may publish even in host-only rooms.
    private func publishWaiting(_ current: ActiveParty, _ pb: PartyPlayback, paused: Bool, positionMs: Int64, waiting: Bool) {
        let next = nextPlayback(current, paused: paused, positionMs: positionMs, rate: pb.rate, waiting: waiting,
                                mediaKey: current.media.key, at: clock.nowMs())
        if pb.paused, !paused { resumedAt = Date() }
        playback = next
        Task { [self] in if let obj = PartyJSON.object(next) { await channel?.broadcast("state", obj) } }
        schedulePersist()
    }

    private func rejoinOrFinish() {
        guard let current = party, !rejoining, let account else { return }
        rejoining = true
        Task { [self] in
            defer { rejoining = false }
            var params = identity()
            params["p_code"] = current.code
            var r = await account.rpcCall("watch_party_join", params.merging(["p_profile_index": profiles?.active?.id ?? 1]) { _, n in n })
            if r.status == 404 { r = await account.rpcCall("watch_party_join", params) }
            let snapshot = PartyJSON.decode(PartySnapshot.self, from: r.json)
            guard let snapshot, snapshot.error == nil, let rejoined = makeActive(snapshot) else {
                await stopSession()
                showToast(snapshot?.error == "removed" ? PartyError.removed.message : PartyError.ended.message)
                return
            }
            if rejoined.memberId == current.memberId {
                update { $0.members = rejoined.members; $0.hostMemberId = rejoined.hostMemberId }
                return
            }
            let keep = playback
            await stopSession()
            startSession(rejoined, playback: keep)
        }
    }
}

// MARK: - Joining a party's title

/// Builds the play request for what a party is watching: the same title and episode, the same release when this
/// device can find it, or the host's shared link.
enum PartyLauncher {
    @MainActor
    static func request(for media: PartyMedia, shared: PartyStream?, settings: SettingsStore, streams: StreamStore,
                        library: LibraryStore, servers: MediaServerStore) async -> PlayRequest? {
        var meta: MetaItem?
        if MediaServerStore.isServerId(media.metaId) {
            meta = await servers.nativeMeta(MetaItem(id: media.metaId, type: media.metaType, name: media.title ?? media.metaId))
        } else {
            meta = await AddonClient.meta(type: media.metaType, id: media.metaId, addons: settings.addons)
        }
        guard let meta else { return nil }
        let itemId = media.videoId ?? media.metaId
        var candidates = await streams.candidates(type: media.metaType, id: itemId, parentId: media.metaId,
                                                  settings: settings, name: meta.name)
        if let src = media.source, let i = candidates.firstIndex(where: { src.matches($0) }), i > 0 {
            candidates.insert(candidates.remove(at: i), at: 0)
        }
        if let shared, shared.mediaKey == media.key, let url = URL(string: shared.url) {
            var s = StreamItem(name: shared.name ?? "Host's link", title: nil, description: shared.description, url: url.absoluteString,
                               infoHash: nil, fileIdx: nil,
                               behaviorHints: StreamItem.BehaviorHints(notWebReady: true, filename: shared.filename,
                                                                       bingeGroup: nil, proxyHeaders: nil),
                               addonName: shared.addonName ?? "Watch party")
            s.isServer = false
            if !candidates.contains(where: { $0.url == s.url }) { candidates.insert(s, at: 0) }
        }
        guard !candidates.isEmpty else { return nil }
        if media.metaType == "series" {
            let ordered = (meta.videos ?? []).filter { ($0.season ?? 0) > 0 }
                .sorted { (($0.season ?? 0), ($0.episode ?? 0)) < (($1.season ?? 0), ($1.episode ?? 0)) }
            if let current = ordered.first(where: { $0.id == itemId }) {
                // A party follows the host's episode, so it never shuffles on its own.
                return DetailView.chain(meta: meta, ordered: ordered, current: current, candidates: candidates,
                                        streams: streams, settings: settings, library: library, shuffle: false)
            }
        }
        var slim = meta
        slim.videos = nil
        return PlayRequest(meta: slim, videoId: media.videoId, title: media.title ?? meta.name, candidates: candidates)
    }
}

extension PlaybackController {
    /// What the party should show for the title playing now.
    func currentPartyMedia() -> PartyMedia {
        let meta = request.meta
        let ref = EpisodeRef(request.videoId)
        var media = PartyMedia(type: meta.type, metaId: meta.id, metaType: meta.type, videoId: request.videoId,
                               season: ref?.season, episode: ref?.episode, title: meta.name,
                               episodeTitle: ref == nil ? nil : request.title, description: meta.description,
                               poster: meta.poster, background: meta.background, logo: nil, source: nil)
        if request.candidates.indices.contains(activeIndex) {
            let s = request.candidates[activeIndex]
            media.source = PartySource(infoHash: s.infoHash, fileIdx: s.fileIdx, filename: s.behaviorHints?.filename,
                                       videoSize: nil, bingeGroup: s.behaviorHints?.bingeGroup, addonId: nil, name: s.addonName)
        }
        return media
    }

    /// The link of the source playing now, when it can be shared with other members.
    func currentPartyStream() -> PartyStream? {
        guard request.candidates.indices.contains(activeIndex) else { return nil }
        let s = request.candidates[activeIndex]
        guard let url = s.playableURL?.absoluteString, !s.isServer, url.hasPrefix("http") else { return nil }
        return PartyStream(mediaKey: currentPartyMedia().key, url: url, headers: nil, name: s.name, description: s.description,
                           addonName: s.addonName, filename: s.behaviorHints?.filename)
    }
}

// MARK: - Player bridge

/// Keeps one player in step with the party: publishes the member's own pause, seek and speed changes, and follows the
/// room's. Polls twice a second, which is plenty for lip-sync-level agreement.
@MainActor
final class PartyPlayerBridge {
    private let controller: PlaybackController
    private let party: PartyRepository
    private let settings: SettingsStore
    private let streams: StreamStore
    private let library: LibraryStore
    private let servers: MediaServerStore
    private var timer: Timer?
    private var lastSeq: Int64 = -1
    private var suppressUntil = Date.distantPast
    private var last: (position: Double, paused: Bool, rate: Double, at: Date)?
    private var nudging = false
    private var switching = false
    private var nudgeRate: Double?

    init(controller: PlaybackController, party: PartyRepository, settings: SettingsStore, streams: StreamStore,
         library: LibraryStore, servers: MediaServerStore) {
        self.controller = controller
        self.party = party
        self.settings = settings
        self.streams = streams
        self.library = library
        self.servers = servers
    }

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.tick() }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        party.setLocalStatus(PartyStatus.browsing, mediaKey: nil)
    }

    // The player as one thing, whether AVPlayer or mpv is under it.
    private var position: Double { controller.usingMPV ? controller.mpv.position : controller.player.currentTime().seconds }
    private var duration: Double { controller.usingMPV ? controller.mpv.duration : (controller.player.currentItem?.duration.seconds ?? 0) }
    private var isPaused: Bool {
        controller.usingMPV ? controller.mpv.isPaused : controller.player.timeControlStatus == .paused
    }
    private var isReady: Bool {
        controller.usingMPV ? (controller.mpv.duration > 0) : (controller.player.currentItem?.status == .readyToPlay)
    }
    private var isBuffering: Bool {
        !controller.usingMPV && controller.player.timeControlStatus == .waitingToPlayAtSpecifiedRate
    }
    private var currentRate: Double {
        if let nudgeRate { return nudgeRate }
        return controller.speed
    }

    private func tick() async {
        guard let current = party.party, !switching else { return }
        let key = controller.currentPartyMedia().key
        // The host moved on to another episode (auto next, or picked one): tell the room.
        if current.isHost, key != current.media.key {
            party.publishMedia(controller.currentPartyMedia(), positionMs: 0)
            return
        }
        // The host moved on and this member is still on the old one: switch to what the room is watching.
        if !current.isHost, key != current.media.key {
            await switchTo(current.media)
            return
        }
        if current.isHost, current.sharing { await party.shareStream(controller.currentPartyStream()) }

        let pos = position.isFinite ? position : 0
        let status: String = !isReady ? PartyStatus.loading : (isBuffering ? PartyStatus.buffering : PartyStatus.ready)
        party.setLocalStatus(status, mediaKey: key)
        guard isReady else { last = nil; return }

        let now = Date()
        let paused = isPaused
        // A new party has no playback state yet: the host's player is where it starts.
        if current.isHost, party.playback == nil {
            party.publishPlayback(paused: paused, positionMs: Int64(pos * 1000), rate: currentRate)
        }
        // Local changes the member made themselves.
        if now > suppressUntil, let last {
            let elapsed = now.timeIntervalSince(last.at)
            let expectedLocal = last.paused ? last.position : last.position + elapsed * last.rate
            var changed = false
            if paused != last.paused { changed = true }
            else if abs(pos - expectedLocal) > 1.5 { changed = true }
            else if nudgeRate == nil, abs(currentRate - last.rate) > 0.01 { changed = true }
            if changed {
                party.publishPlayback(paused: paused, positionMs: Int64(pos * 1000), rate: currentRate)
                suppressUntil = now.addingTimeInterval(1)
                lastSeq = party.playback?.seq ?? lastSeq
            }
        }
        last = (pos, paused, currentRate, now)

        // The room's state: follow it.
        guard let pb = party.playback, pb.mediaKey == nil || pb.mediaKey == key else { return }
        let expected = PartySync.expectedPositionMs(pb, nowMs: party.nowMs(), durationMs: Int64(max(0, duration.isFinite ? duration : 0) * 1000))
        if pb.seq != lastSeq {
            lastSeq = pb.seq
            if isPaused != pb.paused { setPaused(pb.paused) }
            suppressUntil = now.addingTimeInterval(1.5)
        }
        if pb.paused != paused, now > suppressUntil { setPaused(pb.paused); suppressUntil = now.addingTimeInterval(1.5) }
        switch PartySync.correction(localMs: Int64(pos * 1000), expectedMs: expected, playback: pb, nudging: nudging) {
        case .inSync:
            if nudging { nudging = false; setRate(controller.speed); nudgeRate = nil }
        case .seek(let ms):
            nudging = false
            nudgeRate = nil
            setRate(pb.rate)
            seek(Double(ms) / 1000)
            suppressUntil = now.addingTimeInterval(1.5)
        case .nudge(let rate):
            nudging = true
            nudgeRate = rate
            setRate(rate)
        }
        if abs(pb.rate - controller.speed) > 0.01, !nudging, pb.memberId != party.party?.memberId {
            controller.setSpeed(pb.rate)
        }
    }

    private func switchTo(_ media: PartyMedia) async {
        switching = true
        defer { switching = false }
        guard let next = await PartyLauncher.request(for: media, shared: party.sharedStream, settings: settings,
                                                     streams: streams, library: library, servers: servers) else {
            party.showToast("This device could not find \(media.displayTitle).")
            return
        }
        lastSeq = -1
        last = nil
        controller.replaceRequest(next)
    }

    private func setPaused(_ paused: Bool) {
        if controller.usingMPV {
            if paused { controller.mpv.pause() } else { controller.mpv.play() }
        } else if paused {
            controller.player.pause()
        } else {
            controller.player.play()
        }
    }

    private func setRate(_ rate: Double) {
        if controller.usingMPV {
            controller.mpv.setSpeed(rate)
        } else {
            controller.player.defaultRate = Float(rate)
            if controller.player.rate != 0 { controller.player.rate = Float(rate) }
        }
    }

    private func seek(_ seconds: Double) {
        if controller.usingMPV {
            controller.mpv.seek(to: seconds)
        } else {
            controller.player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        }
    }

    static func clock(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let s = Int(seconds)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                         : String(format: "%d:%02d", s / 60, s % 60)
    }
}

// MARK: - Views

/// Badge in the player corner while in a party, plus what just happened.
struct PartyBadge: View {
    @ObservedObject var party: PartyRepository

    var body: some View {
        VStack(alignment: .trailing, spacing: 10) {
            if let p = party.party {
                HStack(spacing: 12) {
                    Image(systemName: "person.2.fill")
                    Text("\(p.members.count)")
                    Text(p.formattedCode).monospacedDigit()
                    if !p.connected { Image(systemName: "wifi.exclamationmark").foregroundStyle(.orange) }
                }
                .font(.callout.bold())
                .padding(.horizontal, 20).padding(.vertical, 10)
                .glass(radius: 14)
                if let pb = party.playback, pb.waiting {
                    let names = PartySync.waitingMemberIds(p, ignored: []).compactMap { p.member($0)?.displayName }
                    Text(names.isEmpty ? "Waiting for everyone…" : "Waiting for \(names.joined(separator: ", "))…")
                        .font(.caption).padding(.horizontal, 16).padding(.vertical, 8).glass(radius: 12)
                }
            }
            if let toast = party.toast {
                Text(toast).font(.caption).padding(.horizontal, 16).padding(.vertical, 8).glass(radius: 12)
            }
        }
        .padding(.top, 50).padding(.trailing, 60)
        .allowsHitTesting(false)
    }
}

/// Watch party page in Settings: join with a code, or see and leave the current party.
struct PartySettingsView: View {
    @EnvironmentObject var party: PartyRepository
    @EnvironmentObject var account: AccountStore
    @State private var code = ""
    @State private var preview: PartyPreview?
    @State private var message: String?

    var body: some View {
        List {
            if !AccountStore.available {
                Text("Watch party needs the Nuvio account server, which this build does not have.").foregroundStyle(.secondary)
            } else if !account.signedIn {
                Text("Sign in under Settings, Nuvio account, to use watch party.").foregroundStyle(.secondary)
            } else if !party.available {
                Text("Watch party is not available on this account server.").foregroundStyle(.secondary)
            } else if let p = party.party {
                Section("Your party") {
                    HStack { Text("Code"); Spacer(); Text(p.formattedCode).monospacedDigit() }
                    HStack { Text("Watching"); Spacer(); Text(p.media.displayTitle).foregroundStyle(.secondary) }
                    Button("Open what the party is watching") { party.reopen() }
                    NavigationLink { PartyChatView() } label: {
                        HStack {
                            Text("Chat")
                            Spacer()
                            if party.chatUnread > 0 { Text("\(party.chatUnread) new").foregroundStyle(.orange) }
                        }
                    }
                    ForEach(p.members) { m in
                        HStack {
                            Text(m.displayName)
                            if m.id == p.hostMemberId { Text("Host").font(.caption).foregroundStyle(.secondary) }
                            if m.id == p.memberId { Text("You").font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                    if p.isHost {
                        Toggle("Only the host controls playback", isOn: Binding(
                            get: { p.hostOnly }, set: { on in Task { await party.setHostOnly(on) } }))
                        Toggle("Share my stream link with members", isOn: Binding(
                            get: { p.sharing }, set: { on in Task { await party.setSharing(on) } }))
                    }
                    Button("Leave party") { Task { await party.leave() } }
                    if p.isHost { Button("End party for everyone", role: .destructive) { Task { await party.end() } } }
                }
            } else {
                Section {
                    TextField("Party code, such as K7M 2QX", text: $code)
                        .autocorrectionDisabled()
                        .onChange(of: code) { _, new in
                            preview = nil
                            message = nil
                            if PartyCode.isValid(new) { Task { preview = await party.preview(code: new) } }
                        }
                    if let pv = preview {
                        if let error = PartyError.from(pv.error) { Text(error.message).foregroundStyle(.orange) }
                        else if let media = pv.media {
                            Text("\(pv.host?.displayName ?? "Someone") is watching \(media.displayTitle)")
                        }
                    }
                    Button(party.busy ? "Joining…" : "Join party") {
                        Task { if let error = await party.join(code: code) { message = error.message } }
                    }
                    .disabled(party.busy || !PartyCode.isValid(code) || preview?.isFull == true)
                    if let message { Text(message).foregroundStyle(.orange) }
                } header: {
                    Text("Join a watch party")
                } footer: {
                    Text("To host one, press up in the player and choose Watch party, then Start a party. Everyone in the party sees the same moment of the same title, and the room waits when someone is still loading.")
                }
            }
        }
        .meowioPageTitle("Watch party")
        .task { await party.refreshAvailability() }
    }
}

// MARK: - Player menus

enum PartyMenu {
    /// The "Watch party" entry of the system player's transport bar menus.
    @MainActor
    static func make(party: PartyRepository, controller: PlaybackController) -> UIMenu? {
        guard !controller.request.isLive, party.canUse else { return nil }
        var items: [UIMenuElement] = []
        if let p = party.party {
            items.append(UIAction(title: "Code \(p.formattedCode) · \(p.members.count) watching", attributes: .disabled) { _ in })
            if p.isHost {
                items.append(UIAction(title: "Only the host controls playback", state: p.hostOnly ? .on : .off) { _ in
                    Task { @MainActor in await party.setHostOnly(!p.hostOnly) }
                })
                items.append(UIAction(title: "Share my stream link", state: p.sharing ? .on : .off) { _ in
                    Task { @MainActor in await party.setSharing(!p.sharing) }
                })
            }
            let quick = ["👍", "😂", "😮", "Pause for a sec?", "Back in a minute"].map { text in
                UIAction(title: text) { _ in Task { @MainActor in party.sendChat(text) } }
            }
            items.append(UIMenu(title: "Say something", image: UIImage(systemName: "bubble.left.fill"), children: quick))
            items.append(UIAction(title: "Leave party", attributes: .destructive) { _ in
                Task { @MainActor in await party.leave() }
            })
            if p.isHost {
                items.append(UIAction(title: "End party for everyone", attributes: .destructive) { _ in
                    Task { @MainActor in await party.end() }
                })
            }
        } else {
            items.append(UIAction(title: "Start a watch party", image: UIImage(systemName: "person.2.fill")) { _ in
                Task { @MainActor in
                    if let error = await party.create(media: controller.currentPartyMedia()) { party.showToast(error.message) }
                }
            })
        }
        return UIMenu(title: party.party == nil ? "Watch party" : "Party", image: UIImage(systemName: "person.2.fill"), children: items)
    }
}

/// The same menu for the mpv player's own controls.
struct PartyMenuButton: View {
    @ObservedObject var party: PartyRepository
    @ObservedObject var controller: PlaybackController

    var body: some View {
        if !controller.request.isLive, party.canUse {
            Menu {
                if let p = party.party {
                    Text("Code \(p.formattedCode) · \(p.members.count) watching")
                    if p.isHost {
                        Button { Task { await party.setHostOnly(!p.hostOnly) } } label: {
                            Label("Only the host controls playback", systemImage: p.hostOnly ? "checkmark" : "person.2")
                        }
                        Button { Task { await party.setSharing(!p.sharing) } } label: {
                            Label("Share my stream link", systemImage: p.sharing ? "checkmark" : "link")
                        }
                    }
                    Menu("Say something") {
                        ForEach(["👍", "😂", "😮", "Pause for a sec?", "Back in a minute"], id: \.self) { text in
                            Button(text) { party.sendChat(text) }
                        }
                    }
                    Button("Leave party", role: .destructive) { Task { await party.leave() } }
                    if p.isHost { Button("End party for everyone", role: .destructive) { Task { await party.end() } } }
                } else {
                    Button("Start a watch party") {
                        Task {
                            if let error = await party.create(media: controller.currentPartyMedia()) { party.showToast(error.message) }
                        }
                    }
                }
            } label: {
                Label(party.party == nil ? "Watch party" : "Party", systemImage: "person.2.fill")
            }
        }
    }
}
