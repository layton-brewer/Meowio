import SwiftUI

/// Content warnings (IMDb parents guide), shown for a few seconds when a film or episode starts,
/// like the other Meowio apps. Same public source the Android apps use.
enum ParentalGuide {
    struct Warning: Hashable {
        var label: String
        var severity: String
    }

    private static var cache: [String: [Warning]] = [:]

    @MainActor
    static func warnings(for id: String) async -> [Warning] {
        let imdb = String(id.split(separator: ":").first ?? "")
        guard imdb.hasPrefix("tt") else { return [] }
        if let hit = cache[imdb] { return hit }
        guard let url = URL(string: "https://api.tiffara.com/titles/\(imdb)/parentsGuide") else { return [] }
        var r = URLRequest(url: url)
        r.timeoutInterval = 10
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await URLSession.shared.data(for: r),
              ((response as? HTTPURLResponse)?.statusCode ?? 0) / 100 == 2,
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let categories = obj["parentsGuide"] as? [[String: Any]] else { return [] }
        let labels = ["SEXUAL_CONTENT": "Nudity", "VIOLENCE": "Violence", "PROFANITY": "Profanity",
                      "ALCOHOL_DRUGS": "Alcohol/Drugs", "FRIGHTENING_INTENSE_SCENES": "Frightening"]
        let order = ["severe": 0, "moderate": 1, "mild": 2]
        var out: [Warning] = []
        for c in categories {
            guard let label = labels[((c["category"] as? String) ?? "").uppercased()],
                  let breakdowns = c["severityBreakdowns"] as? [[String: Any]] else { continue }
            let votes: (String) -> Int = { level in
                breakdowns.first { (($0["severityLevel"] as? String) ?? "").lowercased() == level }?["voteCount"] as? Int ?? 0
            }
            let none = votes("none")
            guard let top = ["severe", "moderate", "mild"].max(by: { votes($0) < votes($1) }),
                  votes(top) > none else { continue }
            out.append(Warning(label: label, severity: top.capitalized))
        }
        out.sort { (order[$0.severity.lowercased()] ?? 3) < (order[$1.severity.lowercased()] ?? 3) }
        cache[imdb] = out
        return out
    }
}

struct ParentalGuideOverlay: View {
    let warnings: [ParentalGuide.Warning]
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Content warnings").font(.system(size: 24, weight: .bold))
            ForEach(warnings, id: \.self) { w in
                HStack(spacing: 14) {
                    Capsule().fill(w.severity == "Severe" ? Color.red : w.severity == "Moderate" ? .orange : .yellow)
                        .frame(width: 6, height: 26)
                    Text(w.label).font(.system(size: 22, weight: .semibold))
                    Text(w.severity).font(.system(size: 22)).foregroundStyle(.secondary)
                }
            }
        }
        .padding(28)
        .glass()
    }
}
