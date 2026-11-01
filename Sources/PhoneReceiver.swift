import Foundation
import Network
import UIKit

/// Phone/tablet receiver mode: the same protocol the Android app speaks, so
/// Android and iOS devices can cast to each other.
///
/// - Advertises `Cast Remote <device name>` on `_castremote._tcp` (NetService).
/// - Listens on TCP 8766 (NWListener). `POST /play` with
///   `{"video_id": "...", "start": 0}` opens the video in the YouTube app
///   (`youtube://` scheme, https fallback).
/// - Discovers other Cast Remote devices with NetServiceBrowser.
/// - Sends to a phone with a plain HTTP POST.
///
/// iOS limit: this only works while the app is open on screen. iOS suspends
/// background network servers; there is no foreground-service equivalent.
final class PhoneReceiver: NSObject, ObservableObject {
    static let shared = PhoneReceiver()
    static let port: UInt16 = 8766
    static let serviceType = "_castremote._tcp."

    @Published var isReceiving = false
    @Published var receiveName = ""
    @Published var lastIncomingVideoId: String?

    private var listener: NWListener?
    private var netService: NetService?
    private var browser: NetServiceBrowser?
    private var resolving: [NetService] = []
    var onPhonesFound: (([(name: String, host: String, port: Int)]) -> Void)?

    // MARK: - Receiver

    func startReceiving() {
        guard !isReceiving else { return }
        let name = "Cast Remote \(UIDevice.current.name)"
        do {
            let listener = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: Self.port)!)
            listener.stateUpdateHandler = { state in
                if case .failed(let error) = state {
                    print("Cast Remote listener failed: \(error)")
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                self?.handleConnection(connection)
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            print("Cast Remote listener error: \(error)")
            return
        }
        let service = NetService(domain: "local.", type: Self.serviceType, name: name, port: Int32(Self.port))
        service.publish()
        netService = service
        isReceiving = true
        receiveName = name
    }

    func stopReceiving() {
        netService?.stop()
        netService = nil
        listener?.cancel()
        listener = nil
        isReceiving = false
        receiveName = ""
    }

    private func handleConnection(_ connection: NWConnection) {
        connection.start(queue: .main)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, _, _ in
            defer { connection.cancel() }
            guard let data = data, let request = String(data: data, encoding: .utf8) else { return }
            let parts = request.components(separatedBy: "\r\n\r\n")
            guard parts.count >= 2 else { return }
            let head = parts[0]
            let body = parts.dropFirst().joined(separator: "\r\n\r\n")
            let response: String
            if head.hasPrefix("POST /play") {
                if let json = try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any],
                   let videoId = json["video_id"] as? String, !videoId.isEmpty {
                    let start = json["start"] as? Int ?? 0
                    DispatchQueue.main.async {
                        self?.lastIncomingVideoId = videoId
                        self?.openYouTube(videoId: videoId, start: start)
                    }
                }
                response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
            } else if head.hasPrefix("GET /ping") {
                response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
            } else {
                response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            }
            connection.send(content: response.data(using: .utf8), completion: .contentProcessed { _ in })
        }
    }

    private func openYouTube(videoId: String, start: Int) {
        let appURL = URL(string: "youtube://watch?v=\(videoId)&t=\(start)s")!
        let webURL = URL(string: "https://www.youtube.com/watch?v=\(videoId)&t=\(start)s")!
        if UIApplication.shared.canOpenURL(appURL) {
            UIApplication.shared.open(appURL)
        } else {
            UIApplication.shared.open(webURL)
        }
    }

    // MARK: - Discovery + sending

    func discoverPhones(timeout: TimeInterval = 5) {
        let browser = NetServiceBrowser()
        browser.delegate = self
        self.browser = browser
        browser.searchForServices(ofType: Self.serviceType, inDomain: "local.")
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
            browser.stop()
            self?.browser = nil
        }
    }

    func sendToPhone(host: String, port: Int, videoId: String, start: Int, completion: @escaping (Result<Void, Error>) -> Void) {
        guard let url = URL(string: "http://\(host):\(port)/play") else {
            completion(.failure(PhoneError.badURL)); return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 5
        request.httpBody = try? JSONSerialization.data(withJSONObject: ["video_id": videoId, "start": start])
        URLSession.shared.dataTask(with: request) { _, response, error in
            if let error = error { completion(.failure(error)); return }
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                completion(.failure(PhoneError.badStatus)); return
            }
            completion(.success(()))
        }.resume()
    }

    enum PhoneError: Error { case badURL, badStatus }
}

// MARK: - NetServiceBrowserDelegate

extension PhoneReceiver: NetServiceBrowserDelegate {
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        service.delegate = self
        resolving.append(service)
        service.resolve(withTimeout: 5)
        if !moreComing { flushResolved() }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        resolving.removeAll { $0 === service }
    }

    private func flushResolved() {
        // Resolved services report via netServiceDidResolveAddress; collect
        // them there. This flush is a no-op hook for batching.
    }
}

// MARK: - NetServiceDelegate

extension PhoneReceiver: NetServiceDelegate {
    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let addresses = sender.addresses else { return }
        for data in addresses {
            if let host = hostFromSockaddr(data) {
                onPhonesFound?([(name: sender.name, host: host, port: sender.port)])
                break
            }
        }
        resolving.removeAll { $0 === sender }
    }

    private func hostFromSockaddr(_ data: Data) -> String? {
        return data.withUnsafeBytes { (ptr: UnsafeRawBufferPointer) -> String? in
            guard let base = ptr.baseAddress else { return nil }
            let sa = base.assumingMemoryBound(to: sockaddr.self).pointee
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if sa.sa_family == sa_family_t(AF_INET) {
                var addr = base.assumingMemoryBound(to: sockaddr_in.self).pointee.sin_addr
                guard inet_ntop(AF_INET, &addr, &buffer, socklen_t(NI_MAXHOST)) != nil else { return nil }
                return String(cString: buffer)
            } else if sa.sa_family == sa_family_t(AF_INET6) {
                var addr = base.assumingMemoryBound(to: sockaddr_in6.self).pointee.sin6_addr
                guard inet_ntop(AF_INET6, &addr, &buffer, socklen_t(NI_MAXHOST)) != nil else { return nil }
                return String(cString: buffer)
            }
            return nil
        }
    }
}
