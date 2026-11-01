import Foundation
import GoogleCast

/// Device row model: one entry per Chromecast or per Cast Remote phone.
struct CastDevice: Identifiable {
    let id: String
    var name: String
    var state: String
    var title: String
    var contentId: String?
    var position: Double
    let isPhone: Bool
    let host: String
    let port: Int
    let gckDevice: GCKDevice?

    static func phone(name: String, host: String, port: Int) -> CastDevice {
        CastDevice(id: "phone:\(name)", name: name, state: "PHONE", title: "Cast Remote",
                   contentId: nil, position: 0, isPhone: true, host: host, port: port, gckDevice: nil)
    }
}

/// Owns Chromecast discovery/sessions (Google Cast SDK) and phone targets
/// (PhoneReceiver). Mirrors the Android app's castctl.py + phone_cast.py.
///
/// iOS architecture note: the Cast SDK holds one active session. Mirror is
/// therefore sequential, not parallel like Android: connect to each TV in
/// turn, read its status or play, then move on. Disconnecting a session does
/// NOT stop playback on that TV; only an explicit stop does.
@MainActor
final class CastManager: NSObject, ObservableObject {
    static let youTubeAppId = "233637DE"
    static let mdxNamespace = "urn:x-cast:com.google.youtube.mdx"

    @Published var devices: [CastDevice] = []
    @Published var status: String = "Tap Refresh to find TVs."

    private var castDevices: [String: CastDevice] = [:]
    private var phoneTargets: [String: (host: String, port: Int)] = [:]
    private var sessionContinuation: CheckedContinuation<GCKCastSession, Error>?
    private var mdxContinuation: CheckedContinuation<String, Error>?
    private var mdxChannel: MDXChannel?

    override init() {
        super.init()
        let context = GCKCastContext.sharedInstance()
        context.discoveryManager.addListener(self)
        PhoneReceiver.shared.onPhonesFound = { [weak self] phones in
            Task { @MainActor in
                for p in phones { self?.phoneTargets[p.name] = (p.host, p.port) }
                self?.rebuildDevices()
            }
        }
    }

    // MARK: - Discovery

    func refresh() {
        status = "Working: discovering..."
        let context = GCKCastContext.sharedInstance()
        if !context.discoveryManager.discoveryActive {
            context.discoveryManager.startDiscovery()
        }
        PhoneReceiver.shared.discoverPhones()
        // Phone results arrive via onPhonesFound; rebuild after the scan window.
        Task {
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            rebuildDevices()
            updateStatus()
        }
    }

    private func rebuildDevices() {
        var all = Array(castDevices.values)
        for (name, hp) in phoneTargets {
            all.append(.phone(name: name, host: hp.host, port: hp.port))
        }
        all.sort { $0.name < $1.name }
        devices = all
    }

    private func updateStatus() {
        let tvCount = devices.filter { !$0.isPhone }.count
        let phoneCount = phoneTargets.count
        var text = "Found "
        if tvCount > 0 { text += "\(tvCount) TVs" }
        if tvCount > 0 && phoneCount > 0 { text += " and " }
        if phoneCount > 0 { text += "\(phoneCount) phones" }
        if tvCount == 0 && phoneCount == 0 { text += "0 devices" }
        text += "."
        if let playing = devices.first(where: { $0.state == "PLAYING" }) {
            text += " \(playing.name) is playing - tap Mirror what's playing."
        }
        status = text
    }

    // MARK: - Play

    func playOn(_ device: CastDevice, videoId: String, start: Int) async {
        if device.isPhone {
            status = "Working: sending to \(device.name)..."
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                PhoneReceiver.shared.sendToPhone(host: device.host, port: device.port, videoId: videoId, start: start) { result in
                    cont.resume()
                    Task { @MainActor in
                        switch result {
                        case .success: self.status = "Play on \(device.name): ok"
                        case .failure(let e): self.status = "Play on \(device.name) failed: \(e.localizedDescription)"
                        }
                    }
                }
            }
            return
        }
        guard let gck = device.gckDevice else { status = "Play failed: no device"; return }
        status = "Working: playing on \(device.name)..."
        do {
            let session = try await session(for: gck)
            let screenId = try await mdxScreenId(session: session)
            let lounge = YouTubeLoungeService(screenId: screenId)
            try await lounge.playVideo(videoId, startTime: String(start))
            status = "Play on \(device.name): ok"
        } catch {
            status = "Play on \(device.name) failed: \(error.localizedDescription)"
        }
    }

    // MARK: - Mirror

    /// Mirrors whatever is playing to one target, or to ALL when target is nil.
    /// Source = first TV whose media status is PLAYING/BUFFERING/PAUSED with an
    /// 11-char YouTube content id, exactly like Android's source_status().
    func mirror(target: CastDevice?) async {
        status = "Working: mirroring..."
        do {
            guard let source = try await findSource() else {
                status = "Nothing playing to mirror. Start a video on one TV first, or paste a link below."
                return
            }
            guard let videoId = source.contentId else {
                status = "Nothing playing to mirror. Start a video on one TV first, or paste a link below."
                return
            }
            let position = Int(source.position)
            var tvCount = 0
            var phoneCount = 0
            let targets = target.map { [$0] } ?? devices.filter { $0.name != source.name && !$0.name.lowercased().hasSuffix("group") }
            for device in targets {
                if device.isPhone {
                    let ok = await sendToPhoneSync(device, videoId: videoId, start: position)
                    if ok { phoneCount += 1 }
                } else if let gck = device.gckDevice {
                    do {
                        let session = try await session(for: gck)
                        let screenId = try await mdxScreenId(session: session)
                        let lounge = YouTubeLoungeService(screenId: screenId)
                        try await lounge.playVideo(videoId, startTime: String(position))
                        tvCount += 1
                    } catch {
                        // Skip a failed target, keep mirroring the rest.
                        continue
                    }
                }
            }
            var parts: [String] = []
            if tvCount > 0 { parts.append("\(tvCount) TVs") }
            if phoneCount > 0 { parts.append("\(phoneCount) phones") }
            let dest = parts.isEmpty ? "nothing" : parts.joined(separator: " and ")
            status = "Mirroring from \(source.name) to \(dest)."
            refresh()
        } catch {
            status = "Mirror failed: \(error.localizedDescription)"
        }
    }

    private func findSource() async throws -> CastDevice? {
        for device in devices where !device.isPhone {
            guard let gck = device.gckDevice else { continue }
            do {
                let session = try await session(for: gck)
                if let media = session.remoteMediaClient?.mediaStatus,
                   let contentId = media.mediaInformation?.contentID,
                   contentId.count == 11,
                   [.playing, .paused, .buffering].contains(media.playerState) {
                    var d = device
                    d.state = media.playerState == .playing ? "PLAYING" : media.playerState == .paused ? "PAUSED" : "BUFFERING"
                    d.title = media.mediaInformation?.metadata?.string(forKey: kGCKMetadataKeyTitle) ?? ""
                    d.contentId = contentId
                    d.position = media.streamPosition
                    return d
                }
            } catch {
                continue
            }
        }
        return nil
    }

    private func sendToPhoneSync(_ device: CastDevice, videoId: String, start: Int) async -> Bool {
        await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            PhoneReceiver.shared.sendToPhone(host: device.host, port: device.port, videoId: videoId, start: start) { result in
                switch result {
                case .success: cont.resume(returning: true)
                case .failure: cont.resume(returning: false)
                }
            }
        }
    }

    // MARK: - Stop

    func stop(_ device: CastDevice) async {
        guard let gck = device.gckDevice else { return }
        status = "Working: stopping \(device.name)..."
        do {
            let session = try await session(for: gck)
            session.remoteMediaClient?.stop()
            GCKCastContext.sharedInstance().sessionManager.endSession()
            status = "Stop \(device.name): ok"
            refresh()
        } catch {
            status = "Stop \(device.name) failed: \(error.localizedDescription)"
        }
    }

    func stopAll() async {
        status = "Working: stopping all..."
        for device in devices where !device.isPhone {
            if let gck = device.gckDevice {
                do {
                    let session = try await session(for: gck)
                    session.remoteMediaClient?.stop()
                } catch { continue }
            }
        }
        GCKCastContext.sharedInstance().sessionManager.endSession()
        status = "Stop all: ok"
        refresh()
    }

    // MARK: - Sessions + MDX

    /// Starts (or reuses) a Cast session for a device. The SDK joins the
    /// YouTube app if it is already running on that TV.
    private func session(for device: GCKDevice) async throws -> GCKCastSession {
        let manager = GCKCastContext.sharedInstance().sessionManager
        if let current = manager.currentCastSession, current.device.deviceID == device.deviceID {
            return current
        }
        manager.addListener(self)
        _ = manager.startSession(with: device)
        return try await withThrowingTaskGroup(of: GCKCastSession.self) { group in
            group.addTask {
                try await withCheckedThrowingContinuation { (cont: CheckedContinuation<GCKCastSession, Error>) in
                    self.sessionContinuation = cont
                }
            }
            group.addTask {
                try await Task.sleep(nanoseconds: 15_000_000_000)
                throw CastError.sessionTimeout
            }
            return try await group.next()!
        }
    }

    /// Sends getMdxSessionStatus on the MDX namespace and waits for the
    /// screenId, mirroring pychromecast's update_screen_id().
    private func mdxScreenId(session: GCKCastSession) async throws -> String {
        let channel = MDXChannel()
        channel.onScreenId = { [weak self] screenId in
            self?.mdxContinuation?.resume(returning: screenId)
            self?.mdxContinuation = nil
        }
        session.add(channel)
        mdxChannel = channel
        var error: NSError?
        guard channel.sendTextMessage("{\"type\":\"getMdxSessionStatus\"}", error: &error) else {
            throw CastError.mdxSendFailed(error?.localizedDescription ?? "unknown")
        }
        return try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask {
                try await withCheckedThrowingContinuation { (cont: CheckedContinuation<String, Error>) in
                    self.mdxContinuation = cont
                }
            }
            group.addTask {
                try await Task.sleep(nanoseconds: 10_000_000_000)
                throw CastError.screenIdTimeout
            }
            return try await group.next()!
        }
    }

    enum CastError: Error, LocalizedError {
        case sessionTimeout
        case screenIdTimeout
        case mdxSendFailed(String)
        var errorDescription: String? {
            switch self {
            case .sessionTimeout: return "Cast session timed out"
            case .screenIdTimeout: return "No screenId from the TV"
            case .mdxSendFailed(let m): return "MDX send failed: \(m)"
            }
        }
    }
}

// MARK: - MDX channel (urn:x-cast:com.google.youtube.mdx)

final class MDXChannel: GCKCastChannel {
    var onScreenId: ((String) -> Void)?

    init() {
        super.init(namespace: CastManager.mdxNamespace)
    }

    override func didReceiveTextMessage(_ message: String) {
        guard let data = message.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              json["type"] as? String == "mdxSessionStatus",
              let inner = json["data"] as? [String: Any],
              let screenId = inner["screenId"] as? String else { return }
        onScreenId?(screenId)
    }
}

// MARK: - Discovery + session delegates

extension CastManager: GCKDiscoveryManagerListener {
    func didInsertDevice(_ device: GCKDevice, at index: UInt) {
        castDevices[device.deviceID] = CastDevice(
            id: device.deviceID, name: device.friendlyName, state: "IDLE", title: "",
            contentId: nil, position: 0, isPhone: false, host: "", port: 0, gckDevice: device)
        rebuildDevices()
    }

    func didUpdateDevice(_ device: GCKDevice, at index: UInt) {
        if castDevices[device.deviceID] != nil {
            castDevices[device.deviceID]?.name = device.friendlyName
            rebuildDevices()
        }
    }

    func didRemoveDevice(_ device: GCKDevice, at index: UInt) {
        castDevices.removeValue(forKey: device.deviceID)
        rebuildDevices()
    }
}

extension CastManager: GCKSessionManagerListener {
    func sessionManager(_ manager: GCKSessionManager, didStartCastSession session: GCKCastSession) {
        sessionContinuation?.resume(returning: session)
        sessionContinuation = nil
    }

    func sessionManager(_ manager: GCKSessionManager, didFailToStartCastSession session: GCKCastSession, withError error: Error) {
        sessionContinuation?.resume(throwing: error)
        sessionContinuation = nil
    }
}
