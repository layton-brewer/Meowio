import Foundation
import libxml2

/// A parsed HTML page for the cheerio calls Nuvio plugins make. Uses the system's libxml2 HTML parser and a small
/// CSS selector matcher covering what scrapers use: tags, #id, .class, [attr] tests, the four combinators,
/// :first-child, :last-child, :nth-child(n), :eq(n), :contains(text), :not(...) and :has(...).
final class HTMLDocument {
    typealias Node = UnsafeMutablePointer<xmlNode>
    private let doc: htmlDocPtr

    init?(_ html: String) {
        var bytes = Array(html.utf8)
        if bytes.isEmpty { bytes = Array("<html></html>".utf8) }
        let options = Int32(HTML_PARSE_RECOVER.rawValue | HTML_PARSE_NOERROR.rawValue | HTML_PARSE_NOWARNING.rawValue
                            | HTML_PARSE_NONET.rawValue | HTML_PARSE_COMPACT.rawValue)
        let parsed = bytes.withUnsafeBufferPointer { buf in
            buf.baseAddress!.withMemoryRebound(to: CChar.self, capacity: buf.count) {
                htmlReadMemory($0, Int32(buf.count), nil, "UTF-8", options)
            }
        }
        guard let parsed else { return nil }
        doc = parsed
    }

    deinit { xmlFreeDoc(doc) }

    var root: Node? { xmlDocGetRootElement(doc) }

    /// Elements matching [selector] under [scope] (the whole page when nil), in document order.
    func select(_ selector: String, in scope: Node? = nil) -> [Node] {
        guard let groups = CSSSelector.parse(selector) else { return [] }
        var out: [Node] = []
        func walk(_ node: Node?) {
            var child = node
            while let c = child {
                if c.pointee.type == XML_ELEMENT_NODE {
                    if groups.contains(where: { $0.matches(c) }) { out.append(c) }
                    walk(c.pointee.children)
                }
                child = c.pointee.next
            }
        }
        if let scope { walk(scope.pointee.children) } else if let root { walk(root) }
        return out
    }

    /// The whole page as HTML.
    func html() -> String {
        guard let root else { return "" }
        return Self.dump(root)
    }

    static func name(_ n: Node) -> String {
        n.pointee.name.map { String(cString: $0).lowercased() } ?? ""
    }

    static func attr(_ n: Node, _ key: String) -> String? {
        guard let v = xmlGetProp(n, key) else { return nil }
        defer { xmlFree(v) }
        return String(cString: v)
    }

    /// Text of the element and everything in it, with runs of white space collapsed (like jsoup's text()).
    static func text(_ n: Node) -> String {
        guard let v = xmlNodeGetContent(n) else { return "" }
        defer { xmlFree(v) }
        return String(cString: v).split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    /// Text directly inside the element, not in its children.
    static func ownText(_ n: Node) -> String {
        var parts: [String] = []
        var child = n.pointee.children
        while let c = child {
            if c.pointee.type == XML_TEXT_NODE, let v = c.pointee.content { parts.append(String(cString: v)) }
            child = c.pointee.next
        }
        return parts.joined().split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    /// HTML inside the element.
    static func innerHTML(_ n: Node) -> String {
        var out = ""
        var child = n.pointee.children
        while let c = child {
            out += dump(c)
            child = c.pointee.next
        }
        return out
    }

    static func dump(_ n: Node) -> String {
        guard let buf = xmlBufferCreate() else { return "" }
        defer { xmlBufferFree(buf) }
        htmlNodeDump(buf, n.pointee.doc, n)
        guard let content = xmlBufferContent(buf) else { return "" }
        return String(cString: content)
    }

    static func parentElement(_ n: Node) -> Node? {
        guard let p = n.pointee.parent, p.pointee.type == XML_ELEMENT_NODE else { return nil }
        return p
    }

    static func nextElement(_ n: Node) -> Node? {
        var s = n.pointee.next
        while let c = s { if c.pointee.type == XML_ELEMENT_NODE { return c }; s = c.pointee.next }
        return nil
    }

    static func previousElement(_ n: Node) -> Node? {
        var s = n.pointee.prev
        while let c = s { if c.pointee.type == XML_ELEMENT_NODE { return c }; s = c.pointee.prev }
        return nil
    }

    static func elementIndex(_ n: Node) -> Int {
        var i = 0
        var s = previousElement(n)
        while let c = s { i += 1; s = previousElement(c) }
        return i
    }
}

/// One comma-separated part of a CSS selector, matched right to left.
struct CSSSelector {
    struct AttrTest { var name: String; var op: String; var value: String }
    indirect enum Pseudo {
        case firstChild, lastChild, onlyChild, nthChild(Int), nthLastChild(Int), even, odd
        case eq(Int), lt(Int), gt(Int)
        case contains(String), containsOwn(String), matches(String)
        case not([CSSSelector]), has([CSSSelector])
    }
    struct Compound {
        var tag: String?
        var id: String?
        var classes: [String] = []
        var attrs: [AttrTest] = []
        var pseudos: [Pseudo] = []
    }

    var compounds: [Compound]
    /// combinators[i] joins compounds[i] and compounds[i + 1]: " ", ">", "+" or "~".
    var combinators: [Character]

    func matches(_ node: HTMLDocument.Node) -> Bool { match(node, compounds.count - 1) }

    private func match(_ node: HTMLDocument.Node, _ i: Int) -> Bool {
        guard Self.test(compounds[i], node) else { return false }
        if i == 0 { return true }
        switch combinators[i - 1] {
        case ">":
            return HTMLDocument.parentElement(node).map { match($0, i - 1) } ?? false
        case "+":
            return HTMLDocument.previousElement(node).map { match($0, i - 1) } ?? false
        case "~":
            var s = HTMLDocument.previousElement(node)
            while let c = s { if match(c, i - 1) { return true }; s = HTMLDocument.previousElement(c) }
            return false
        default:
            var p = HTMLDocument.parentElement(node)
            while let c = p { if match(c, i - 1) { return true }; p = HTMLDocument.parentElement(c) }
            return false
        }
    }

    private static func test(_ c: Compound, _ n: HTMLDocument.Node) -> Bool {
        if let tag = c.tag, tag != "*", HTMLDocument.name(n) != tag { return false }
        if let id = c.id, HTMLDocument.attr(n, "id") != id { return false }
        if !c.classes.isEmpty {
            let have = Set((HTMLDocument.attr(n, "class") ?? "").split(whereSeparator: { $0.isWhitespace }).map(String.init))
            if !c.classes.allSatisfy(have.contains) { return false }
        }
        for a in c.attrs {
            guard let v = HTMLDocument.attr(n, a.name) else { return false }
            let lv = v.lowercased(), want = a.value.lowercased()
            switch a.op {
            case "=": if v != a.value { return false }
            case "^=": if !lv.hasPrefix(want) { return false }
            case "$=": if !lv.hasSuffix(want) { return false }
            case "*=": if !lv.contains(want) { return false }
            case "~=": if !v.split(separator: " ").contains(Substring(a.value)) { return false }
            case "|=": if !(v == a.value || v.hasPrefix(a.value + "-")) { return false }
            default: break
            }
        }
        for p in c.pseudos {
            switch p {
            case .firstChild: if HTMLDocument.previousElement(n) != nil { return false }
            case .lastChild: if HTMLDocument.nextElement(n) != nil { return false }
            case .onlyChild: if HTMLDocument.previousElement(n) != nil || HTMLDocument.nextElement(n) != nil { return false }
            case .nthChild(let k): if HTMLDocument.elementIndex(n) + 1 != k { return false }
            case .nthLastChild(let k):
                var count = 0
                var s = HTMLDocument.nextElement(n)
                while let c = s { count += 1; s = HTMLDocument.nextElement(c) }
                if count + 1 != k { return false }
            case .even: if (HTMLDocument.elementIndex(n) + 1) % 2 != 0 { return false }
            case .odd: if (HTMLDocument.elementIndex(n) + 1) % 2 != 1 { return false }
            // jsoup's :eq/:lt/:gt count the element's place among its siblings.
            case .eq(let k): if HTMLDocument.elementIndex(n) != k { return false }
            case .lt(let k): if HTMLDocument.elementIndex(n) >= k { return false }
            case .gt(let k): if HTMLDocument.elementIndex(n) <= k { return false }
            case .contains(let t): if !HTMLDocument.text(n).lowercased().contains(t.lowercased()) { return false }
            case .containsOwn(let t): if !HTMLDocument.ownText(n).lowercased().contains(t.lowercased()) { return false }
            case .matches(let re):
                if HTMLDocument.text(n).range(of: re, options: .regularExpression) == nil { return false }
            case .not(let list): if list.contains(where: { $0.matches(n) }) { return false }
            case .has(let list):
                var found = false
                func walk(_ node: HTMLDocument.Node?) {
                    var child = node
                    while let c = child, !found {
                        if c.pointee.type == XML_ELEMENT_NODE {
                            if list.contains(where: { $0.matches(c) }) { found = true }
                            walk(c.pointee.children)
                        }
                        child = c.pointee.next
                    }
                }
                walk(n.pointee.children)
                if !found { return false }
            }
        }
        return true
    }

    // MARK: Parsing

    /// Nil when the selector cannot be read; plugins then get no matches, as with jsoup's parse errors.
    static func parse(_ text: String) -> [CSSSelector]? {
        var p = Parser(Array(text))
        return p.selectorList()
    }

    private struct Parser {
        let chars: [Character]
        var i = 0
        init(_ chars: [Character]) { self.chars = chars }

        var done: Bool { i >= chars.count }
        var peek: Character? { done ? nil : chars[i] }

        mutating func skipSpace() -> Bool {
            var skipped = false
            while let c = peek, c.isWhitespace { i += 1; skipped = true }
            return skipped
        }

        mutating func selectorList(until end: Character? = nil) -> [CSSSelector]? {
            var list: [CSSSelector] = []
            while true {
                _ = skipSpace()
                guard let s = complex(until: end) else { return nil }
                list.append(s)
                _ = skipSpace()
                if peek == "," { i += 1; continue }
                if let end, peek == end { return list }
                return done ? list : nil
            }
        }

        mutating func complex(until end: Character?) -> CSSSelector? {
            var compounds: [Compound] = []
            var combinators: [Character] = []
            _ = skipSpace()
            if let c = peek, ">+~".contains(c) {
                // A leading combinator (cheerio find("> li")) is relative to the search scope.
                i += 1
                _ = skipSpace()
            }
            guard let first = compound() else { return nil }
            compounds.append(first)
            while true {
                let spaced = skipSpace()
                guard let c = peek, c != ",", c != end else { break }
                var comb: Character = " "
                if ">+~".contains(c) { comb = c; i += 1; _ = skipSpace() } else if !spaced { return nil }
                guard let next = compound() else { return nil }
                combinators.append(comb)
                compounds.append(next)
            }
            return CSSSelector(compounds: compounds, combinators: combinators)
        }

        mutating func ident() -> String {
            var s = ""
            while let c = peek, c.isLetter || c.isNumber || c == "-" || c == "_" || c == "\\" {
                if c == "\\" { i += 1; if let n = peek { s.append(n) } } else { s.append(c) }
                i += 1
            }
            return s
        }

        mutating func compound() -> Compound? {
            var c = Compound()
            var any = false
            if peek == "*" { i += 1; c.tag = "*"; any = true }
            else { let t = ident(); if !t.isEmpty { c.tag = t.lowercased(); any = true } }
            loop: while let ch = peek {
                switch ch {
                case "#": i += 1; c.id = ident(); any = true
                case ".": i += 1; c.classes.append(ident()); any = true
                case "[":
                    i += 1
                    _ = skipSpace()
                    let name = ident().lowercased()
                    _ = skipSpace()
                    var op = "", value = ""
                    if let o = peek, "=^$*~|".contains(o) {
                        op = String(o); i += 1
                        if o != "=" { guard peek == "=" else { return nil }; op += "="; i += 1 }
                        _ = skipSpace()
                        value = quotedOrBare(stop: "]")
                        _ = skipSpace()
                        // Case flag ([attr=x i]) is accepted and ignored.
                        if peek == "i" || peek == "s" { i += 1; _ = skipSpace() }
                    }
                    guard peek == "]" else { return nil }
                    i += 1
                    c.attrs.append(AttrTest(name: name, op: op, value: value))
                    any = true
                case ":":
                    i += 1
                    if peek == ":" { i += 1 }
                    let name = ident().lowercased()
                    var arg: String?
                    var inner: [CSSSelector]?
                    if peek == "(" {
                        i += 1
                        if name == "not" || name == "has" || name == "is" {
                            inner = selectorList(until: ")")
                            guard inner != nil else { return nil }
                        } else {
                            _ = skipSpace()
                            arg = quotedOrBare(stop: ")")
                            _ = skipSpace()
                        }
                        guard peek == ")" else { return nil }
                        i += 1
                    }
                    let n = Int(arg?.trimmingCharacters(in: .whitespaces) ?? "") ?? 0
                    switch name {
                    case "first-child", "first", "first-of-type": c.pseudos.append(.firstChild)
                    case "last-child", "last", "last-of-type": c.pseudos.append(.lastChild)
                    case "only-child": c.pseudos.append(.onlyChild)
                    case "nth-child", "nth-of-type":
                        let a = arg?.lowercased().trimmingCharacters(in: .whitespaces) ?? ""
                        if a == "even" || a == "2n" { c.pseudos.append(.even) }
                        else if a == "odd" || a == "2n+1" { c.pseudos.append(.odd) }
                        else { c.pseudos.append(.nthChild(n)) }
                    case "nth-last-child": c.pseudos.append(.nthLastChild(n))
                    case "eq": c.pseudos.append(.eq(n))
                    case "lt": c.pseudos.append(.lt(n))
                    case "gt": c.pseudos.append(.gt(n))
                    case "contains", "containsown" where arg != nil:
                        c.pseudos.append(name == "contains" ? .contains(arg!) : .containsOwn(arg!))
                    case "matches" where arg != nil: c.pseudos.append(.matches(arg!))
                    case "not": c.pseudos.append(.not(inner ?? []))
                    case "has": c.pseudos.append(.has(inner ?? []))
                    // :is(x) as not(not(x)).
                    case "is": c.pseudos.append(.not([CSSSelector(compounds: [Compound(pseudos: [.not(inner ?? [])])], combinators: [])]))
                    default: break
                    }
                    any = true
                default:
                    break loop
                }
            }
            return any ? c : nil
        }

        mutating func quotedOrBare(stop: Character) -> String {
            if let q = peek, q == "\"" || q == "'" {
                i += 1
                var s = ""
                while let c = peek, c != q { s.append(c); i += 1 }
                if peek == q { i += 1 }
                return s
            }
            var s = ""
            var depth = 0
            while let c = peek {
                if c == "(" { depth += 1 }
                if c == stop && depth == 0 { break }
                if c == ")" { depth -= 1 }
                s.append(c); i += 1
            }
            return s.trimmingCharacters(in: .whitespaces)
        }
    }
}
