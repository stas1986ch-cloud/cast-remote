/**
 * @file CastManager.swift
 * @summary Manages Chromecast discovery, Cast/MDX YouTube playback, mirroring, favorite device pinning, and TV power on/off.
 * @description Wraps Google Cast SDK v4.8 listeners and sessions with safe timeout continuations, persists pinned device IDs in UserDefaults, and delegates TV power control to AndroidTVRemoteManager with Cast wake/stop fallback.
 * @testing Pinning sort logic and Cast/MDX state transitions are verified via static type audit and automated Python logic/protocol tests.
 */

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
    var host: String
    let port: Int
    let gckDevice: GCKDevice?
    var isPinned: Bool = false
    var isPowerOn: Bool? = nil

    static func phone(name: String, host: String, port: Int, isPinned: Bool = false) -> CastDevice {
        CastDevice(
            id: "phone:\(name)",
            name: name,
            state: "PHONE",
            title: "Cast Remote",
            contentId: nil,
            position: 0,
            isPhone: true,
            host: host,
            port: port,
            gckDevice: nil,
            isPinned: isPinned,
            isPowerOn: nil
        )
    }
}

/// Owns Chromecast discovery/sessions (Google Cast SDK), phone targets
/// (PhoneReceiver), favorite pinning, and TV power control (AndroidTVRemoteManager).
///
/// iOS architecture note: the Cast SDK holds one active session. Mirror is
/// therefore sequential, not parallel like Android: connect to each TV in
/// turn, read its status or play, then move on. Disconnecting a session does
/// NOT stop playback on that TV; only an explicit stop does.
@MainActor
final class CastManager: NSObject, ObservableObject {
    static let youTubeAppId = "233637DE"
    static let mdxNamespace = "urn:x-cast:com.google.youtube.mdx"
    private static let pinnedDefaultsKey = "pinnedDeviceIds"

    @Published var devices: [CastDevice] = []
    @Published var status: String = "Tap Refresh to find TVs."
    @Published var isBusy: Bool = false
    @Published var pinnedDeviceIds: Set<String>

    let atvRemote = AndroidTVRemoteManager()

    private var castDevices: [String: CastDevice] = [:]
    private var phoneTargets: [String: (host: String, port: Int)] = [:]
    private var sessionContinuation: CheckedContinuation<GCKCastSession, Error>?
    private var sessionTimeoutTask: Task<Void, Never>?
    private var mdxContinuation: CheckedContinuation<String, Error>?
    private var mdxTimeoutTask: Task<Void, Never>?
    private var mdxChannel: MDXChannel?
    private var refreshTask: Task<Void, Never>?

    override init() {
        let savedPins = UserDefaults.standard.stringArray(forKey: Self.pinnedDefaultsKey) ?? []
        self.pinnedDeviceIds = Set(savedPins)
        super.init()

        let context = GCKCastContext.sharedInstance()
        context.discoveryManager.add(self)
        context.sessionManager.add(self)

        PhoneReceiver.shared.onPhonesFound = { [weak self] phones in
            Task { @MainActor in
                for p in phones { self?.phoneTargets[p.name] = (p.host, p.port) }
                self?.rebuildDevices()
            }
        }
        PhoneReceiver.shared.onPhoneRemoved = { [weak self] name in
            Task { @MainActor in
                self?.phoneTargets.removeValue(forKey: name)
                self?.rebuildDevices()
            }
        }
    }

    // MARK: - Pinning Favorites

    func isPinned(_ device: CastDevice) -> Bool {
        pinnedDeviceIds.contains(device.id)
    }

    func togglePin(_ device: CastDevice) {
        if pinnedDeviceIds.contains(device.id) {
            pinnedDeviceIds.remove(device.id)
            status = "Unpinned \(device.name)."
        } else {
            pinnedDeviceIds.insert(device.id)
            status = "Pinned \(device.name) to top."
        }
        UserDefaults.standard.set(Array(pinnedDeviceIds), forKey: Self.pinnedDefaultsKey)
        rebuildDevices()
    }

    // MARK: - Discovery

    func refresh() {
        status = "Working: discovering..."
        let context = GCKCastContext.sharedInstance()
        if !context.discoveryManager.discoveryActive {
            context.discoveryManager.startDiscovery()
        }
        // Sync any already-discovered devices from Cast SDK cache.
        let count = context.discoveryManager.deviceCount
        for idx in 0..<count {
            let dev = context.discoveryManager.device(at: idx)
            upsertCastDevice(dev)
        }
        PhoneReceiver.shared.discoverPhones()

        refreshTask?.cancel()
        refreshTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            guard !Task.isCancelled else { return }
            self?.rebuildDevices()
            self?.updateStatus()
        }
    }

    private func upsertCastDevice(_ device: GCKDevice) {
        let friendly = device.friendlyName ?? device.deviceID
        let ip = device.networkAddress.ipAddress ?? ""
        if var existing = castDevices[device.deviceID] {
            existing.name = friendly
            if !ip.isEmpty { existing.host = ip }
            castDevices[device.deviceID] = existing
        } else {
            castDevices[device.deviceID] = CastDevice(
                id: device.deviceID,
                name: friendly,
                state: "IDLE",
                title: "",
                contentId: nil,
                position: 0,
                isPhone: false,
                host: ip,
                port: 0,
                gckDevice: device,
                isPinned: pinnedDeviceIds.contains(device.deviceID),
                isPowerOn: atvRemote.powerStates[device.deviceID]
            )
        }
    }

    func rebuildDevices() {
        var all: [CastDevice] = castDevices.values.map { dev in
            var copy = dev
            copy.isPinned = pinnedDeviceIds.contains(dev.id)
            copy.isPowerOn = atvRemote.powerStates[dev.id]
            return copy
        }
        for (name, hp) in phoneTargets {
            let id = "phone:\(name)"
            all.append(.phone(name: name, host: hp.host, port: hp.port, isPinned: pinnedDeviceIds.contains(id)))
        }
        // Pinned favorites always appear at the top, sorted alphabetically within each group.
        all.sort { lhs, rhs in
            if lhs.isPinned != rhs.isPinned {
                return lhs.isPinned && !rhs.isPinned
            }
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
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

    // MARK: - Power On / Off (Android TV Remote v2 + Cast Fallback)

    func pairTV(_ device: CastDevice) async {
        guard !device.isPhone else { return }
        guard !device.host.isEmpty else {
            status = "Cannot pair \(device.name): IP address unavailable."
            return
        }
        status = "Connecting to \(device.name) for PIN pairing..."
        do {
            try await atvRemote.beginPairing(deviceId: device.id, name: device.name, host: device.host)
            status = "Enter the 6-character code shown on \(device.name)."
        } catch {
            status = "Pair \(device.name) failed: \(error.localizedDescription)"
        }
    }

    func submitPairingPIN(_ pin: String) async {
        do {
            let targetName = atvRemote.pairingTarget?.name ?? "TV"
            try await atvRemote.completePairing(pin: pin)
            status = "Paired with \(targetName)! Power On/Off is now enabled."
            rebuildDevices()
        } catch {
            atvRemote.pairingError = error.localizedDescription
        }
    }

    func powerOn(_ device: CastDevice) async {
        guard !device.isPhone else { return }
        guard !isBusy else { status = "Wait for current action to finish."; return }
        isBusy = true
        defer { isBusy = false }

        if atvRemote.isPaired(device.id), !device.host.isEmpty {
            status = "Turning on \(device.name)..."
            do {
                _ = try await atvRemote.sendPower(deviceId: device.id, host: device.host, action: .powerOn)
                status = "Turned on \(device.name)."
                rebuildDevices()
                return
            } catch {
                // Fall through to Cast wake if Android TV remote socket is asleep/unreachable.
                status = "Remote wake failed (\(error.localizedDescription)); waking via Cast..."
            }
        }

        // Cast Wake fallback: starting a Cast session wakes HDMI-CEC TVs.
        guard let gck = device.gckDevice else {
            status = "Turn on \(device.name) failed: no Cast device."
            return
        }
        status = "Waking \(device.name) via Cast..."
        do {
            _ = try await session(for: gck)
            atvRemote.powerStates[device.id] = true
            rebuildDevices()
            status = atvRemote.isPaired(device.id)
                ? "Woke \(device.name) via Cast."
                : "Woke \(device.name) via Cast. Tap Pair for full TV Power Off."
        } catch {
            status = "Turn on \(device.name) failed: \(error.localizedDescription)"
        }
    }

    func powerOff(_ device: CastDevice) async {
        guard !device.isPhone else { return }
        guard !isBusy else { status = "Wait for current action to finish."; return }
        isBusy = true
        defer { isBusy = false }

        if atvRemote.isPaired(device.id), !device.host.isEmpty {
            status = "Turning off \(device.name)..."
            do {
                _ = try await atvRemote.sendPower(deviceId: device.id, host: device.host, action: .powerOff)
                castDevices[device.id]?.state = "IDLE"
                castDevices[device.id]?.title = ""
                status = "Turned off \(device.name)."
                rebuildDevices()
                return
            } catch {
                status = "Turn off \(device.name) failed: \(error.localizedDescription)"
                return
            }
        }

        // Not paired yet: try starting Android TV Remote v2 pairing first so Google TV/Android TV users
        // get real TV power-off; if port 6467 is unsupported (legacy Chromecast dongle), fall back to Cast Stop.
        if !device.host.isEmpty {
            status = "Checking \(device.name) for TV power support..."
            do {
                try await atvRemote.beginPairing(deviceId: device.id, name: device.name, host: device.host)
                status = "Enter the 6-character code on \(device.name) to enable Power Off."
                return
            } catch {
                // Likely a non-Android-TV Chromecast dongle; fall back to stopping playback/idle.
            }
        }

        if let gck = device.gckDevice {
            do {
                let sess = try await session(for: gck)
                sess.remoteMediaClient?.stop()
                try? await Task.sleep(nanoseconds: 200_000_000)
                GCKCastContext.sharedInstance().sessionManager.endSessionAndStopCasting(true)
                castDevices[device.id]?.state = "IDLE"
                castDevices[device.id]?.title = ""
                atvRemote.powerStates[device.id] = false
                rebuildDevices()
                status = "Stopped \(device.name) to idle (Chromecast without Android TV has no TV power-off API)."
            } catch {
                status = "Power off \(device.name) failed: \(error.localizedDescription)"
            }
        }
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
        guard !isBusy else { status = "Wait for current action to finish."; return }
        guard let gck = device.gckDevice else { status = "Play failed: no device"; return }
        isBusy = true
        defer { isBusy = false }

        status = "Working: playing on \(device.name)..."
        do {
            let session = try await session(for: gck)
            let screenId = try await mdxScreenId(session: session)
            let lounge = YouTubeLoungeService(screenId: screenId)
            try await lounge.playVideo(videoId, startTime: String(start))
            castDevices[device.id]?.state = "PLAYING"
            castDevices[device.id]?.contentId = videoId
            rebuildDevices()
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
        guard !isBusy else { status = "Wait for current action to finish."; return }
        isBusy = true
        defer { isBusy = false }

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
            let targets = target.map { [$0] } ?? devices.filter { $0.id != source.id && !$0.name.lowercased().hasSuffix("group") }
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
                        castDevices[device.id]?.state = "PLAYING"
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
            rebuildDevices()
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
                    castDevices[d.id]?.state = d.state
                    castDevices[d.id]?.title = d.title
                    castDevices[d.id]?.contentId = contentId
                    castDevices[d.id]?.position = d.position
                    rebuildDevices()
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
        guard !isBusy else { status = "Wait for current action to finish."; return }
        guard let gck = device.gckDevice else { return }
        isBusy = true
        defer { isBusy = false }

        status = "Working: stopping \(device.name)..."
        do {
            let session = try await session(for: gck)
            session.remoteMediaClient?.stop()
            try? await Task.sleep(nanoseconds: 200_000_000)
            GCKCastContext.sharedInstance().sessionManager.endSessionAndStopCasting(true)
            castDevices[device.id]?.state = "IDLE"
            castDevices[device.id]?.title = ""
            rebuildDevices()
            status = "Stop \(device.name): ok"
        } catch {
            status = "Stop \(device.name) failed: \(error.localizedDescription)"
        }
    }

    func stopAll() async {
        guard !isBusy else { status = "Wait for current action to finish."; return }
        isBusy = true
        defer { isBusy = false }

        status = "Working: stopping all..."
        for device in devices where !device.isPhone {
            if let gck = device.gckDevice {
                do {
                    let session = try await session(for: gck)
                    session.remoteMediaClient?.stop()
                    try? await Task.sleep(nanoseconds: 150_000_000)
                    GCKCastContext.sharedInstance().sessionManager.endSessionAndStopCasting(true)
                    castDevices[device.id]?.state = "IDLE"
                    castDevices[device.id]?.title = ""
                } catch { continue }
            }
        }
        rebuildDevices()
        status = "Stop all: ok"
    }

    // MARK: - Sessions + MDX

    /// Starts (or reuses) a Cast session for a device. The SDK joins the
    /// YouTube app if it is already running on that TV.
    private func session(for device: GCKDevice) async throws -> GCKCastSession {
        let manager = GCKCastContext.sharedInstance().sessionManager
        if let current = manager.currentCastSession, current.device.deviceID == device.deviceID {
            return current
        }
        if manager.currentCastSession != nil {
            manager.endSessionAndStopCasting(false)
            try? await Task.sleep(nanoseconds: 150_000_000)
        }
        // Fail any stale continuation before starting a new one.
        resolveSessionContinuation(.failure(CastError.sessionTimeout))

        guard manager.startSession(with: device) else {
            throw CastError.sessionStartFailed
        }

        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<GCKCastSession, Error>) in
            self.sessionContinuation = cont
            self.sessionTimeoutTask?.cancel()
            self.sessionTimeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                guard !Task.isCancelled else { return }
                self?.resolveSessionContinuation(.failure(CastError.sessionTimeout))
            }
        }
    }

    private func resolveSessionContinuation(_ result: Result<GCKCastSession, Error>) {
        sessionTimeoutTask?.cancel()
        sessionTimeoutTask = nil
        guard let cont = sessionContinuation else { return }
        sessionContinuation = nil
        cont.resume(with: result)
    }

    /// Sends getMdxSessionStatus on the MDX namespace and waits for the
    /// screenId, mirroring pychromecast's update_screen_id().
    private func mdxScreenId(session: GCKCastSession) async throws -> String {
        if let old = mdxChannel {
            session.remove(old)
            mdxChannel = nil
        }
        resolveMdxContinuation(.failure(CastError.screenIdTimeout))

        let channel = MDXChannel()
        channel.onScreenId = { [weak self] screenId in
            Task { @MainActor in
                self?.resolveMdxContinuation(.success(screenId))
            }
        }
        session.add(channel)
        mdxChannel = channel

        var error: NSError?
        guard channel.sendTextMessage("{\"type\":\"getMdxSessionStatus\"}", error: &error) else {
            throw CastError.mdxSendFailed(error?.localizedDescription ?? "unknown")
        }

        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<String, Error>) in
            self.mdxContinuation = cont
            self.mdxTimeoutTask?.cancel()
            self.mdxTimeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                guard !Task.isCancelled else { return }
                self?.resolveMdxContinuation(.failure(CastError.screenIdTimeout))
            }
        }
    }

    private func resolveMdxContinuation(_ result: Result<String, Error>) {
        mdxTimeoutTask?.cancel()
        mdxTimeoutTask = nil
        guard let cont = mdxContinuation else { return }
        mdxContinuation = nil
        cont.resume(with: result)
    }

    enum CastError: Error, LocalizedError {
        case sessionStartFailed
        case sessionTimeout
        case screenIdTimeout
        case mdxSendFailed(String)
        var errorDescription: String? {
            switch self {
            case .sessionStartFailed: return "Could not start Cast session"
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

// MARK: - Discovery + session delegates (Swift-bridged Cast SDK v4.8 selectors)

extension CastManager: GCKDiscoveryManagerListener {
    @objc nonisolated func didInsert(_ device: GCKDevice, at index: UInt) {
        Task { @MainActor in
            self.upsertCastDevice(device)
            self.rebuildDevices()
        }
    }

    @objc nonisolated func didUpdate(_ device: GCKDevice, at index: UInt) {
        Task { @MainActor in
            self.upsertCastDevice(device)
            self.rebuildDevices()
        }
    }

    @objc nonisolated func didRemove(_ device: GCKDevice, at index: UInt) {
        Task { @MainActor in
            self.castDevices.removeValue(forKey: device.deviceID)
            self.rebuildDevices()
        }
    }
}

extension CastManager: GCKSessionManagerListener {
    @objc nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKCastSession) {
        Task { @MainActor in
            self.resolveSessionContinuation(.success(session))
        }
    }

    @objc nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKCastSession, withError error: Error) {
        Task { @MainActor in
            self.resolveSessionContinuation(.failure(error))
        }
    }
}
