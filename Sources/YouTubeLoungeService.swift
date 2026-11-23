/**
 * @file YouTubeLoungeService.swift
 * @summary Implements the YouTube Lounge HTTP protocol to start and queue videos on Chromecast receivers.
 * @description Acquires a lounge token from a TV screenId, binds a session (SID/gsessionid), and posts form-encoded playlist actions with automatic stale-SID retry.
 * @testing Verified via static review and Python wire/encoding verification scripts against casttube's form-encoding and session counter rules.
 */

import Foundation

/// Faithful Swift port of `casttube`'s YouTubeSession, the code the Android app
/// runs to play YouTube videos on Chromecasts. See README.md for the protocol.
///
/// Flow: lounge token -> bind (SID + gsessionid) -> setPlaylist.
/// RID increments on every post; the session request counter only on session
/// requests. On HTTP 404/400 for a session request, re-bind (as casttube does).
final class YouTubeLoungeService {
    private static let base = "https://www.youtube.com/"
    private static let bindURL = URL(string: base + "api/lounge/bc/bind")!
    private static let loungeTokenURL = URL(string: base + "api/lounge/pairing/get_lounge_token_batch")!

    private static let bindData: [(String, String)] = [
        ("device", "REMOTE_CONTROL"),
        ("id", "aaaaaaaaaaaaaaaaaaaaaaaaaa"),
        ("name", "Python"),
        ("mdx-version", "3"),
        ("pairing_type", "cast"),
        ("app", "android-phone-13.14.55"),
    ]

    private let screenId: String
    private var loungeToken: String?
    private var gsessionId: String?
    private var sid: String?
    private var rid = 0
    private var reqCount = 0

    init(screenId: String) {
        self.screenId = screenId
    }

    var inSession: Bool { gsessionId != nil && loungeToken != nil }

    /// Play a video now, replacing the queue (casttube `play_video`).
    func playVideo(_ videoId: String, listId: String = "", startTime: String = "0") async throws {
        try await startSession()
        let params: [(String, String)] = [
            ("_listId", listId),
            ("__sc", "setPlaylist"),
            ("_currentTime", startTime),
            ("_currentIndex", "-1"),
            ("_audioOnly", "false"),
            ("_videoId", videoId),
            ("count", "1"),
        ]
        do {
            try await postSession(form: formatSessionParams(params))
        } catch LoungeError.needsRetry {
            // SID went stale mid-request; postSession already re-bound, retry once.
            try await postSession(form: formatSessionParams(params))
        }
    }

    func addToQueue(_ videoId: String) async throws {
        try await queueAction(videoId, action: "addVideo")
    }

    func playNext(_ videoId: String) async throws {
        try await queueAction(videoId, action: "insertVideo")
    }

    func removeVideo(_ videoId: String) async throws {
        try await queueAction(videoId, action: "removeVideo")
    }

    func clearPlaylist() async throws {
        try await queueAction("", action: "clearPlaylist")
    }

    // MARK: - Session plumbing (casttube internals)

    private func startSession() async throws {
        try await getLoungeToken()
        try await bind()
    }

    private func queueAction(_ videoId: String, action: String) async throws {
        if !inSession {
            try await startSession()
        } else {
            // casttube re-binds before each queue action: sessions drift out
            // of sync after ~30 seconds otherwise.
            try await bind()
        }
        let params: [(String, String)] = [
            ("__sc", action),
            ("_videoId", videoId),
            ("count", "1"),
        ]
        do {
            try await postSession(form: formatSessionParams(params))
        } catch LoungeError.needsRetry {
            try await postSession(form: formatSessionParams(params))
        }
    }

    private func getLoungeToken() async throws {
        let (data, _) = try await postForm(
            url: Self.loungeTokenURL,
            form: [("screen_ids", screenId)],
            params: [],
            loungeHeader: false
        )
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let screens = json["screens"] as? [[String: Any]],
              let token = screens.first?["loungeToken"] as? String else {
            throw LoungeError.badLoungeTokenResponse
        }
        loungeToken = token
    }

    private func bind() async throws {
        rid = 0
        reqCount = 0
        let (data, _) = try await postForm(
            url: Self.bindURL,
            form: Self.bindData,
            params: [("RID", "0"), ("VER", "8"), ("CVER", "1")],
            loungeHeader: true
        )
        let content = String(data: data, encoding: .utf8) ?? ""
        guard let sid = firstMatch("\"c\",\"(.*?)\",", in: content),
              let gsession = firstMatch("\"S\",\"(.*?)\"\\]", in: content) else {
            throw LoungeError.badBindResponse
        }
        self.sid = sid
        self.gsessionId = gsession
    }

    private func postSession(form: [(String, String)]) async throws {
        guard let sid = sid, let gsessionId = gsessionId else {
            throw LoungeError.notBound
        }
        let params: [(String, String)] = [
            ("SID", sid),
            ("gsessionid", gsessionId),
            ("RID", String(rid)),
            ("VER", "8"),
            ("CVER", "1"),
        ]
        do {
            _ = try await postForm(url: Self.bindURL, form: form, params: params, loungeHeader: true)
            reqCount += 1
        } catch LoungeError.httpStatus(let code) where (code == 404 || code == 400) {
            // casttube: a 404/400 on a session request means a stale SID.
            try await bind()
            throw LoungeError.needsRetry
        }
    }

    /// Prefixes keys starting with "_" with "req<N>_", exactly like casttube's
    /// `_format_session_params`. "count" and "__sc" pass through unchanged
    /// ("__sc" does not start with a single "_", it starts with "__"... see
    /// note: casttube checks `k.startswith("_")`, so "__sc" DOES get prefixed
    /// to "req<N>__sc". That is the behavior ported here.)
    private func formatSessionParams(_ params: [(String, String)]) -> [(String, String)] {
        let prefix = "req\(reqCount)"
        return params.map { (k, v) in
            (k.hasPrefix("_") ? prefix + k : k, v)
        }
    }

    /// One form POST. Returns body + status code. Throws on non-2xx.
    /// Mirrors casttube's `_do_post`: base headers + optional lounge header,
    /// RID increments on every post.
    @discardableResult
    private func postForm(
        url: URL,
        form: [(String, String)],
        params: [(String, String)],
        loungeHeader: Bool
    ) async throws -> (Data, Int) {
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        if !params.isEmpty {
            components.queryItems = params.map { URLQueryItem(name: $0.0, value: $0.1) }
        }
        var request = URLRequest(url: components.url!)
        request.httpMethod = "POST"
        request.setValue("https://www.youtube.com/", forHTTPHeaderField: "Origin")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        if loungeHeader, let token = loungeToken {
            request.setValue(token, forHTTPHeaderField: "X-YouTube-LoungeId-Token")
        }
        request.httpBody = form
            .map { "\(Self.formEncode($0.0))=\(Self.formEncode($0.1))" }
            .joined(separator: "&")
            .data(using: .utf8)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw LoungeError.noHTTPResponse
        }
        guard (200...299).contains(http.statusCode) else {
            throw LoungeError.httpStatus(http.statusCode)
        }
        rid += 1
        return (data, http.statusCode)
    }

    private static let formAllowed: CharacterSet = {
        var cs = CharacterSet.alphanumerics
        cs.insert(charactersIn: "-._~")
        return cs
    }()

    private static func formEncode(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: formAllowed) ?? value
    }

    private func firstMatch(_ pattern: String, in text: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        guard let m = re.firstMatch(in: text, range: range),
              m.numberOfRanges > 1,
              let r = Range(m.range(at: 1), in: text) else { return nil }
        return String(text[r])
    }

    enum LoungeError: Error {
        case badLoungeTokenResponse
        case badBindResponse
        case notBound
        case needsRetry
        case noHTTPResponse
        case httpStatus(Int)
    }
}
