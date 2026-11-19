import SwiftUI

// Party chat: messages are stored by the account server (watch_party_send_message / watch_party_list_messages) and
// arrive live as "chat" broadcasts, the same as in the phone and Android TV apps.

struct PartyChatMessage: Identifiable, Equatable {
    enum Status { case sent, sending, failed }

    var key: String
    var id: Int64?
    var memberId: String
    var name: String
    var body: String
    var sentAt: Int64
    var mine: Bool
    var clientId: String?
    var status: Status = .sent

    static func from(_ p: [String: Any], myMemberId: String) -> PartyChatMessage? {
        guard let rawId = (p["id"] as? Int64) ?? (p["id"] as? Int).map(Int64.init) ?? (p["id"] as? Double).map(Int64.init),
              let member = p["member_id"] as? String, let body = p["body"] as? String else { return nil }
        let sent = (p["sent_at"] as? Int64) ?? (p["sent_at"] as? Int).map(Int64.init) ?? (p["sent_at"] as? Double).map(Int64.init) ?? 0
        return PartyChatMessage(key: "id:\(rawId)", id: rawId, memberId: member, name: p["name"] as? String ?? "",
                                body: body, sentAt: sent, mine: (p["mine"] as? Bool ?? false) || member == myMemberId,
                                clientId: p["client_id"] as? String)
    }

    /// Merges server messages into the list: confirmed ones by id, and own pending ones swapped for their stored copy.
    static func merge(current: [PartyChatMessage], incoming: [PartyChatMessage], replaceOlder: Bool, limit: Int)
        -> (messages: [PartyChatMessage], added: [PartyChatMessage]) {
        let oldest = incoming.compactMap(\.id).min()
        let base = (replaceOlder && oldest != nil) ? current.filter { $0.id == nil || $0.id! >= oldest! } : current
        var confirmed: [Int64: PartyChatMessage] = [:]
        for m in base { if let id = m.id { confirmed[id] = m } }
        var pending = base.filter { $0.id == nil }
        var added: [PartyChatMessage] = []
        for m in incoming.sorted(by: { ($0.id ?? 0) < ($1.id ?? 0) }) {
            guard let id = m.id, confirmed[id] == nil else { continue }
            if m.mine, let cid = m.clientId, let i = pending.firstIndex(where: { $0.key == cid }) {
                var copy = m
                copy.key = pending[i].key
                pending.remove(at: i)
                confirmed[id] = copy
            } else {
                confirmed[id] = m
                added.append(m)
            }
        }
        let merged = confirmed.values.sorted { ($0.id ?? 0) < ($1.id ?? 0) } + pending
        return (Array(merged.suffix(limit)), added)
    }
}

extension PartyRepository {
    static let chatLimit = 500

    func applyChat(_ incoming: [PartyChatMessage], replaceOlder: Bool, notify: Bool) {
        let result = PartyChatMessage.merge(current: chat, incoming: incoming, replaceOlder: replaceOlder, limit: 200)
        chat = result.messages
        guard notify else { return }
        let others = result.added.filter { !$0.mine }
        guard let last = others.last else { return }
        if !chatOpen { chatUnread += others.count }
        showToast("\(last.name.isEmpty ? "Someone" : last.name): \(last.body)")
    }

    func syncChat() async {
        guard let party, let account else { return }
        let after = chatLoaded ? chat.last(where: { $0.id != nil })?.id : nil
        var params: [String: Any] = ["p_party_id": party.id, "p_device_id": account.deviceId]
        if let after { params["p_after"] = after }
        let r = await account.rpcCall("watch_party_list_messages", params)
        guard r.status < 300, let obj = r.json as? [String: Any], obj["error"] == nil,
              self.party?.id == party.id else { return }
        let rows = (obj["messages"] as? [[String: Any]] ?? []).compactMap { PartyChatMessage.from($0, myMemberId: party.memberId) }
        let first = !chatLoaded
        chatLoaded = true
        applyChat(rows, replaceOlder: after != nil && rows.count >= 100, notify: !first)
    }

    func sendChat(_ text: String) {
        let body = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(500))
        guard !body.isEmpty, let party else { return }
        let cid = String((0..<20).map { _ in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".randomElement()! })
        let me = party.member(party.memberId)
        let local = PartyChatMessage(key: cid, id: nil, memberId: party.memberId, name: me?.displayName ?? "", body: body,
                                     sentAt: nowMs(), mine: true, clientId: cid, status: .sending)
        chat.append(local)
        Task { await deliver(local) }
    }

    func retryChat(_ key: String) {
        guard let i = chat.firstIndex(where: { $0.key == key && $0.status == .failed }) else { return }
        chat[i].status = .sending
        let m = chat[i]
        Task { await deliver(m) }
    }

    func resendFailedChat() async {
        for m in chat where m.status == .failed {
            setChatStatus(m.key, .sending)
            await deliver(m)
        }
    }

    private func setChatStatus(_ key: String, _ status: PartyChatMessage.Status) {
        if let i = chat.firstIndex(where: { $0.key == key && $0.id == nil }) { chat[i].status = status }
    }

    private func deliver(_ m: PartyChatMessage) async {
        guard let party, let account else { return }
        let r = await account.rpcCall("watch_party_send_message", ["p_party_id": party.id, "p_device_id": account.deviceId,
                                                                    "p_body": m.body, "p_client_id": m.key])
        guard self.party?.id == party.id else { return }
        if r.status < 300, let obj = r.json as? [String: Any], obj["error"] == nil,
           let sent = obj["message"] as? [String: Any],
           let stored = PartyChatMessage.from(sent, myMemberId: party.memberId) {
            applyChat([stored], replaceOlder: false, notify: false)
        } else {
            setChatStatus(m.key, .failed)
        }
    }
}

/// Chat page: the conversation and a field to type into.
struct PartyChatView: View {
    @EnvironmentObject var party: PartyRepository
    @State private var draft = ""

    var body: some View {
        List {
            Section {
                if party.chat.isEmpty { Text("No messages yet. Say hi.").foregroundStyle(.secondary) }
                ForEach(party.chat) { m in
                    VStack(alignment: m.mine ? .trailing : .leading, spacing: 4) {
                        Text(m.mine ? "You" : (m.name.isEmpty ? "Member" : m.name)).font(.caption).foregroundStyle(.secondary)
                        Text(m.body)
                        if m.status == .sending { Text("Sending…").font(.caption2).foregroundStyle(.secondary) }
                        if m.status == .failed {
                            Button("Failed. Tap to retry") { party.retryChat(m.key) }.font(.caption)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: m.mine ? .trailing : .leading)
                }
            }
            Section {
                TextField("Message", text: $draft)
                    .onSubmit { send() }
                Button("Send") { send() }.disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .meowioPageTitle("Party chat")
        .onAppear { party.chatOpen = true }
        .onDisappear { party.chatOpen = false }
    }

    private func send() {
        party.sendChat(draft)
        draft = ""
    }
}
