import SwiftUI

// MARK: - Navigation

/// Opens a title and starts the best source straight away (Continue Watching, the hero's Play button).
struct AutoPlayRoute: Hashable {
    var item: MetaItem
}

extension View {
    /// Detail pages for every navigation stack in the app.
    func meowioDestinations() -> some View {
        self
            .navigationDestination(for: MetaItem.self) { DetailView(item: $0) }
            .navigationDestination(for: AutoPlayRoute.self) { DetailView(item: $0.item, autoplay: true) }
            .navigationDestination(for: FolderRoute.self) { FolderView(route: $0) }
            .navigationDestination(for: AddonClient.AddonCatalog.self) { CatalogSeeAllView(catalog: $0) }
            .navigationDestination(for: CastRoute.self) { CastView(route: $0) }
    }
}

// MARK: - Artwork

/// Remote artwork with a soft placeholder, so rows never jump while images load.
struct Artwork: View {
    let url: URL?
    var placeholder: String = ""
    var contentMode: ContentMode = .fill

    var body: some View {
        AsyncImage(url: url, transaction: Transaction(animation: .easeInOut(duration: 0.25))) { phase in
            switch phase {
            case .success(let image):
                image.resizable().aspectRatio(contentMode: contentMode)
            default:
                ZStack {
                    Rectangle().fill(.ultraThinMaterial)
                    if !placeholder.isEmpty {
                        Text(placeholder).font(.callout.bold()).multilineTextAlignment(.center).padding()
                    }
                }
            }
        }
    }
}

/// Full-screen backdrop behind Home and detail pages, faded into the theme colour.
struct Backdrop: View {
    let url: URL?
    let background: Color
    /// When set, this title's trailer plays muted over the art after a short pause (Home's header).
    var trailerFor: MetaItem? = nil

    var body: some View {
        ZStack {
            background
            if let url {
                Artwork(url: url)
                    .id(url)
                    .transition(.opacity)
            }
            if let trailerFor { HeroTrailerLayer(item: trailerFor).id(trailerFor.id) }
            LinearGradient(colors: [background, background.opacity(0.75), background.opacity(0.15)],
                           startPoint: .leading, endPoint: .trailing)
            LinearGradient(colors: [.clear, background.opacity(0.6), background],
                           startPoint: .center, endPoint: .bottom)
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.4), value: url)
    }
}

// MARK: - Cards

/// Android TV's card focus: a white ring round the art and a small lift, instead of tvOS's big tilt and shadow.
struct FocusRingCardStyle: ButtonStyle {
    var radius: CGFloat = 24
    func makeBody(configuration: Configuration) -> some View {
        FocusRingCard(configuration: configuration, radius: radius)
    }

    private struct FocusRingCard: View {
        let configuration: ButtonStyleConfiguration
        let radius: CGFloat
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .scaleEffect(configuration.isPressed ? 0.98 : (focused ? 1.04 : 1))
                .animation(.easeOut(duration: 0.18), value: focused)
        }
    }
}

extension ButtonStyle where Self == FocusRingCardStyle {
    static var focusRing: FocusRingCardStyle { FocusRingCardStyle() }
}

/// White focus ring drawn round card art, like Android TV.
private struct FocusRing: ViewModifier {
    let focused: Bool
    let radius: CGFloat
    func body(content: Content) -> some View {
        content
            .clipShape(RoundedRectangle(cornerRadius: radius))
            .overlay(RoundedRectangle(cornerRadius: radius).strokeBorder(.white, lineWidth: focused ? 4 : 0))
    }
}

/// Poster size on Home and in rows: Android TV's Modern view poster (126 dp × 0.84 × 1.08) at Apple TV's scale.
enum CardMetrics {
    static let posterWidth: CGFloat = 228
    static let posterHeight: CGFloat = 342
    static let wideWidth: CGFloat = 418
    static let wideHeight: CGFloat = 236
    static let radius: CGFloat = 24
    static let spacing: CGFloat = 24
    static let rowInset: CGFloat = 104
}

struct PosterCard: View {
    let item: MetaItem
    var onFocus: ((MetaItem) -> Void)? = nil
    /// Show Poster Labels: the name and year under the poster.
    var showLabel = true
    /// Landscape Poster Mode: the title's backdrop in a wide card.
    var landscape = false
    @Environment(\.isFocused) private var focused

    private var width: CGFloat { landscape ? CardMetrics.wideWidth : CardMetrics.posterWidth }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Artwork(url: landscape ? item.backgroundURL : item.posterURL, placeholder: item.name)
                .frame(width: width, height: landscape ? CardMetrics.wideHeight : CardMetrics.posterHeight)
                .modifier(FocusRing(focused: focused, radius: CardMetrics.radius))
            if showLabel {
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name)
                    .font(.system(size: 26, weight: .medium))
                    .lineLimit(1)
                    .foregroundStyle(focused ? .primary : .secondary)
                if let year = item.releaseInfo, !year.isEmpty {
                    Text(year).font(.system(size: 22)).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .frame(width: width, alignment: .leading)
            }
        }
        .onChange(of: focused) { _, f in if f { onFocus?(item) } }
    }
}

/// Wide card for Continue Watching, as in the phone and Android TV apps: the episode code, show and
/// episode title over the still, time left in the corner and a progress bar in the theme colour.
struct ContinueCard: View {
    let entry: ContinueItem
    let subtitle: String?
    let progress: Double?
    var minutesLeft: Int? = nil
    var accent: Color = .pink
    var onFocus: ((MetaItem) -> Void)? = nil
    @Environment(\.isFocused) private var focused

    private var episodeTitle: String? {
        guard let vid = entry.videoId else { return nil }
        return entry.meta.videos?.first(where: { $0.id == vid })?.displayTitle
    }

    /// The episode's own still, as Android TV shows, when the add-on has one.
    private var episodeStill: URL? {
        guard let vid = entry.videoId else { return nil }
        return entry.meta.videos?.first(where: { $0.id == vid })?.thumbnail.flatMap(URL.init(string:))
    }

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Artwork(url: episodeStill ?? entry.meta.backgroundURL, placeholder: entry.meta.name)
                .frame(width: CardMetrics.wideWidth, height: CardMetrics.wideHeight)
            LinearGradient(stops: [.init(color: .clear, location: 0.3), .init(color: .black.opacity(0.85), location: 1)],
                           startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 4) {
                if let subtitle {
                    Text(subtitle).font(.system(size: 20, weight: .bold)).kerning(1.5).foregroundStyle(.white.opacity(0.85))
                }
                Text(entry.meta.name).font(.system(size: 28, weight: .semibold)).lineLimit(1)
                if let episodeTitle {
                    Text(episodeTitle).font(.system(size: 22, weight: .medium)).foregroundStyle(.white.opacity(0.75)).lineLimit(1)
                }
                if let progress {
                    GeometryReader { geo in
                        Capsule().fill(accent).frame(width: max(12, geo.size.width * progress))
                    }
                    .frame(height: 6)
                    .padding(.top, 6)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 16)
        }
        .frame(width: CardMetrics.wideWidth, height: CardMetrics.wideHeight)
        .overlay(alignment: .topTrailing) {
            if let minutesLeft {
                Text("\(minutesLeft)m left").font(.system(size: 20, weight: .semibold))
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 10))
                    .padding(14)
            }
        }
        .modifier(FocusRing(focused: focused, radius: CardMetrics.radius))
        .onChange(of: focused) { _, f in if f { onFocus?(entry.meta) } }
    }
}

/// Android TV's episode card: the still with the episode code, title, synopsis and air date laid over it.
struct EpisodeCard: View {
    let video: Video
    let fallback: URL?
    let watched: Bool
    let progress: Double?
    let isNext: Bool
    var rating: Double? = nil
    @Environment(\.isFocused) private var focused

    static let width: CGFloat = 640
    static let height: CGFloat = 414

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Artwork(url: video.thumbnailURL ?? fallback, placeholder: "")
                .frame(width: Self.width, height: Self.height)
            LinearGradient(stops: [.init(color: .clear, location: 0.15), .init(color: .black.opacity(0.85), location: 1)],
                           startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 10) {
                Text("S\(video.season ?? 0) E\(video.episode ?? 0)")
                    .font(.system(size: 22, weight: .bold)).kerning(1.5)
                    .padding(.horizontal, 14).padding(.vertical, 6)
                    .background(.white.opacity(0.18), in: RoundedRectangle(cornerRadius: 10))
                Text(video.displayTitle).font(.system(size: 32, weight: .semibold)).lineLimit(1)
                if let s = video.synopsis {
                    Text(s).font(.system(size: 24)).foregroundStyle(.white.opacity(0.75)).lineLimit(3)
                }
                HStack(spacing: 18) {
                    if let date = Self.airDate(video.released) {
                        Text(date).font(.system(size: 22, weight: .medium)).foregroundStyle(.white.opacity(0.6))
                    }
                    if let rating {
                        // Episode rating, like Android TV's episode ratings.
                        Label(String(format: "%.1f", rating), systemImage: "star.fill")
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(rating >= 8 ? .green : rating >= 6.5 ? .yellow : .orange)
                    }
                }
            }
            .padding(.horizontal, 32).padding(.bottom, 40)
            if let progress {
                Capsule().fill(.white.opacity(0.25)).frame(height: 8)
                    .overlay(alignment: .leading) {
                        GeometryReader { g in Capsule().fill(.white).frame(width: g.size.width * progress) }
                    }
                    .padding(.horizontal, 32).padding(.bottom, 16)
            }
        }
        .frame(width: Self.width, height: Self.height)
        .overlay(alignment: .topTrailing) {
            if watched || isNext {
                Image(systemName: watched ? "checkmark" : "play.fill")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(watched ? .black : .white)
                    .frame(width: 48, height: 48)
                    .background(watched ? AnyShapeStyle(.white) : AnyShapeStyle(.black.opacity(0.55)), in: Circle())
                    .padding(24)
            }
        }
        .modifier(FocusRing(focused: focused, radius: CardMetrics.radius))
    }

    private static let isoDay: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; return f
    }()

    static func airDate(_ released: String?) -> String? {
        guard let released, released.count >= 10, let d = isoDay.date(from: String(released.prefix(10))) else { return nil }
        return d.formatted(date: .abbreviated, time: .omitted)
    }
}

// MARK: - Buttons

/// Android TV's detail-page buttons: a white pill for Play and round glass buttons for the rest,
/// with a white ring when focused.
struct PillButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { StyledBody(configuration: configuration) }
    private struct StyledBody: View {
        let configuration: ButtonStyleConfiguration
        @Environment(\.isFocused) private var focused
        @Environment(\.isEnabled) private var enabled
        var body: some View {
            configuration.label
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(.black)
                .padding(.horizontal, 48).frame(height: 84)
                .background(.white, in: Capsule())
                .overlay(Capsule().strokeBorder(.white.opacity(0.6), lineWidth: focused ? 4 : 0).padding(-8))
                .scaleEffect(focused ? 1.05 : 1)
                .opacity(enabled ? 1 : 0.5)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

struct CircleIconButtonStyle: ButtonStyle {
    var selected = false
    func makeBody(configuration: Configuration) -> some View { StyledBody(configuration: configuration, selected: selected) }
    private struct StyledBody: View {
        let configuration: ButtonStyleConfiguration
        let selected: Bool
        @Environment(\.isFocused) private var focused
        var body: some View {
            configuration.label
                .labelStyle(.iconOnly)
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(focused || selected ? .black : .white)
                .frame(width: 84, height: 84)
                .background(focused || selected ? AnyShapeStyle(.white) : AnyShapeStyle(.white.opacity(0.14)), in: Circle())
                .overlay(Circle().strokeBorder(.white.opacity(0.25), lineWidth: focused ? 0 : 1.5))
                .scaleEffect(focused ? 1.08 : 1)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

/// Season picker chips: the chosen season filled white, like Android TV's season tabs.
struct ChipButtonStyle: ButtonStyle {
    var selected: Bool
    /// When set, the selected chip is filled with this colour (Live TV's groups), otherwise white.
    var accent: Color? = nil
    func makeBody(configuration: Configuration) -> some View { StyledBody(configuration: configuration, selected: selected, accent: accent) }
    private struct StyledBody: View {
        let configuration: ButtonStyleConfiguration
        let selected: Bool
        let accent: Color?
        @Environment(\.isFocused) private var focused
        var body: some View {
            configuration.label
                .font(.system(size: 26, weight: selected ? .bold : .medium))
                .foregroundStyle(selected ? (accent == nil ? .black : .white) : (focused ? .white : .white.opacity(0.7)))
                .padding(.horizontal, 28).frame(height: 60)
                .background(selected ? AnyShapeStyle(accent ?? .white) : AnyShapeStyle(.white.opacity(focused ? 0.2 : 0.08)), in: Capsule())
                .overlay(Capsule().strokeBorder(.white, lineWidth: focused ? 3 : 0))
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

// MARK: - Rows

struct Shelf: View {
    let title: String
    let items: [MetaItem]
    var onFocus: ((MetaItem) -> Void)? = nil
    /// The add-on catalog behind this row; when set, the row ends with a See all card, like Android TV.
    var catalog: AddonClient.AddonCatalog? = nil
    @EnvironmentObject var settings: SettingsStore

    var body: some View {
        let items = settings.visible(self.items)
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.system(size: 32, weight: .semibold)).padding(.leading, CardMetrics.rowInset)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: CardMetrics.spacing) {
                        ForEach(items) { item in
                            NavigationLink(value: item) { PosterCard(item: item, onFocus: onFocus, showLabel: settings.posterLabels,
                                                                  landscape: settings.landscapePosters) }
                                .buttonStyle(.focusRing)
                        }
                        if let catalog {
                            NavigationLink(value: catalog) { SeeAllCard(landscape: settings.landscapePosters) }
                                .buttonStyle(.focusRing)
                        }
                    }
                    .padding(.horizontal, CardMetrics.rowInset)
                    .padding(.vertical, 20)
                }
                .scrollClipDisabled()
            }
            // Each row is one focus section, so Up and Down always land on the row above or below,
            // even when no card sits straight above the focused one.
            .focusSection()
        }
    }
}

/// Posters in a grid that wraps, for Library and search results (Android TV uses grids there, not rows).
struct PosterGrid: View {
    let title: String?
    let items: [MetaItem]

    var body: some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                if let title {
                    Text(title).font(.system(size: 32, weight: .semibold)).padding(.leading, CardMetrics.rowInset)
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: CardMetrics.posterWidth, maximum: CardMetrics.posterWidth),
                                             spacing: CardMetrics.spacing, alignment: .top)],
                          alignment: .leading, spacing: 36) {
                    ForEach(items) { item in
                        NavigationLink(value: item) { PosterCard(item: item) }
                            .buttonStyle(.focusRing)
                    }
                }
                .padding(.horizontal, CardMetrics.rowInset)
                .padding(.vertical, 12)
            }
        }
    }
}

struct ContinueShelf: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: SettingsStore
    var onFocus: ((MetaItem) -> Void)? = nil

    var body: some View {
        if !library.continueWatching.isEmpty && settings.showContinueWatching {
            VStack(alignment: .leading, spacing: 4) {
                Text("Continue Watching").font(.system(size: 32, weight: .semibold)).padding(.leading, CardMetrics.rowInset)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: CardMetrics.spacing) {
                        ForEach(library.continueWatching) { entry in
                            NavigationLink(value: AutoPlayRoute(item: entry.meta)) {
                                ContinueCard(entry: entry, subtitle: subtitle(entry),
                                             progress: library.progress(for: entry.videoId ?? entry.id),
                                             minutesLeft: library.minutesLeft(for: entry.videoId ?? entry.id),
                                             accent: settings.theme.accent, onFocus: onFocus)
                            }
                            .buttonStyle(.focusRing)
                            .contextMenu {
                                if entry.meta.type == "series" {
                                    Button("Mark entire show as unwatched", role: .destructive) {
                                        library.markAllUnwatched(meta: entry.meta)
                                    }
                                }
                                Button("Remove from Continue Watching", role: .destructive) { library.removeContinue(entry.id) }
                            }
                        }
                    }
                    .padding(.horizontal, CardMetrics.rowInset)
                    .padding(.vertical, 20)
                }
                .scrollClipDisabled()
            }
            .focusSection()
            // Entries synced from the account or Trakt can arrive with only an id: fetch their name and artwork.
            .task(id: missingArt.map(\.id)) {
                for entry in missingArt.prefix(30) {
                    if let meta = await AddonClient.meta(type: entry.meta.type.isEmpty ? (entry.videoId == nil ? "movie" : "series") : entry.meta.type,
                                                         id: entry.id, addonBase: entry.meta.addonBase, addons: settings.addons) {
                        library.refreshContinueMeta(meta)
                    }
                }
            }
        }
    }

    private var missingArt: [ContinueItem] {
        library.continueWatching.filter(\.needsDetails)
    }

    private func subtitle(_ entry: ContinueItem) -> String? {
        guard let vid = entry.videoId else { return nil }
        // Cinemeta episode ids look like tt123:1:4.
        let parts = vid.split(separator: ":")
        if parts.count >= 3, let s = Int(parts[parts.count - 2]), let e = Int(parts[parts.count - 1]) {
            return "S\(s) E\(e)"
        }
        return nil
    }
}

// MARK: - Home

/// Home's header, as in Android TV's Modern view: the focused title's logo (or name), its details and
/// description. Nothing in it takes focus; pressing a card opens or resumes that title.
struct HeroHeader: View {
    let item: MetaItem

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HeroTitle(item: item)
            HStack(spacing: 16) {
                let leading = [item.type == "movie" ? "Movie" : "Series", item.genres?.first].compactMap { $0 }
                Text(leading.joined(separator: " • "))
                let trailing = [item.runtime, item.releaseInfo].compactMap { $0 }.filter { !$0.isEmpty }
                ForEach(trailing, id: \.self) { part in
                    Divider().frame(height: 22)
                    Text(part)
                }
                if let r = item.imdbRating, !r.isEmpty {
                    Divider().frame(height: 22)
                    HStack(spacing: 8) {
                        Text("IMDb").font(.system(size: 18, weight: .heavy)).foregroundStyle(.black)
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Color(red: 0.96, green: 0.77, blue: 0.09), in: RoundedRectangle(cornerRadius: 4))
                        Text(r)
                    }
                }
            }
            .font(.system(size: 24, weight: .medium))
            .foregroundStyle(.secondary)
            .lineLimit(1)
            if let d = item.description {
                Text(d)
                    .font(.system(size: 26))
                    .lineSpacing(4)
                    .lineLimit(3)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: 860, alignment: .leading)
            }
        }
        .frame(maxWidth: 960, alignment: .leading)
        .animation(.easeInOut(duration: 0.25), value: item.id)
    }
}

/// The title's logo art, or its name in large type when there is no logo.
struct HeroTitle: View {
    let item: MetaItem
    @State private var logoFailed = false

    var body: some View {
        Group {
            if let url = item.logoURL, !logoFailed {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image): image.resizable().aspectRatio(contentMode: .fit)
                    case .failure: Color.clear.onAppear { logoFailed = true }
                    default: Color.clear
                    }
                }
                .frame(maxWidth: 440, maxHeight: 170, alignment: .leading)
                .frame(height: 170, alignment: .bottomLeading)
            } else {
                Text(item.name)
                    .font(.system(size: 64, weight: .bold))
                    .lineLimit(2)
                    .minimumScaleFactor(0.6)
            }
        }
        .id(item.id)
    }
}

/// Home's backdrop: the art fills the top right, fading into the background to the left and below,
/// so the rows underneath read cleanly (Android TV draws its hero at 72% of the width).
struct HeroBackdrop: View {
    let url: URL?
    let background: Color
    var trailerFor: MetaItem? = nil

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topTrailing) {
                background
                ZStack {
                    if let url {
                        Artwork(url: url).id(url).transition(.opacity)
                    }
                    if let trailerFor { HeroTrailerLayer(item: trailerFor).id(trailerFor.id) }
                    LinearGradient(stops: [.init(color: background, location: 0), .init(color: background.opacity(0.8), location: 0.2),
                                           .init(color: background.opacity(0.3), location: 0.45), .init(color: .clear, location: 0.7)],
                                   startPoint: .leading, endPoint: .trailing)
                    LinearGradient(stops: [.init(color: .clear, location: 0.45), .init(color: background.opacity(0.75), location: 0.8),
                                           .init(color: background, location: 1)],
                                   startPoint: .top, endPoint: .bottom)
                }
                .frame(width: geo.size.width * 0.72, height: geo.size.height * 0.7)
                .clipped()
            }
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.4), value: url)
    }
}

struct UpdatePrompt: Identifiable {
    var tag: String
    var id: String { tag }
}

struct HomeCatalogKey: Hashable {
    var addons: [String]
    var order: [String]
    var hidden: [String]
}

struct AddonRow: Identifiable {
    var catalog: AddonClient.AddonCatalog
    var items: [MetaItem]
    var id: String { catalog.rowId }
}

struct HomeView: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var collections: CollectionsStore
    @EnvironmentObject var servers: MediaServerStore
    @EnvironmentObject var party: PartyRepository
    @State private var serverShelves: [ServerShelf] = []
    @State private var addonRows: [AddonRow] = []
    @State private var loaded = false
    @State private var focusedItem: MetaItem?
    /// The row holding focus, so Modern can scroll it to the top with its title showing.
    @State private var focusedRow: String?
    @State private var heroItem: MetaItem?
    @StateObject private var updates = UpdateChecker()
    @State private var updatePrompt: UpdatePrompt?
    @State private var ambientOn = false

    private var featured: MetaItem? { heroItem ?? library.continueWatching.first?.meta ?? addonRows.first?.items.first }
    /// What the header shows: the focused card, or a featured title before anything is focused.
    private var heroShown: MetaItem? { focusedItem ?? featured }

    /// Setup hints, the update card and an open watch party sit above the rows.
    @ViewBuilder private var notices: some View {
        if settings.addons.isEmpty && !servers.hasEnabled {
            Text("Add a stream add-on or a media server in Settings and Meowio will pick and play sources for you.")
                .padding(24).glass().padding(.horizontal, CardMetrics.rowInset)
        }
        if party.party != nil {
            Button { party.reopen() } label: {
                Label("Watch party \(party.party?.formattedCode ?? "") · open what everyone is watching", systemImage: "person.2.fill")
            }
            .padding(.horizontal, CardMetrics.rowInset)
        }
    }

    /// The rows shared by every layout; Grid shows each catalog as a wrapping grid instead of a row.
    @ViewBuilder private var rowsContent: some View {
        notices
        ContinueShelf(onFocus: { focus($0); focusedRow = "continue" })
            .id("continue")
        ForEach(serverShelves) { shelf in
            Shelf(title: shelf.title, items: shelf.items, onFocus: focus)
        }
        ForEach(collections.ordered) { collection in
            CollectionShelf(collection: collection)
        }
        ForEach(addonRows) { row in
            if settings.homeLayout == .grid {
                GridSection(row: row, onFocus: focus)
            } else {
                Shelf(title: row.catalog.title, items: row.items, onFocus: { focus($0); focusedRow = row.id }, catalog: row.catalog)
                    .id(row.id)
            }
        }
        if loaded && addonRows.isEmpty && !settings.addons.isEmpty {
            Text("No catalogs to show. Check your connection, or turn catalogs on in Settings > Content & Discovery > Home catalogs.")
                .padding(CardMetrics.rowInset)
        }
    }

    /// Modern: the focused title's header on top, the rows in the lower half.
    @ViewBuilder private func modernLayout(_ geo: GeometryProxy) -> some View {
        if !settings.showHero {
            EmptyView()
        } else if let heroShown {
            HeroHeader(item: heroShown)
                .padding(.leading, CardMetrics.rowInset)
                .padding(.top, 56)
        } else {
            Text("Meowio").font(.largeTitle.bold()).foregroundStyle(settings.theme.accent)
                .padding(.leading, CardMetrics.rowInset).padding(.top, 80)
        }
        // The rows fill the lower half, like Android TV's Modern view; the header above follows focus.
        ScrollViewReader { proxy in
            ScrollView {
                // A plain stack keeps every row in place, so focus can always move back up to Continue Watching.
                VStack(alignment: .leading, spacing: 12) {
                    rowsContent
                }
                .padding(.top, 16)
                .padding(.bottom, 80)
            }
            // The focused row goes to the top of the lower half, title and all, instead of being cut at its title.
            .onChange(of: focusedRow) { _, row in
                guard let row else { return }
                withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(row, anchor: .top) }
            }
        }
        // Rows scrolled up out of the lower half are clipped, so they never run over the title's text.
        .frame(height: geo.size.height * (settings.showHero ? 0.5 : 0.88))
        .frame(maxHeight: .infinity, alignment: .bottom)
    }

    /// Classic and Grid: one full-height scroll with an optional featured banner on top; the backdrop follows focus.
    @ViewBuilder private func scrollingLayout(_ geo: GeometryProxy) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if settings.showHero, let featured {
                    FeaturedBanner(item: featured)
                        .frame(minHeight: geo.size.height * 0.62, alignment: .bottomLeading)
                }
                rowsContent
            }
            .padding(.top, 16)
            .padding(.bottom, 80)
        }
    }

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                ZStack(alignment: .topLeading) {
                    HeroBackdrop(url: heroShown?.backgroundURL, background: settings.theme.background,
                                 trailerFor: settings.heroTrailers ? heroShown : nil)
                    switch settings.homeLayout {
                    case .modern: modernLayout(geo)
                    case .classic, .grid: scrollingLayout(geo)
                    }
                }
            }
            .ignoresSafeArea(edges: .bottom)
            .meowioDestinations()
        }
        .task {
            if case .idle = updates.state { await updates.check() }
            if case .available(let tag) = updates.state,
               UserDefaults.standard.string(forKey: "updatePromptDismissed") != tag {
                updatePrompt = UpdatePrompt(tag: tag)
            }
        }
        // A new version is offered once, in a dialog over Home, like Android TV's update prompt.
        .fullScreenCover(item: $updatePrompt) { prompt in
            VStack(spacing: 40) {
                UpdateCard(tag: prompt.tag, url: updates.releaseURL, notes: updates.notes)
                    .frame(maxWidth: 1300)
                Button("Later") {
                    UserDefaults.standard.set(prompt.tag, forKey: "updatePromptDismissed")
                    updatePrompt = nil
                }
            }
            .padding(80)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black.opacity(0.85).ignoresSafeArea())
        }
        // Ambient mode: restarts its countdown on every focus move, so it only starts when Home is left alone.
        .task(id: AmbientKey(minutes: settings.ambientMinutes, focus: focusedItem?.id, on: ambientOn)) {
            guard settings.ambientMinutes > 0, !ambientOn, updatePrompt == nil else { return }
            try? await Task.sleep(for: .seconds(settings.ambientMinutes * 60))
            if !Task.isCancelled, !ambientItems.isEmpty { ambientOn = true }
        }
        .fullScreenCover(isPresented: $ambientOn) {
            AmbientView(items: ambientItems) { ambientOn = false }
        }
        .task(id: servers.connections) {
            serverShelves = await servers.shelves()
        }
        .task(id: HomeCatalogKey(addons: settings.addons, order: settings.homeCatalogOrder, hidden: settings.disabledHomeCatalogs)) {
            // Every add-on's catalogs, Cinemeta's included, in the order set in Home catalogs, minus the hidden ones.
            let hidden = Set(settings.disabledHomeCatalogs)
            let catalogs = Array(settings.orderedHomeCatalogs(await AddonClient.addonCatalogs(settings.addons))
                .filter { !hidden.contains($0.rowId) }.prefix(40))
            var found: [Int: AddonRow] = [:]
            await withTaskGroup(of: (Int, [MetaItem]).self) { group in
                for (index, catalog) in catalogs.enumerated() {
                    group.addTask { (index, await AddonClient.items(in: catalog)) }
                }
                // Rows appear as each catalog arrives instead of after the slowest one.
                for await (index, items) in group where !items.isEmpty {
                    if Task.isCancelled { continue }
                    found[index] = AddonRow(catalog: catalogs[index], items: Array(items.prefix(40)))
                    addonRows = found.keys.sorted().compactMap { found[$0] }
                }
            }
            // A newer load (add-ons arriving from the account) has taken over: leave the rows to it.
            if Task.isCancelled { return }
            if found.isEmpty { addonRows = [] }
            if heroItem == nil { heroItem = addonRows.first?.items.prefix(5).randomElement() }
            loaded = true
        }
    }

    private func focus(_ item: MetaItem) {
        focusedItem = item
    }

    /// Titles with a backdrop for ambient mode, from every Home row.
    private var ambientItems: [MetaItem] {
        var seen = Set<String>()
        return addonRows.flatMap(\.items).filter { $0.background != nil && seen.insert($0.id).inserted }.prefix(60).map { $0 }
    }
}

// MARK: - Search and library

struct SearchView: View {
    @EnvironmentObject var settings: SettingsStore
    @State private var query = ""
    @State private var results: [MetaItem] = []
    @State private var searching = false
    @State private var showSurprise = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    Button { showSurprise = true } label: {
                        Label("Surprise Me", systemImage: "shuffle")
                    }
                    .buttonStyle(ChipButtonStyle(selected: false))
                    .padding(.horizontal, CardMetrics.rowInset)
                    if searching { ProgressView().padding(CardMetrics.rowInset) }
                    // Results as a poster grid, like Android TV's search.
                    PosterGrid(title: "Movies", items: results.filter { $0.type == "movie" })
                    PosterGrid(title: "Series", items: results.filter { $0.type == "series" })
                    if !searching && results.isEmpty && query.count >= 2 {
                        Text("Nothing found for \u{201C}\(query)\u{201D}.").padding(CardMetrics.rowInset)
                    }
                    if query.trimmingCharacters(in: .whitespaces).count < 2 {
                        // Android TV's empty search screen.
                        VStack(spacing: 18) {
                            Image(systemName: "magnifyingglass").font(.system(size: 80)).foregroundStyle(.secondary)
                            Text("Start Searching").font(.system(size: 38, weight: .medium))
                            Text("Enter at least 2 characters").font(.system(size: 24)).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 80)
                    }
                }
                .padding(.bottom, 80)
            }
            .background(settings.theme.background.ignoresSafeArea())
            .meowioDestinations()
        }
        .searchable(text: $query)
        .fullScreenCover(isPresented: $showSurprise) { ZapView() }
        .task(id: query) {
            let q = query.trimmingCharacters(in: .whitespaces)
            guard q.count >= 2 else { results = []; return }
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
            searching = true
            let found = await AddonClient.search(q, addons: settings.addons)
            if Task.isCancelled { return }
            results = found
            searching = false
        }
    }
}

struct LibraryView: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var trakt: TraktStore
    @EnvironmentObject var simkl: SimklStore
    @EnvironmentObject var mdblist: MDBListStore
    /// Android TV's tabs: Saved, Cloud and Lists.
    @State private var tab = "saved"
    /// "all", "meowio", "trakt", "simkl" or "mdblist".
    @State private var showing = "all"
    @State private var kind = "all"
    @State private var sort = "added"
    @State private var genre = ""
    @State private var year = ""
    @State private var watched = "all"

    private struct Service: Identifiable {
        let id: String
        let name: String
        let connected: Bool
    }

    /// Meowio's own list is always there; the others show "Not set up yet" until they are connected.
    private var services: [Service] {
        [Service(id: "meowio", name: "Meowio library", connected: true),
         Service(id: "trakt", name: "Trakt", connected: trakt.connected),
         Service(id: "simkl", name: "Simkl", connected: simkl.connected),
         Service(id: "mdblist", name: "MDBList", connected: mdblist.connected)]
    }

    private func name(_ id: String) -> String {
        id == "all" ? "All services" : (services.first { $0.id == id }?.name ?? "Meowio library")
    }

    private var cloudAvailable: Bool { settings.debridReady && CloudLibrary.supports(settings.debridService) }

    private var fromService: [MetaItem] {
        switch showing {
        case "all": return library.saved
        case "meowio": return library.saved.filter { (library.savedSources[$0.id] ?? []).isEmpty }
        default: return library.saved.filter { (library.savedSources[$0.id] ?? []).contains(showing) }
        }
    }

    private func yearOf(_ m: MetaItem) -> String? {
        guard let r = m.releaseInfo, r.count >= 4 else { return nil }
        let y = String(r.prefix(4))
        return Int(y) == nil ? nil : y
    }

    private func isWatched(_ m: MetaItem) -> Bool {
        library.records.contains { $0.contentId == m.id && $0.completed }
    }

    private var genres: [String] { Array(Set(fromService.flatMap { $0.genres ?? [] })).sorted() }
    private var years: [String] { Array(Set(fromService.compactMap(yearOf))).sorted(by: >) }

    private var visible: [MetaItem] {
        var list = fromService.filter { m in
            (kind == "all" || (kind == "movie" ? m.type == "movie" : m.type != "movie"))
                && (genre.isEmpty || (m.genres ?? []).contains(genre))
                && (year.isEmpty || yearOf(m) == year)
                && (watched == "all" || (watched == "watched") == isWatched(m))
        }
        switch sort {
        case "oldest": list.reverse()
        case "title": list.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        case "year": list.sort { (yearOf($0) ?? "") > (yearOf($1) ?? "") }
        default: break
        }
        return list
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 16) {
                    ForEach([("saved", "Saved"), ("cloud", "Cloud"), ("lists", "Lists")], id: \.0) { id, title in
                        Button { tab = id } label: { Text(title) }
                            .buttonStyle(ChipButtonStyle(selected: tab == id))
                    }
                }
                .padding(.horizontal, CardMetrics.rowInset)
                .padding(.top, 40)
                .padding(.bottom, 20)
                .focusSection()
                switch tab {
                case "cloud":
                    if cloudAvailable {
                        CloudLibraryView()
                    } else {
                        Text("Sign in to Real-Debrid, TorBox or Premiumize in Settings › Integrations › Connected Services to see your cloud files here.")
                            .foregroundStyle(.secondary).padding(CardMetrics.rowInset)
                        Spacer()
                    }
                case "lists":
                    DiscoverListsView()
                default:
                    savedTab
                }
            }
            .background(settings.theme.background.ignoresSafeArea())
            .meowioDestinations()
        }
    }

    private var savedTab: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Android TV's filter boxes.
                Grid(horizontalSpacing: 20, verticalSpacing: 20) {
                    GridRow {
                        FilterBox(label: "Type", value: ["all": "All", "movie": "Movies", "series": "Series"][kind] ?? "All") {
                            choice("All", kind == "all") { kind = "all" }
                            choice("Movies", kind == "movie") { kind = "movie" }
                            choice("Series", kind == "series") { kind = "series" }
                        }
                        .gridCellColumns(3)
                        FilterBox(label: "Sort", value: ["added": "Added \u{2193}", "oldest": "Added \u{2191}", "title": "Title A\u{2013}Z", "year": "Year"][sort] ?? "") {
                            choice("Added \u{2193}", sort == "added") { sort = "added" }
                            choice("Added \u{2191}", sort == "oldest") { sort = "oldest" }
                            choice("Title A\u{2013}Z", sort == "title") { sort = "title" }
                            choice("Year", sort == "year") { sort = "year" }
                        }
                        .gridCellColumns(3)
                    }
                    GridRow {
                        FilterBox(label: "Genre", value: genre.isEmpty ? "All" : genre) {
                            choice("All", genre.isEmpty) { genre = "" }
                            ForEach(genres, id: \.self) { g in choice(g, genre == g) { genre = g } }
                        }
                        .gridCellColumns(2)
                        FilterBox(label: "Year", value: year.isEmpty ? "All" : year) {
                            choice("All", year.isEmpty) { year = "" }
                            ForEach(years, id: \.self) { y in choice(y, year == y) { year = y } }
                        }
                        .gridCellColumns(2)
                        FilterBox(label: "Watched", value: ["all": "All", "watched": "Watched", "unwatched": "Not watched"][watched] ?? "All") {
                            choice("All", watched == "all") { watched = "all" }
                            choice("Watched", watched == "watched") { watched = "watched" }
                            choice("Not watched", watched == "unwatched") { watched = "unwatched" }
                        }
                        .gridCellColumns(2)
                    }
                    GridRow {
                        FilterBox(label: "Show library from", value: name(showing)) {
                            choice("All services", showing == "all") { showing = "all" }
                            ForEach(services) { s in
                                Button { if s.connected { showing = s.id } } label: {
                                    Label(s.connected ? s.name : "\(s.name) \u{00B7} Not set up yet",
                                          systemImage: showing == s.id ? "checkmark" : (s.connected ? "circle" : "lock"))
                                }
                                .disabled(!s.connected)
                            }
                        }
                        .gridCellColumns(2)
                        FilterBox(label: "Primary service (watching syncs to all connected)",
                                  value: library.primaryService == "meowio" ? "Meowio sync" : name(library.primaryService)) {
                            ForEach(services) { s in
                                Button { if s.connected { library.primaryService = s.id } } label: {
                                    Label(s.connected ? (s.id == "meowio" ? "Meowio sync" : s.name) : "\(s.name) \u{00B7} Not set up yet",
                                          systemImage: library.primaryService == s.id ? "checkmark" : (s.connected ? "circle" : "lock"))
                                }
                                .disabled(!s.connected)
                            }
                        }
                        .gridCellColumns(2)
                        Color.clear.gridCellColumns(2).frame(height: 1)
                    }
                }
                .padding(.horizontal, CardMetrics.rowInset)
                .focusSection()

                // Wide cards with the title underneath and a tick on watched titles, like Android TV.
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 36, alignment: .top), count: 4),
                          alignment: .leading, spacing: 40) {
                    ForEach(visible) { item in
                        NavigationLink(value: item) { LibraryCard(item: item, watched: isWatched(item)) }
                            .buttonStyle(.focusRing)
                    }
                }
                .padding(.horizontal, CardMetrics.rowInset)
                .padding(.vertical, 20)

                if visible.isEmpty {
                    Text(library.saved.isEmpty
                         ? "Save titles from their detail page and they will show up here."
                         : (showing == "all" ? "Nothing matches these filters." : "Nothing from \(name(showing)) yet. Sync in Settings to pull its lists in."))
                        .foregroundStyle(.secondary).padding(.horizontal, CardMetrics.rowInset)
                }
            }
            .padding(.bottom, 80)
        }
    }

    private func choice(_ title: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            if on { Label(title, systemImage: "checkmark") } else { Text(title) }
        }
    }
}

/// One of Android TV's library filter boxes: a small grey label over the current value and a chevron.
struct FilterBox<Content: View>: View {
    let label: String
    let value: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        Menu { content() } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.system(size: 18)).foregroundStyle(.secondary).lineLimit(1)
                    Text(value).font(.system(size: 28)).lineLimit(1)
                }
                Spacer(minLength: 10)
                Image(systemName: "chevron.down").font(.system(size: 22, weight: .semibold))
            }
            .padding(.horizontal, 22)
            .frame(maxWidth: .infinity, minHeight: 86, alignment: .leading)
        }
    }
}

/// A saved title in the library grid: its backdrop, the name under it and a tick once watched.
struct LibraryCard: View {
    let item: MetaItem
    let watched: Bool
    @EnvironmentObject var settings: SettingsStore
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Color.clear
                .aspectRatio(16 / 9, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .overlay(Artwork(url: item.backgroundURL, placeholder: item.name))
                .clipShape(RoundedRectangle(cornerRadius: CardMetrics.radius))
                .overlay(alignment: .topTrailing) {
                    if watched {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 34))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, settings.theme.accent)
                            .padding(14)
                    }
                }
                .modifier(FocusRing(focused: focused, radius: CardMetrics.radius))
            Text(item.name)
                .font(.system(size: 26, weight: .medium))
                .lineLimit(1)
                .foregroundStyle(focused ? .primary : .secondary)
        }
    }
}

// MARK: - Detail

struct DetailView: View {
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var streams: StreamStore
    @EnvironmentObject var servers: MediaServerStore
    @EnvironmentObject var plugins: PluginStore
    let item: MetaItem
    var autoplay = false
    @State private var full: MetaItem?
    @State private var season = 1
    @State private var playRequest: PlayRequest?
    @State private var resolving = false
    @State private var message: String?
    @State private var picker: PickerState?
    @State private var didAutoplay = false
    @State private var trailerURL: URL?
    @State private var showTrailer = false
    @State private var moreLikeThis: [MetaItem] = []
    @State private var extras = DetailExtras()
    @State private var rateTarget: RateTarget?
    @EnvironmentObject var trakt: TraktStore
    @FocusState private var playFocused: Bool

    struct RateTarget: Identifiable {
        let id = UUID()
        let videoId: String?
        let title: String
    }

    struct PickerState: Identifiable {
        let id = UUID()
        let videoId: String
        let title: String
        let candidates: [StreamItem]
    }

    private var meta: MetaItem { full ?? item }
    private var seasons: [Int] {
        Array(Set((meta.videos ?? []).compactMap(\.season).filter { $0 > 0 })).sorted()
    }
    private var episodes: [Video] {
        (meta.videos ?? []).filter { $0.season == season }.sorted { ($0.episode ?? 0) < ($1.episode ?? 0) }
    }
    /// Episodes in watching order, specials left out.
    private var ordered: [Video] {
        (meta.videos ?? []).filter { ($0.season ?? 0) > 0 }
            .sorted { (($0.season ?? 0), ($0.episode ?? 0)) < (($1.season ?? 0), ($1.episode ?? 0)) }
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                Backdrop(url: meta.backgroundURL, background: settings.theme.background)
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        // Android TV's detail hero: everything sits at the bottom of the first screen.
                        heroSection
                            .frame(minHeight: geo.size.height - (meta.type == "movie" ? 0 : 170), alignment: .bottomLeading)
                        if meta.type != "movie" { episodesSection.padding(.top, 40) }
                        // Android TV's detail rows: cast, collection, more like this, production, comments.
                        CastPhotoRow(people: extras.people).padding(.top, 40)
                        if let name = extras.collectionName {
                            Shelf(title: name, items: extras.collection)
                                .padding(.top, 40)
                                .padding(.horizontal, 96 - CardMetrics.rowInset)
                        }
                        // Under the cast, like the other apps: similar movies and shows.
                        Shelf(title: "More like this", items: moreLikeThis)
                            .padding(.top, 40)
                            .padding(.horizontal, 96 - CardMetrics.rowInset)
                        CompanyLogosRow(companies: extras.companies).padding(.top, 40)
                        CommentsRow(comments: extras.comments).padding(.top, 40)
                    }
                    .padding(.horizontal, 96)
                    .padding(.bottom, 80)
                }
                .scrollClipDisabled()
            }
        }
        .ignoresSafeArea()
        .task {
            if MediaServerStore.isServerId(item.id) {
                full = await servers.nativeMeta(item)
            } else {
                full = await AddonClient.meta(type: item.type, id: item.id, addonBase: item.addonBase, addons: settings.addons)
            }
            if let resume = library.continueWatching.first(where: { $0.id == item.id })?.videoId,
               let s = (full?.videos ?? []).first(where: { $0.id == resume })?.season, s > 0 {
                season = s
            } else if let first = seasons.first {
                season = seasons.contains(1) ? 1 : first
            }
            playFocused = true
            preloadForOpen()
            if autoplay, !didAutoplay {
                didAutoplay = true
                if meta.type == "movie" {
                    await play(videoId: meta.id, title: meta.name)
                } else if let next = nextUp {
                    await play(videoId: next.id, title: next.displayTitle)
                }
            }
        }
        .task(id: full?.id) {
            guard let loaded = full else { return }
            trailerURL = await Trailers.playableURL(for: loaded)
        }
        .task(id: item.id) {
            guard !MediaServerStore.isServerId(item.id) else { return }
            moreLikeThis = await Self.similarTitles(to: item)
        }
        .task(id: "extras-" + item.id) {
            guard !MediaServerStore.isServerId(item.id) else { return }
            extras = await DetailExtrasLoader.load(for: item)
        }
        .sheet(item: $rateTarget) { target in
            RatingSheet(title: target.title, current: trakt.rating(meta: meta, videoId: target.videoId)) { value in
                trakt.rate(meta: meta, videoId: target.videoId, rating: value)
            }
        }
        .fullScreenCover(item: $playRequest) { req in
            PlayerScreen(request: req, settings: settings)
        }
        .fullScreenCover(isPresented: $showTrailer) {
            if let trailerURL { TrailerPlayerView(url: trailerURL).ignoresSafeArea() }
        }
        .fullScreenCover(item: $picker) { state in
            SourcePicker(state: state) { chosen in
                picker = nil
                start(videoId: state.videoId, title: state.title, candidates: chosen)
            }
        }
    }

    /// Trakt's related titles (public, built-in key), else TMDB recommendations when a TMDB key is set.
    nonisolated static func similarTitles(to item: MetaItem) async -> [MetaItem] {
        let related = await TraktPublic.related(to: item)
        if !related.isEmpty { return related.filter { $0.id != item.id } }
        let imdb = String(item.id.split(separator: ":").first ?? "")
        guard TMDB.available, imdb.hasPrefix("tt"),
              let found = await TMDB.get("/find/\(imdb)", ["external_source": "imdb_id"]) else { return [] }
        let media = item.type == "movie" ? "movie" : "tv"
        let match = (found[media == "movie" ? "movie_results" : "tv_results"] as? [[String: Any]])?.first
        guard let id = match?["id"] as? Int,
              let recs = (await TMDB.get("/\(media)/\(id)/recommendations"))?["results"] as? [[String: Any]] else { return [] }
        return await CollectionsStore.withImdbIds(Array(recs.prefix(20)), defaultMedia: media)
    }

    @ViewBuilder private var heroSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            HeroTitle(item: meta)
                .frame(maxWidth: 900, alignment: .leading)
                .padding(.bottom, 24)
            HStack(spacing: 24) {
                if meta.type == "movie" {
                    Button { Task { await play(videoId: meta.id, title: meta.name) } } label: {
                        Label(resolving ? "Finding best source…" : movieLabel, systemImage: "play.fill")
                    }
                    .buttonStyle(PillButtonStyle())
                    .focused($playFocused)
                    .disabled(resolving)
                } else if let next = nextUp {
                    Button { Task { await play(videoId: next.id, title: next.displayTitle) } } label: {
                        Label(resolving ? "Finding best source…" : nextLabel(next), systemImage: "play.fill")
                    }
                    .buttonStyle(PillButtonStyle())
                    .focused($playFocused)
                    .disabled(resolving)
                }
                Button { library.toggleSaved(meta) } label: {
                    Label(library.isSaved(meta) ? "Remove from library" : "Add to library",
                          systemImage: library.isSaved(meta) ? "bookmark.fill" : "bookmark")
                }
                .buttonStyle(CircleIconButtonStyle(selected: library.isSaved(meta)))
                if meta.type == "movie" {
                    Button {
                        if library.isWatched(meta.id) { library.markUnwatched(meta: meta, videoId: nil) }
                        else { library.markWatched(meta: meta, videoId: nil) }
                    } label: {
                        Label(library.isWatched(meta.id) ? "Mark unwatched" : "Mark watched",
                              systemImage: library.isWatched(meta.id) ? "eye.fill" : "eye.slash")
                    }
                    .buttonStyle(CircleIconButtonStyle(selected: library.isWatched(meta.id)))
                }
                if trailerURL != nil {
                    Button { showTrailer = true } label: { Label("Trailer", systemImage: "film") }
                        .buttonStyle(CircleIconButtonStyle())
                }
                if trakt.connected {
                    // Star rating saved to Trakt, like the rating button on the other apps.
                    let mine = trakt.rating(meta: meta, videoId: nil)
                    Button { rateTarget = RateTarget(videoId: nil, title: meta.name) } label: {
                        Label(mine.map { "Your rating \($0)/10" } ?? "Rate", systemImage: mine == nil ? "star" : "star.fill")
                    }
                    .buttonStyle(CircleIconButtonStyle(selected: mine != nil))
                }
                if meta.type != "movie", ordered.count > 1 {
                    // Episode shuffle, like the Android TV app: plays a random episode of the show.
                    Button {
                        if let ep = ordered.randomElement() {
                            Task { await play(videoId: ep.id, title: ep.displayTitle) }
                        }
                    } label: { Label("Random episode", systemImage: "shuffle") }
                        .buttonStyle(CircleIconButtonStyle())
                        .disabled(resolving)
                }
                if settings.streamMode == .smart, !settings.addons.isEmpty || servers.hasEnabled || plugins.hasActiveScrapers {
                    Button { Task { await chooseSource() } } label: { Label("Sources", systemImage: "list.bullet") }
                        .buttonStyle(CircleIconButtonStyle())
                        .disabled(resolving)
                }
            }
            .padding(.bottom, 32)
            if let message { Text(message).foregroundStyle(.orange).padding(.bottom, 16) }
            if let d = meta.description {
                Text(d)
                    .font(.system(size: 28))
                    .lineSpacing(4)
                    .lineLimit(4)
                    .foregroundStyle(.white.opacity(0.85))
                    .frame(maxWidth: 1100, alignment: .leading)
                    .padding(.bottom, 24)
            }
            metaInfoRow
            if extras.people.isEmpty { creditsLine }
        }
    }

    /// Cast and directors, like Android TV's cast row: each name opens that person's page.
    @ViewBuilder private var creditsLine: some View {
        let people = Array((meta.director ?? []).prefix(2).map { ($0, "Director") } + (meta.cast ?? []).prefix(8).map { ($0, "") })
        if !people.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 16) {
                    ForEach(Array(people.enumerated()), id: \.offset) { _, person in
                        NavigationLink(value: CastRoute(name: person.0, crew: !person.1.isEmpty)) {
                            Text(person.1.isEmpty ? person.0 : "\(person.0) · \(person.1)")
                        }
                        .buttonStyle(ChipButtonStyle(selected: false))
                    }
                }
                .padding(.vertical, 12)
            }
            .scrollClipDisabled()
            .focusSection()
        }
    }

    /// Year, genres, running time and IMDb rating under the description, like Android TV's MetaInfoRow.
    private var metaInfoRow: some View {
        HStack(spacing: 16) {
            let parts = [meta.releaseInfo, meta.genres.map { $0.prefix(3).joined(separator: " • ") }, meta.runtime]
                .compactMap { $0 }.filter { !$0.isEmpty }
            ForEach(Array(parts.enumerated()), id: \.offset) { i, part in
                if i > 0 { Divider().frame(height: 22) }
                Text(part)
            }
            if let r = meta.imdbRating, !r.isEmpty {
                if !parts.isEmpty { Divider().frame(height: 22) }
                HStack(spacing: 8) {
                    Text("IMDb").font(.system(size: 18, weight: .heavy)).foregroundStyle(.black)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color(red: 0.96, green: 0.77, blue: 0.09), in: RoundedRectangle(cornerRadius: 4))
                    Text(r)
                }
            }
        }
        .font(.system(size: 24, weight: .medium))
        .foregroundStyle(.secondary)
        .lineLimit(1)
    }

    @ViewBuilder private var episodesSection: some View {
        if !seasons.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 16) {
                    ForEach(seasons, id: \.self) { s in
                        if let poster = extras.seasonPosters[s], settings.seasonPosters {
                            // Season posters in the season tabs, like Android TV.
                            Button { season = s } label: {
                                VStack(spacing: 10) {
                                    Artwork(url: poster).frame(width: 150, height: 225)
                                        .clipShape(RoundedRectangle(cornerRadius: 14))
                                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(.white, lineWidth: s == season ? 4 : 0))
                                    Text("Season \(s)").font(.system(size: 22, weight: s == season ? .bold : .regular))
                                }
                            }
                            .buttonStyle(.focusRing)
                        } else {
                            Button { season = s } label: { Text("Season \(s)") }
                                .buttonStyle(ChipButtonStyle(selected: s == season))
                        }
                    }
                }
                .padding(.vertical, 12)
            }
            .scrollClipDisabled()
        }
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 32) {
                    ForEach(episodes) { ep in
                        Button { Task { await play(videoId: ep.id, title: ep.displayTitle) } } label: {
                            EpisodeCard(video: ep, fallback: meta.backgroundURL, watched: watched(ep),
                                        progress: library.progress(for: ep.id), isNext: ep.id == nextUp?.id,
                                        rating: extras.episodeRatings[DetailExtras.ratingKey(ep.season, ep.episode)])
                        }
                        .buttonStyle(.focusRing)
                        .disabled(resolving)
                        .contextMenu {
                            if watched(ep) {
                                Button("Mark as unwatched") { library.markUnwatched(meta: meta, videoId: ep.id) }
                            } else {
                                Button("Mark as watched") { library.markWatched(meta: meta, videoId: ep.id) }
                            }
                            Button("Mark season \(season) as watched") {
                                for e in episodes where !watched(e) { library.markWatched(meta: meta, videoId: e.id) }
                            }
                            if library.hasHistory(meta.id) {
                                Button("Mark entire show as unwatched", role: .destructive) {
                                    library.markAllUnwatched(meta: meta)
                                }
                            }
                            Button("Choose source") { Task { await play(videoId: ep.id, title: ep.displayTitle, forcePicker: true) } }
                            if trakt.connected {
                                Button("Rate episode") { rateTarget = RateTarget(videoId: ep.id, title: ep.displayTitle) }
                            }
                        }
                        .id(ep.id)
                    }
                }
                .padding(.vertical, 24)
            }
            .scrollClipDisabled()
            .onChange(of: season) { _, _ in
                if let first = episodes.first { proxy.scrollTo(first.id, anchor: .leading) }
            }
            .onAppear {
                if let n = nextUp, episodes.contains(where: { $0.id == n.id }) { proxy.scrollTo(n.id, anchor: .leading) }
            }
        }
    }

    private var movieLabel: String {
        library.resumePoint(for: meta.id) > 0 ? "Resume" : "Play"
    }

    private func watched(_ ep: Video) -> Bool { library.isWatched(ep.id) }

    /// The first episode not finished yet, or the last one watched when it is only partly seen.
    private var nextUp: Video? {
        let list = ordered
        guard !list.isEmpty else { return nil }
        if let resume = library.continueWatching.first(where: { $0.id == item.id })?.videoId,
           let i = list.firstIndex(where: { $0.id == resume }) {
            if library.records.contains(where: { $0.id == resume && $0.completed }), i + 1 < list.count { return list[i + 1] }
            return list[i]
        }
        return list.first
    }

    private func nextLabel(_ v: Video) -> String {
        let started = library.resumePoint(for: v.id) > 0
        return "\(started ? "Resume" : "Play") S\(v.season ?? 0) E\(v.episode ?? 0)"
    }

    /// Finds sources early: the movie, or the next episode up plus the first episode of every season.
    private func preloadForOpen() {
        if meta.type == "movie" {
            streams.preload(type: "movie", id: meta.id, settings: settings)
            return
        }
        if let n = nextUp { streams.preload(type: "series", id: n.id, settings: settings) }
        for s in seasons.prefix(8) {
            if let first = (meta.videos ?? []).filter({ $0.season == s }).min(by: { ($0.episode ?? 0) < ($1.episode ?? 0) }) {
                streams.preload(type: "series", id: first.id, settings: settings)
            }
        }
    }

    private func play(videoId: String, title: String, forcePicker: Bool = false) async {
        message = nil
        guard !settings.addons.isEmpty || servers.hasEnabled || plugins.hasActiveScrapers else {
            message = "Add a stream add-on or a media server in Settings first."
            return
        }
        resolving = true
        let list = await streams.candidates(type: meta.type, id: videoId, parentId: meta.id, settings: settings, name: meta.name)
        resolving = false
        guard !list.isEmpty else {
            message = "No playable sources found. Check your add-ons in Settings, then try again, or use the Sources button."
            return
        }
        if forcePicker || settings.streamMode == .manual {
            picker = PickerState(videoId: videoId, title: title, candidates: list)
        } else {
            start(videoId: videoId, title: title, candidates: list)
        }
    }

    /// Opens the ranked source list for the movie or the next episode, even in Smart mode.
    private func chooseSource() async {
        if meta.type == "movie" {
            await play(videoId: meta.id, title: meta.name, forcePicker: true)
        } else if let n = nextUp {
            await play(videoId: n.id, title: n.displayTitle, forcePicker: true)
        }
    }

    private func start(videoId: String, title: String, candidates: [StreamItem]) {
        library.record(meta: meta, videoId: meta.type == "movie" ? nil : videoId)
        var req = makeRequest(videoId: videoId, title: title, candidates: candidates)
        req.startAt = library.resumePoint(for: meta.type == "movie" ? meta.id : videoId)
        playRequest = req
    }

    private func makeRequest(videoId: String, title: String, candidates: [StreamItem]) -> PlayRequest {
        if meta.type == "series", let cur = ordered.first(where: { $0.id == videoId }) {
            return DetailView.chain(meta: meta, ordered: ordered, current: cur, candidates: candidates,
                                    streams: streams, settings: settings, library: library)
        }
        return PlayRequest(meta: meta, videoId: meta.type == "movie" ? nil : videoId, title: title, candidates: candidates)
    }

    /// Builds the request for [current] and, recursively, for the episode after it.
    @MainActor
    static func chain(meta: MetaItem, ordered: [Video], current: Video, candidates: [StreamItem],
                      streams: StreamStore, settings: SettingsStore, library: LibraryStore) -> PlayRequest {
        var slim = meta
        slim.videos = nil
        var req = PlayRequest(meta: slim, videoId: current.id, title: current.displayTitle, candidates: candidates)
        if let idx = ordered.firstIndex(where: { $0.id == current.id }), idx + 1 < ordered.count {
            let next = ordered[idx + 1]
            if settings.preloadNext { streams.preload(type: "series", id: next.id, settings: settings) }
            req.nextEpisode = {
                let list = await streams.candidates(type: "series", id: next.id, parentId: meta.id, settings: settings, name: meta.name)
                guard !list.isEmpty else { return nil }
                library.record(meta: meta, videoId: next.id)
                var nextReq = DetailView.chain(meta: meta, ordered: ordered, current: next, candidates: list,
                                               streams: streams, settings: settings, library: library)
                nextReq.startAt = library.resumePoint(for: next.id)
                return nextReq
            }
        }
        return req
    }
}

/// The ranked source list: Manual mode, or the Sources button in Smart mode. Laid out like Android TV's
/// sources screen: what is playing on the left, a column of source cards on the right.
struct SourcePicker: View {
    let state: DetailView.PickerState
    let onChoose: ([StreamItem]) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 60) {
            VStack(alignment: .leading, spacing: 16) {
                Text("Sources").font(.system(size: 28, weight: .semibold)).foregroundStyle(.secondary)
                Text(state.title).font(.system(size: 48, weight: .bold)).lineLimit(3)
                Text("\(state.candidates.count) found. The first one is Smart auto-play's pick.")
                    .font(.system(size: 24)).foregroundStyle(.secondary)
            }
            .frame(width: 560, alignment: .leading)
            .padding(.top, 40)
            ScrollView {
                LazyVStack(spacing: 18) {
                    ForEach(Array(state.candidates.prefix(40).enumerated()), id: \.offset) { i, s in
                        Button {
                            var reordered = state.candidates
                            let picked = reordered.remove(at: i)
                            reordered.insert(picked, at: 0)
                            onChoose(reordered)
                        } label: {
                            SourceCard(stream: s, best: i == 0)
                        }
                        .buttonStyle(.focusRing)
                    }
                }
                .padding(.vertical, 40)
                .padding(.horizontal, 20)
            }
            .scrollClipDisabled()
        }
        .padding(.horizontal, 96)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Color.black.opacity(0.92).ignoresSafeArea())
    }
}

private struct SourceCard: View {
    let stream: StreamItem
    let best: Bool
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(alignment: .top, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 12) {
                    if best {
                        Label("Best", systemImage: "sparkles").font(.system(size: 20, weight: .bold))
                            .padding(.horizontal, 10).padding(.vertical, 4)
                            .background(.yellow.opacity(0.9), in: Capsule()).foregroundStyle(.black)
                    }
                    Text(stream.label).font(.system(size: 30, weight: .semibold)).lineLimit(1)
                }
                Text(stream.detail).font(.system(size: 22)).foregroundStyle(.secondary).lineLimit(3)
            }
            Spacer(minLength: 20)
            if let addon = stream.addonName {
                Text(addon).font(.system(size: 22, weight: .medium)).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(28)
        .frame(width: 1060, alignment: .leading)
        .background(.white.opacity(focused ? 0.16 : 0.07), in: RoundedRectangle(cornerRadius: CardMetrics.radius))
        .overlay(RoundedRectangle(cornerRadius: CardMetrics.radius).strokeBorder(.white, lineWidth: focused ? 4 : 0))
    }
}


/// The last card of a catalog row: opens the whole catalog.
struct SeeAllCard: View {
    var landscape = false
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "square.grid.2x2").font(.system(size: 48, weight: .semibold))
            Text("See all").font(.system(size: 26, weight: .semibold))
        }
        .frame(width: landscape ? CardMetrics.wideWidth : CardMetrics.posterWidth,
               height: landscape ? CardMetrics.wideHeight : CardMetrics.posterHeight)
        .background(.white.opacity(focused ? 0.18 : 0.08), in: RoundedRectangle(cornerRadius: CardMetrics.radius))
        .modifier(FocusRing(focused: focused, radius: CardMetrics.radius))
    }
}

/// Android TV's See all screen: the whole catalog as a poster grid, loading more pages as you scroll down.
struct CatalogSeeAllView: View {
    let catalog: AddonClient.AddonCatalog
    @State private var items: [MetaItem] = []
    @State private var loading = false
    @State private var finished = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text(catalog.title).font(.system(size: 44, weight: .bold)).padding(.leading, CardMetrics.rowInset)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: CardMetrics.posterWidth, maximum: CardMetrics.posterWidth),
                                             spacing: CardMetrics.spacing, alignment: .top)],
                          alignment: .leading, spacing: 36) {
                    ForEach(items) { item in
                        NavigationLink(value: item) { PosterCard(item: item) }
                            .buttonStyle(.focusRing)
                            .onAppear { if item.id == items.last?.id { Task { await loadMore() } } }
                    }
                }
                .padding(.horizontal, CardMetrics.rowInset)
                if loading { ProgressView().frame(maxWidth: .infinity) }
                if finished && items.isEmpty {
                    Text("This catalog is empty.").foregroundStyle(.secondary).padding(.leading, CardMetrics.rowInset)
                }
            }
            .padding(.vertical, 40)
        }
        .task { if items.isEmpty { await loadMore() } }
    }

    private func loadMore() async {
        guard !loading, !finished else { return }
        loading = true
        let page = await AddonClient.page(base: catalog.base, type: catalog.type, catalogId: catalog.id, extra: [:], skip: items.count)
        let known = Set(items.map(\.id))
        let fresh = page.filter { !known.contains($0.id) }
        items.append(contentsOf: fresh)
        if fresh.isEmpty { finished = true }
        loading = false
    }
}

/// The featured title at the top of the Classic and Grid layouts, with a button to open it.
struct FeaturedBanner: View {
    let item: MetaItem

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            HeroHeader(item: item)
            NavigationLink(value: item) {
                Label("Details", systemImage: "info.circle")
            }
            .buttonStyle(PillButtonStyle())
        }
        .padding(.leading, CardMetrics.rowInset)
        .padding(.bottom, 24)
        .focusSection()
    }
}

/// Grid layout: one catalog as a titled grid of its first titles, ending with See all.
struct GridSection: View {
    let row: AddonRow
    var onFocus: ((MetaItem) -> Void)? = nil
    @EnvironmentObject var settings: SettingsStore

    var body: some View {
        let items = Array(settings.visible(row.items).prefix(settings.landscapePosters ? 11 : 13))
        let width = settings.landscapePosters ? CardMetrics.wideWidth : CardMetrics.posterWidth
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 16) {
                Text(row.catalog.title).font(.system(size: 32, weight: .semibold)).padding(.leading, CardMetrics.rowInset)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: width, maximum: width), spacing: CardMetrics.spacing, alignment: .top)],
                          alignment: .leading, spacing: 36) {
                    ForEach(items) { item in
                        NavigationLink(value: item) {
                            PosterCard(item: item, onFocus: onFocus, showLabel: settings.posterLabels, landscape: settings.landscapePosters)
                        }
                        .buttonStyle(.focusRing)
                    }
                    NavigationLink(value: row.catalog) { SeeAllCard(landscape: settings.landscapePosters) }
                        .buttonStyle(.focusRing)
                }
                .padding(.horizontal, CardMetrics.rowInset)
            }
            .padding(.vertical, 20)
            .focusSection()
        }
    }
}


struct CastRoute: Hashable {
    var name: String
    /// Directors and writers: their page lists what they made rather than acted in.
    var crew: Bool = false
}

/// Android TV's cast page: the person's photo and biography, and the films and shows they are known for.
/// TMDB when a key is set, else Trakt's public people pages (the built-in Trakt key, no sign-in needed).
struct CastView: View {
    let route: CastRoute
    @State private var photo: URL?
    @State private var biography = ""
    @State private var knownFor: [MetaItem] = []
    @State private var loading = true
    @State private var failed = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 40) {
                HStack(alignment: .top, spacing: 48) {
                    Artwork(url: photo, placeholder: route.name)
                        .frame(width: 300, height: 450)
                        .clipShape(RoundedRectangle(cornerRadius: CardMetrics.radius))
                    VStack(alignment: .leading, spacing: 20) {
                        Text(route.name).font(.system(size: 56, weight: .bold))
                        if !biography.isEmpty {
                            Text(biography).font(.system(size: 24)).foregroundStyle(.secondary).lineLimit(9)
                        } else if failed {
                            Text("Couldn't load what \(route.name) is in. Check your connection and try again.").foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: 1100, alignment: .leading)
                }
                .padding(.horizontal, CardMetrics.rowInset)
                .focusable(knownFor.isEmpty)
                if loading {
                    ProgressView().padding(.leading, CardMetrics.rowInset)
                }
                PosterGrid(title: "Known for", items: knownFor)
            }
            .padding(.vertical, 60)
        }
        .task {
            if TMDB.available { await loadFromTmdb() }
            if knownFor.isEmpty, let person = await TraktPublic.person(named: route.name, preferCrew: route.crew) {
                if photo == nil { photo = person.photo.flatMap(URL.init(string:)) }
                if biography.isEmpty { biography = person.biography }
                knownFor = person.knownFor
            }
            failed = knownFor.isEmpty && biography.isEmpty
            loading = false
        }
    }

    private func loadFromTmdb() async {
        guard let person = (await TMDB.get("/search/person", ["query": route.name]))?["results"] as? [[String: Any]],
              let first = person.first, let id = first["id"] as? Int else { return }
        photo = TMDB.imageURL(first["profile_path"] as? String, size: "w500").flatMap(URL.init(string:))
        async let details = TMDB.get("/person/\(id)")
        async let credits = TMDB.get("/person/\(id)/combined_credits")
        biography = (await details)?["biography"] as? String ?? ""
        let creditRows = await credits
        // Talk-show guest spots (TMDB genre 10767) flood filmographies; hide them, keep hosts.
        var rows = (creditRows?["cast"] as? [[String: Any]] ?? []).filter { row in
            let talk = (row["genre_ids"] as? [Int] ?? []).contains(10767)
            let host = (row["character"] as? String ?? "").localizedCaseInsensitiveContains("host")
            return !((row["media_type"] as? String) == "tv" && talk && !host)
        }
        if route.crew || rows.isEmpty {
            let crew = creditRows?["crew"] as? [[String: Any]] ?? []
            if !crew.isEmpty { rows = crew }
        }
        var seen = Set<Int>()
        let ranked = rows.sorted { ($0["popularity"] as? Double ?? 0) > ($1["popularity"] as? Double ?? 0) }
            .filter { row in
                guard let id = row["id"] as? Int, !seen.contains(id) else { return false }
                seen.insert(id)
                return true
            }
        knownFor = await CollectionsStore.withImdbIds(Array(ranked.prefix(40)), defaultMedia: "movie")
    }
}


struct AmbientKey: Hashable {
    var minutes: Int
    var focus: String?
    var on: Bool
}

/// Android TV's ambient mode: backdrops from the user's catalogs, slowly zooming, with the title in the corner.
struct AmbientView: View {
    let items: [MetaItem]
    let onExit: () -> Void
    @State private var index = 0
    @State private var zoom = false

    var body: some View {
        let item = items.isEmpty ? nil : items[index % items.count]
        ZStack(alignment: .bottomLeading) {
            Color.black.ignoresSafeArea()
            if let item {
                Artwork(url: item.backgroundURL)
                    .scaleEffect(zoom ? 1.12 : 1.0)
                    .animation(.linear(duration: 12), value: zoom)
                    .ignoresSafeArea()
                    .id(item.id)
                    .transition(.opacity)
                LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .center, endPoint: .bottom).ignoresSafeArea()
                VStack(alignment: .leading, spacing: 8) {
                    HeroTitle(item: item)
                    if let year = item.releaseInfo { Text(year).font(.system(size: 26)).foregroundStyle(.secondary) }
                }
                .padding(80)
            }
            // Any press on the remote closes the slideshow.
            Button(action: onExit) { Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity) }
                .buttonStyle(.plain)
                .opacity(0.02)
        }
        .onExitCommand(perform: onExit)
        .task {
            zoom = true
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(12))
                withAnimation(.easeInOut(duration: 1.2)) { index += 1 }
                zoom = false
                try? await Task.sleep(for: .milliseconds(50))
                zoom = true
            }
        }
    }
}
