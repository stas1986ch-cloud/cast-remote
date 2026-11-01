import Foundation

/// Exact port of the Android `parse_link` function in castctl.py.
/// Returns the 11-char video id (or nil) and the start time in seconds.
struct YouTubeLink {
    let videoId: String?
    let start: Int

    static func parse(_ link: String) -> YouTubeLink {
        let text = link.trimmingCharacters(in: .whitespacesAndNewlines)
        var videoId: String? = nil
        var start = 0

        func firstMatch(_ pattern: String, in s: String, group: Int = 1) -> String? {
            guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
            let range = NSRange(s.startIndex..., in: s)
            guard let m = re.firstMatch(in: s, range: range),
                  m.numberOfRanges > group,
                  let r = Range(m.range(at: group), in: s) else { return nil }
            return String(s[r])
        }

        if let v = firstMatch("[?&]v=([A-Za-z0-9_-]{11})", in: text) {
            videoId = v
        } else if let v = firstMatch("youtu\\.be/([A-Za-z0-9_-]{11})", in: text) {
            videoId = v
        } else if let v = firstMatch("/(shorts|embed|live)/([A-Za-z0-9_-]{11})", in: text, group: 2) {
            videoId = v
        } else if text.range(of: "^[A-Za-z0-9_-]{11}$", options: .regularExpression) != nil {
            videoId = text
        }

        if let t = firstMatch("[?&]t=([^&]+)", in: text) {
            start = parseStart(t)
        }
        return YouTubeLink(videoId: videoId, start: start)
    }

    /// Port of `_parse_start`: "90s" -> 90, "90" -> 90, "1h2m3s" -> 3723.
    static func parseStart(_ text: String) -> Int {
        let t = text.trimmingCharacters(in: .whitespaces)
        if t.hasSuffix("s"), let n = Int(t.dropLast()) { return n }
        if let n = Int(t) { return n }
        var total = 0
        func part(_ pattern: String, multiplier: Int) {
            guard let re = try? NSRegularExpression(pattern: pattern),
                  let m = re.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)),
                  let r = Range(m.range(at: 1), in: t),
                  let n = Int(t[r]) else { return }
            total += n * multiplier
        }
        part("(\\d+)h", multiplier: 3600)
        part("(\\d+)m", multiplier: 60)
        part("(\\d+)s", multiplier: 1)
        return total
    }
}
