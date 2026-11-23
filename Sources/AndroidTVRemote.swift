/**
 * @file AndroidTVRemote.swift
 * @summary Implements Android TV / Google TV Remote Protocol v2 pairing (port 6467) and power control (port 6466) over Mutual TLS.
 * @description Generates a persistent self-signed RSA-2048 client certificate in iOS Keychain, exchanges varint-framed Protobuf messages over Network.framework TLS, and sends state-aware power on/off keycodes.
 * @testing Protobuf wire encoding/decoding, ASN.1 DER certificate/public-key parsing, and SHA-256 pairing secret math are verified with an automated Python test suite matching androidtvremote2.
 */

import CryptoKit
import Foundation
import Network
import Security

// MARK: - Power Action & State

enum TVPowerAction {
    case powerOn
    case powerOff
    case toggle
}

enum ATVRemoteError: Error, LocalizedError {
    case missingHost
    case identityFailed(String)
    case connectionFailed(String)
    case timeout
    case notPaired
    case invalidPIN
    case pinMismatch
    case pairingRejected(Int)
    case protocolError(String)

    var errorDescription: String? {
        switch self {
        case .missingHost: return "TV IP address unavailable"
        case .identityFailed(let m): return "Client cert error: \(m)"
        case .connectionFailed(let m): return "TV connection failed: \(m)"
        case .timeout: return "TV remote timed out"
        case .notPaired: return "TV needs one-time PIN pairing"
        case .invalidPIN: return "Enter the 6-character hex code shown on your TV"
        case .pinMismatch: return "PIN code did not match — check the TV screen and retry"
        case .pairingRejected(let code): return "TV rejected pairing (status \(code))"
        case .protocolError(let m): return "TV protocol error: \(m)"
        }
    }
}

// MARK: - Minimal ASN.1 DER Encoder / PKCS#1 RSA Parser

enum ASN1DER {
    static func tlv(tag: UInt8, content: Data) -> Data {
        var out = Data([tag])
        let len = content.count
        if len < 0x80 {
            out.append(UInt8(len))
        } else {
            var lenBytes: [UInt8] = []
            var rem = len
            while rem > 0 {
                lenBytes.insert(UInt8(rem & 0xFF), at: 0)
                rem >>= 8
            }
            out.append(0x80 | UInt8(lenBytes.count))
            out.append(contentsOf: lenBytes)
        }
        out.append(content)
        return out
    }

    static func sequence(_ parts: [Data]) -> Data {
        var body = Data()
        for p in parts { body.append(p) }
        return tlv(tag: 0x30, content: body)
    }

    static func set(_ parts: [Data]) -> Data {
        var body = Data()
        for p in parts { body.append(p) }
        return tlv(tag: 0x31, content: body)
    }

    static func integer(_ rawBigEndian: Data) -> Data {
        var bytes = rawBigEndian
        while bytes.count > 1 && bytes.first == 0x00 {
            bytes.removeFirst()
        }
        if bytes.isEmpty {
            bytes = Data([0x00])
        } else if (bytes[bytes.startIndex] & 0x80) != 0 {
            bytes.insert(0x00, at: 0)
        }
        return tlv(tag: 0x02, content: bytes)
    }

    static func bitString(_ data: Data) -> Data {
        var content = Data([0x00]) // 0 unused bits
        content.append(data)
        return tlv(tag: 0x03, content: content)
    }

    static func null() -> Data {
        Data([0x05, 0x00])
    }

    static func oid(_ bytes: [UInt8]) -> Data {
        tlv(tag: 0x06, content: Data(bytes))
    }

    static func utf8String(_ text: String) -> Data {
        tlv(tag: 0x0C, content: Data(text.utf8))
    }

    static func utcTime(_ text: String) -> Data {
        tlv(tag: 0x17, content: Data(text.utf8))
    }

    static func explicitTag(_ index: UInt8, content: Data) -> Data {
        tlv(tag: 0xA0 | index, content: content)
    }

    /// Parses PKCS#1 RSAPublicKey DER (`SEQUENCE { INTEGER n, INTEGER e }`)
    /// into unsigned big-endian modulus and exponent (no leading 0x00 sign byte).
    static func parseRSAPublicKey(_ pkcs1DER: Data) -> (modulus: Data, exponent: Data)? {
        let bytes = [UInt8](pkcs1DER)
        var idx = 0
        guard let seq = readTLV(bytes, index: &idx), seq.tag == 0x30 else { return nil }
        let inner = [UInt8](seq.value)
        var innerIdx = 0
        guard let modTLV = readTLV(inner, index: &innerIdx), modTLV.tag == 0x02,
              let expTLV = readTLV(inner, index: &innerIdx), expTLV.tag == 0x02 else { return nil }
        return (stripLeadingZero(modTLV.value), stripLeadingZero(expTLV.value))
    }

    private static func stripLeadingZero(_ data: Data) -> Data {
        var d = data
        while d.count > 1 && d.first == 0x00 {
            d.removeFirst()
        }
        return d
    }

    private static func readTLV(_ bytes: [UInt8], index: inout Int) -> (tag: UInt8, value: Data)? {
        guard index < bytes.count else { return nil }
        let tag = bytes[index]
        index += 1
        guard index < bytes.count else { return nil }
        let firstLen = bytes[index]
        index += 1
        var length = 0
        if firstLen < 0x80 {
            length = Int(firstLen)
        } else {
            let count = Int(firstLen & 0x7F)
            guard count > 0, count <= 4, index + count <= bytes.count else { return nil }
            for _ in 0..<count {
                length = (length << 8) | Int(bytes[index])
                index += 1
            }
        }
        guard index + length <= bytes.count else { return nil }
        let value = Data(bytes[index..<(index + length)])
        index += length
        return (tag, value)
    }
}

// MARK: - Persistent Self-Signed RSA-2048 Client Identity (Keychain)

final class ATVClientIdentity {
    static let shared = ATVClientIdentity()

    private let keyTag = "com.stas.castremote.atv.rsa2048".data(using: .utf8)!
    private let certLabel = "com.stas.castremote.atv.cert"

    struct Bundle {
        let identity: SecIdentity
        let modulus: Data
        let exponent: Data
    }

    private var cached: Bundle?

    func loadOrCreate() throws -> Bundle {
        if let cached = cached { return cached }

        if let existing = findExistingIdentity() {
            cached = existing
            return existing
        }

        // Clean any partial state before generating a fresh keypair + self-signed cert.
        deleteExistingItems()

        let keyAttrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: keyTag,
            ],
        ]
        var cfError: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(keyAttrs as CFDictionary, &cfError),
              let publicKey = SecKeyCopyPublicKey(privateKey),
              let pubDER = SecKeyCopyExternalRepresentation(publicKey, &cfError) as Data? else {
            let msg = cfError?.takeRetainedValue().localizedDescription ?? "Key generation failed"
            throw ATVRemoteError.identityFailed(msg)
        }

        guard let rsa = ASN1DER.parseRSAPublicKey(pubDER) else {
            throw ATVRemoteError.identityFailed("Could not parse generated RSA public key")
        }

        let certDER = try buildSelfSignedCertificate(privateKey: privateKey, pkcs1PublicKeyDER: pubDER)
        guard let secCert = SecCertificateCreateWithData(nil, certDER as CFData) else {
            throw ATVRemoteError.identityFailed("Generated X.509 certificate was invalid")
        }

        let addCertQuery: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: secCert,
            kSecAttrLabel as String: certLabel,
        ]
        let status = SecItemAdd(addCertQuery as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw ATVRemoteError.identityFailed("Keychain cert store failed (\(status))")
        }

        guard let created = findExistingIdentity() else {
            throw ATVRemoteError.identityFailed("Keychain did not return SecIdentity")
        }
        let bundle = Bundle(identity: created.identity, modulus: rsa.modulus, exponent: rsa.exponent)
        cached = bundle
        return bundle
    }

    private func findExistingIdentity() -> Bundle? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecAttrLabel as String: certLabel,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let raw = item,
              CFGetTypeID(raw) == SecIdentityGetTypeID() else {
            return nil
        }
        let identity = raw as! SecIdentity
        var certRef: SecCertificate?
        guard SecIdentityCopyCertificate(identity, &certRef) == errSecSuccess,
              let cert = certRef,
              let pubKey = SecCertificateCopyKey(cert),
              let pubDER = SecKeyCopyExternalRepresentation(pubKey, nil) as Data?,
              let rsa = ASN1DER.parseRSAPublicKey(pubDER) else {
            return nil
        }
        return Bundle(identity: identity, modulus: rsa.modulus, exponent: rsa.exponent)
    }

    private func deleteExistingItems() {
        let certQuery: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecAttrLabel as String: certLabel,
        ]
        SecItemDelete(certQuery as CFDictionary)
        let keyQuery: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: keyTag,
        ]
        SecItemDelete(keyQuery as CFDictionary)
    }

    private func buildSelfSignedCertificate(privateKey: SecKey, pkcs1PublicKeyDER: Data) throws -> Data {
        // OIDs:
        // sha256WithRSAEncryption: 1.2.840.113549.1.1.11
        let oidSha256WithRSA: [UInt8] = [0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B]
        // rsaEncryption: 1.2.840.113549.1.1.1
        let oidRSAEncryption: [UInt8] = [0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01]
        // commonName: 2.5.4.3
        let oidCN: [UInt8] = [0x55, 0x04, 0x03]

        let sigAlg = ASN1DER.sequence([ASN1DER.oid(oidSha256WithRSA), ASN1DER.null()])
        let version = ASN1DER.explicitTag(0, content: ASN1DER.integer(Data([0x02]))) // X.509 v3

        var serialBytes = [UInt8](repeating: 0, count: 8)
        _ = SecRandomCopyBytes(kSecRandomDefault, serialBytes.count, &serialBytes)
        serialBytes[0] = (serialBytes[0] & 0x7F) | 0x01
        let serial = ASN1DER.integer(Data(serialBytes))

        let dn = ASN1DER.sequence([
            ASN1DER.set([
                ASN1DER.sequence([
                    ASN1DER.oid(oidCN),
                    ASN1DER.utf8String("CastRemote"),
                ])
            ])
        ])

        let validity = ASN1DER.sequence([
            ASN1DER.utcTime("240101000000Z"),
            ASN1DER.utcTime("491231235959Z"),
        ])

        let spki = ASN1DER.sequence([
            ASN1DER.sequence([ASN1DER.oid(oidRSAEncryption), ASN1DER.null()]),
            ASN1DER.bitString(pkcs1PublicKeyDER),
        ])

        let tbs = ASN1DER.sequence([version, serial, sigAlg, dn, validity, dn, spki])

        var cfError: Unmanaged<CFError>?
        guard let sig = SecKeyCreateSignature(
            privateKey,
            .rsaSignatureMessagePKCS1v15SHA256,
            tbs as CFData,
            &cfError
        ) as Data? else {
            let msg = cfError?.takeRetainedValue().localizedDescription ?? "Cert signing failed"
            throw ATVRemoteError.identityFailed(msg)
        }

        return ASN1DER.sequence([tbs, sigAlg, ASN1DER.bitString(sig)])
    }
}

// MARK: - Lightweight Protobuf Wire Codec (Android TV Remote v2)

enum ProtobufWire {
    static func encodeVarint(_ value: UInt64) -> Data {
        var v = value
        var out = Data()
        while v >= 0x80 {
            out.append(UInt8((v & 0x7F) | 0x80))
            v >>= 7
        }
        out.append(UInt8(v))
        return out
    }

    static func decodeVarint(_ data: Data, offset: inout Int) -> UInt64? {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        while offset < data.count {
            let byte = data[data.startIndex + offset]
            offset += 1
            result |= UInt64(byte & 0x7F) << shift
            if (byte & 0x80) == 0 {
                return result
            }
            shift += 7
            if shift >= 64 { return nil }
        }
        return nil
    }

    static func varintField(_ fieldNumber: Int, _ value: UInt64) -> Data {
        var out = encodeVarint(UInt64((fieldNumber << 3) | 0))
        out.append(encodeVarint(value))
        return out
    }

    static func bytesField(_ fieldNumber: Int, _ payload: Data) -> Data {
        var out = encodeVarint(UInt64((fieldNumber << 3) | 2))
        out.append(encodeVarint(UInt64(payload.count)))
        out.append(payload)
        return out
    }

    static func stringField(_ fieldNumber: Int, _ text: String) -> Data {
        bytesField(fieldNumber, Data(text.utf8))
    }

    /// Length-delimited wire frame: varint(payload.count) + payload.
    static func frame(_ payload: Data) -> Data {
        var out = encodeVarint(UInt64(payload.count))
        out.append(payload)
        return out
    }

    /// Parses top-level fields of a protobuf message into (fieldNumber, wireType, varintOrBytes).
    enum FieldValue {
        case varint(UInt64)
        case bytes(Data)
    }

    static func parseFields(_ data: Data) -> [(field: Int, value: FieldValue)] {
        var out: [(Int, FieldValue)] = []
        var offset = 0
        while offset < data.count {
            guard let tag = decodeVarint(data, offset: &offset) else { break }
            let fieldNumber = Int(tag >> 3)
            let wireType = Int(tag & 0x07)
            switch wireType {
            case 0:
                guard let v = decodeVarint(data, offset: &offset) else { return out }
                out.append((fieldNumber, .varint(v)))
            case 1:
                guard offset + 8 <= data.count else { return out }
                offset += 8
            case 2:
                guard let len = decodeVarint(data, offset: &offset) else { return out }
                let count = Int(len)
                guard count >= 0, offset + count <= data.count else { return out }
                let start = data.startIndex + offset
                out.append((fieldNumber, .bytes(Data(data[start..<(start + count)]))))
                offset += count
            case 5:
                guard offset + 4 <= data.count else { return out }
                offset += 4
            default:
                return out
            }
        }
        return out
    }

    // MARK: Pairing Messages (port 6467)

    static func pairingRequest(serviceName: String = "com.stas.castremote", clientName: String = "Cast Remote") -> Data {
        var req = Data()
        req.append(stringField(1, serviceName))
        req.append(stringField(2, clientName))
        var msg = Data()
        msg.append(varintField(1, 2))   // protocol_version = 2
        msg.append(varintField(2, 200)) // STATUS_OK = 200
        msg.append(bytesField(10, req)) // pairing_request
        return msg
    }

    static func pairingOption() -> Data {
        var encoding = Data()
        encoding.append(varintField(1, 3)) // ENCODING_TYPE_HEXADECIMAL = 3
        encoding.append(varintField(2, 6)) // symbol_length = 6
        var opt = Data()
        opt.append(bytesField(1, encoding)) // input_encodings
        opt.append(varintField(3, 1))       // preferred_role = ROLE_TYPE_INPUT (1)
        var msg = Data()
        msg.append(varintField(1, 2))
        msg.append(varintField(2, 200))
        msg.append(bytesField(20, opt))     // pairing_option
        return msg
    }

    static func pairingConfiguration() -> Data {
        var encoding = Data()
        encoding.append(varintField(1, 3)) // ENCODING_TYPE_HEXADECIMAL = 3
        encoding.append(varintField(2, 6)) // symbol_length = 6
        var cfg = Data()
        cfg.append(bytesField(1, encoding)) // encoding
        cfg.append(varintField(2, 1))       // client_role = ROLE_TYPE_INPUT (1)
        var msg = Data()
        msg.append(varintField(1, 2))
        msg.append(varintField(2, 200))
        msg.append(bytesField(30, cfg))     // pairing_configuration
        return msg
    }

    static func pairingSecret(_ secret: Data) -> Data {
        let sec = bytesField(1, secret)
        var msg = Data()
        msg.append(varintField(1, 2))
        msg.append(varintField(2, 200))
        msg.append(bytesField(40, sec))     // pairing_secret
        return msg
    }

    // MARK: Remote Messages (port 6466)

    static func remoteConfigure() -> Data {
        var info = Data()
        info.append(stringField(1, "iPhone"))
        info.append(stringField(2, "Apple"))
        info.append(varintField(3, 1))
        info.append(stringField(4, "1"))
        info.append(stringField(5, "com.stas.castremote"))
        info.append(stringField(6, "1.0"))
        var cfg = Data()
        cfg.append(varintField(1, 622))
        cfg.append(bytesField(2, info))
        return bytesField(1, cfg) // remote_configure = 1
    }

    static func remoteSetActive(_ active: UInt64 = 622) -> Data {
        let body = varintField(1, active)
        return bytesField(2, body) // remote_set_active = 2
    }

    static func remotePingResponse(_ val1: UInt64) -> Data {
        let body = varintField(1, val1)
        return bytesField(9, body) // remote_ping_response = 9
    }

    static func remoteKeyInject(keyCode: UInt64, direction: UInt64 = 3) -> Data {
        // RemoteKeyInject: key_code = 1, direction = 2 (SHORT = 3)
        var body = Data()
        body.append(varintField(1, keyCode))
        body.append(varintField(2, direction))
        return bytesField(10, body) // remote_key_inject = 10
    }
}

// MARK: - Framed Mutual TLS Transport

private final class ATVConnection {
    private final class ServerRSABox {
        var rsa: (modulus: Data, exponent: Data)?
    }

    private let connection: NWConnection
    private let queue = DispatchQueue(label: "com.stas.castremote.atv.conn")
    private var readBuffer = Data()
    private let serverRSABox = ServerRSABox()
    var serverRSA: (modulus: Data, exponent: Data)? { serverRSABox.rsa }

    init(host: String, port: UInt16, identity: SecIdentity) throws {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw ATVRemoteError.connectionFailed("Invalid port")
        }
        let tls = NWProtocolTLS.Options()
        let secOpts = tls.securityProtocolOptions
        if let secIdentity = sec_identity_create(identity) {
            sec_protocol_options_set_local_identity(secOpts, secIdentity)
        }
        sec_protocol_options_set_min_tls_protocol_version(secOpts, .TLSv12)

        let box = self.serverRSABox
        let verifyQueue = self.queue
        sec_protocol_options_set_verify_block(secOpts, { _, secTrust, complete in
            let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
            if let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
               let leaf = chain.first,
               let pubKey = SecCertificateCopyKey(leaf),
               let pubDER = SecKeyCopyExternalRepresentation(pubKey, nil) as Data?,
               let rsa = ASN1DER.parseRSAPublicKey(pubDER) {
                box.rsa = rsa
            }
            // Android TV uses self-signed certificates verified via the PIN secret hash.
            complete(true)
        }, verifyQueue)

        self.connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: NWParameters(tls: tls))
    }

    func connect(timeoutSeconds: UInt64 = 8) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            var resumed = false
            let lock = NSLock()
            func finish(_ result: Result<Void, Error>) {
                lock.lock()
                defer { lock.unlock() }
                guard !resumed else { return }
                resumed = true
                cont.resume(with: result)
            }

            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    finish(.success(()))
                case .failed(let err):
                    finish(.failure(ATVRemoteError.connectionFailed(err.localizedDescription)))
                case .cancelled:
                    finish(.failure(ATVRemoteError.connectionFailed("Cancelled")))
                default:
                    break
                }
            }
            connection.start(queue: queue)

            queue.asyncAfter(deadline: .now() + .seconds(Int(timeoutSeconds))) { [weak self] in
                lock.lock()
                let alreadyDone = resumed
                lock.unlock()
                if !alreadyDone {
                    self?.connection.cancel()
                    finish(.failure(ATVRemoteError.timeout))
                }
            }
        }
    }

    func sendMessage(_ payload: Data) async throws {
        let framed = ProtobufWire.frame(payload)
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.send(content: framed, completion: .contentProcessed { error in
                if let error = error {
                    cont.resume(throwing: ATVRemoteError.connectionFailed(error.localizedDescription))
                } else {
                    cont.resume()
                }
            })
        }
    }

    func receiveMessage(timeoutSeconds: UInt64 = 8) async throws -> Data {
        if let msg = extractBufferedMessage() {
            return msg
        }
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            var resumed = false
            let lock = NSLock()
            func finish(_ result: Result<Data, Error>) {
                lock.lock()
                defer { lock.unlock() }
                guard !resumed else { return }
                resumed = true
                cont.resume(with: result)
            }

            func readMore() {
                connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
                    guard let self = self else {
                        finish(.failure(ATVRemoteError.connectionFailed("Connection released")))
                        return
                    }
                    if let error = error {
                        finish(.failure(ATVRemoteError.connectionFailed(error.localizedDescription)))
                        return
                    }
                    if let data = data, !data.isEmpty {
                        self.readBuffer.append(data)
                        if let msg = self.extractBufferedMessage() {
                            finish(.success(msg))
                            return
                        }
                    }
                    if isComplete {
                        finish(.failure(ATVRemoteError.connectionFailed("Connection closed")))
                        return
                    }
                    lock.lock()
                    let done = resumed
                    lock.unlock()
                    if !done {
                        readMore()
                    }
                }
            }

            readMore()

            queue.asyncAfter(deadline: .now() + .seconds(Int(timeoutSeconds))) {
                finish(.failure(ATVRemoteError.timeout))
            }
        }
    }

    private func extractBufferedMessage() -> Data? {
        var offset = 0
        guard let len = ProtobufWire.decodeVarint(readBuffer, offset: &offset) else { return nil }
        let msgLen = Int(len)
        guard offset + msgLen <= readBuffer.count else { return nil }
        let start = readBuffer.startIndex + offset
        let payload = Data(readBuffer[start..<(start + msgLen)])
        readBuffer.removeFirst(offset + msgLen)
        return payload
    }

    func close() {
        connection.cancel()
    }
}

// MARK: - Pairing Secret Computation

enum ATVPairingCrypto {
    static func computeSecret(
        pinHex: String,
        clientModulus: Data,
        clientExponent: Data,
        serverModulus: Data,
        serverExponent: Data
    ) throws -> Data {
        let cleaned = pinHex.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard cleaned.count == 6, let pinBytes = hexToData(cleaned), pinBytes.count == 3 else {
            throw ATVRemoteError.invalidPIN
        }
        var hasher = SHA256()
        hasher.update(data: clientModulus)
        hasher.update(data: clientExponent)
        hasher.update(data: serverModulus)
        hasher.update(data: serverExponent)
        hasher.update(data: pinBytes.suffix(2)) // last 4 hex chars = 2 bytes
        let digest = Data(hasher.finalize())
        guard digest.first == pinBytes.first else {
            throw ATVRemoteError.pinMismatch
        }
        return digest
    }

    static func hexToData(_ hex: String) -> Data? {
        guard hex.count % 2 == 0 else { return nil }
        var data = Data(capacity: hex.count / 2)
        var idx = hex.startIndex
        while idx < hex.endIndex {
            let next = hex.index(idx, offsetBy: 2)
            guard let byte = UInt8(hex[idx..<next], radix: 16) else { return nil }
            data.append(byte)
            idx = next
        }
        return data
    }
}

// MARK: - Android TV Remote Manager

@MainActor
final class AndroidTVRemoteManager: ObservableObject {
    static let pairingPort: UInt16 = 6467
    static let remotePort: UInt16 = 6466

    static let keycodePower: UInt64 = 26
    static let keycodeSleep: UInt64 = 223
    static let keycodeWakeup: UInt64 = 224

    private static let pairedDevicesKey = "atvPairedDeviceIds"

    @Published var pairedDeviceIds: Set<String>
    @Published var powerStates: [String: Bool] = [:]
    @Published var pairingTarget: (deviceId: String, name: String, host: String)?
    @Published var pairingError: String?

    private var activePairingConn: ATVConnection?
    private var activeClientBundle: ATVClientIdentity.Bundle?

    init() {
        let saved = UserDefaults.standard.stringArray(forKey: Self.pairedDevicesKey) ?? []
        self.pairedDeviceIds = Set(saved)
    }

    func isPaired(_ deviceId: String) -> Bool {
        pairedDeviceIds.contains(deviceId)
    }

    func markPaired(_ deviceId: String) {
        pairedDeviceIds.insert(deviceId)
        UserDefaults.standard.set(Array(pairedDeviceIds), forKey: Self.pairedDevicesKey)
    }

    func unpair(_ deviceId: String) {
        pairedDeviceIds.remove(deviceId)
        powerStates.removeValue(forKey: deviceId)
        UserDefaults.standard.set(Array(pairedDeviceIds), forKey: Self.pairedDevicesKey)
    }

    // MARK: - Pairing Flow (Port 6467)

    func beginPairing(deviceId: String, name: String, host: String) async throws {
        cancelPairing()
        guard !host.isEmpty else { throw ATVRemoteError.missingHost }
        let bundle = try ATVClientIdentity.shared.loadOrCreate()
        let conn = try ATVConnection(host: host, port: Self.pairingPort, identity: bundle.identity)
        try await conn.connect(timeoutSeconds: 8)

        // Step 1: PairingRequest -> PairingRequestAck
        try await conn.sendMessage(ProtobufWire.pairingRequest())
        try validatePairingStatus(try await conn.receiveMessage())

        // Step 2: PairingOption -> PairingOption (server)
        try await conn.sendMessage(ProtobufWire.pairingOption())
        try validatePairingStatus(try await conn.receiveMessage())

        // Step 3: PairingConfiguration -> PairingConfigurationAck (TV displays 6-char hex PIN)
        try await conn.sendMessage(ProtobufWire.pairingConfiguration())
        try validatePairingStatus(try await conn.receiveMessage())

        activePairingConn = conn
        activeClientBundle = bundle
        pairingError = nil
        pairingTarget = (deviceId: deviceId, name: name, host: host)
    }

    func completePairing(pin: String) async throws {
        guard let conn = activePairingConn,
              let bundle = activeClientBundle,
              let target = pairingTarget else {
            throw ATVRemoteError.protocolError("No active pairing session")
        }
        guard let serverRSA = conn.serverRSA else {
            throw ATVRemoteError.protocolError("Missing TV TLS certificate")
        }

        let secret = try ATVPairingCrypto.computeSecret(
            pinHex: pin,
            clientModulus: bundle.modulus,
            clientExponent: bundle.exponent,
            serverModulus: serverRSA.modulus,
            serverExponent: serverRSA.exponent
        )
        try await conn.sendMessage(ProtobufWire.pairingSecret(secret))
        try validatePairingStatus(try await conn.receiveMessage())

        markPaired(target.deviceId)
        cancelPairing()
    }

    func cancelPairing() {
        activePairingConn?.close()
        activePairingConn = nil
        activeClientBundle = nil
        pairingTarget = nil
        pairingError = nil
    }

    private func validatePairingStatus(_ data: Data) throws {
        let fields = ProtobufWire.parseFields(data)
        for (field, value) in fields {
            if field == 2, case .varint(let status) = value, status != 200 {
                throw ATVRemoteError.pairingRejected(Int(status))
            }
        }
    }

    // MARK: - Power Control (Port 6466)

    /// Connects to Android TV Remote port 6466, reads live power state (`RemoteStart`),
    /// and sends the appropriate power keycode. Returns the resulting power state.
    func sendPower(deviceId: String, host: String, action: TVPowerAction) async throws -> Bool? {
        guard !host.isEmpty else { throw ATVRemoteError.missingHost }
        guard isPaired(deviceId) else { throw ATVRemoteError.notPaired }

        let bundle = try ATVClientIdentity.shared.loadOrCreate()
        let conn = try ATVConnection(host: host, port: Self.remotePort, identity: bundle.identity)
        defer { conn.close() }

        do {
            try await conn.connect(timeoutSeconds: 6)
        } catch {
            throw error
        }

        var isPoweredOn: Bool? = nil
        var configured = false
        var activeSet = false

        // Complete handshake: server sends RemoteConfigure (1), then RemoteSetActive (2),
        // and usually RemoteStart (40) with current TV power state.
        for _ in 0..<8 {
            let msg = try await conn.receiveMessage(timeoutSeconds: 4)
            let fields = ProtobufWire.parseFields(msg)
            for (field, value) in fields {
                switch field {
                case 1: // remote_configure
                    try await conn.sendMessage(ProtobufWire.remoteConfigure())
                    configured = true
                case 2: // remote_set_active
                    try await conn.sendMessage(ProtobufWire.remoteSetActive(622))
                    activeSet = true
                case 8: // remote_ping_request
                    if case .bytes(let pingData) = value {
                        let pingFields = ProtobufWire.parseFields(pingData)
                        let val1: UInt64 = pingFields.first(where: { $0.field == 1 }).flatMap {
                            if case .varint(let v) = $0.value { return v }
                            return nil
                        } ?? 0
                        try await conn.sendMessage(ProtobufWire.remotePingResponse(val1))
                    }
                case 40: // remote_start
                    if case .bytes(let startData) = value {
                        let startFields = ProtobufWire.parseFields(startData)
                        if let startedField = startFields.first(where: { $0.field == 1 }),
                           case .varint(let v) = startedField.value {
                            isPoweredOn = (v != 0)
                        } else {
                            isPoweredOn = false
                        }
                    }
                default:
                    break
                }
            }
            if configured && activeSet && isPoweredOn != nil {
                break
            }
        }

        let keyCodeToSend: UInt64?
        let expectedPowerState: Bool?
        switch action {
        case .powerOn:
            if isPoweredOn == true {
                keyCodeToSend = nil
                expectedPowerState = true
            } else if isPoweredOn == false {
                keyCodeToSend = Self.keycodePower
                expectedPowerState = true
            } else {
                keyCodeToSend = Self.keycodeWakeup
                expectedPowerState = true
            }
        case .powerOff:
            if isPoweredOn == false {
                keyCodeToSend = nil
                expectedPowerState = false
            } else if isPoweredOn == true {
                keyCodeToSend = Self.keycodePower
                expectedPowerState = false
            } else {
                keyCodeToSend = Self.keycodeSleep
                expectedPowerState = false
            }
        case .toggle:
            keyCodeToSend = Self.keycodePower
            expectedPowerState = isPoweredOn.map { !$0 }
        }

        if let keyCode = keyCodeToSend {
            try await conn.sendMessage(ProtobufWire.remoteKeyInject(keyCode: keyCode))
            // Brief settle so the TV processes the frame before TLS closes.
            try? await Task.sleep(nanoseconds: 250_000_000)
        }

        if let expected = expectedPowerState {
            powerStates[deviceId] = expected
        } else if let reported = isPoweredOn {
            powerStates[deviceId] = reported
        }
        return powerStates[deviceId]
    }
}
