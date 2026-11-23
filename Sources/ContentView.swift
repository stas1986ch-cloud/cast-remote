/**
 * @file ContentView.swift
 * @summary Main SwiftUI interface for discovering devices, pinning favorites, casting/mirroring YouTube, and powering TVs on/off.
 * @description Renders the dark neon remote UI bound to CastManager, PhoneReceiver, and AndroidTVRemoteManager, including per-device pin toggles, TV power controls, and a modal PIN pairing sheet.
 * @testing Verified via SwiftUI state-binding review, dark-mode contrast checks, and iOS 16 API compatibility audit.
 */

import SwiftUI

extension Color {
    init(hex: String) {
        let h = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        var rgb: UInt64 = 0
        Scanner(string: h).scanHexInt64(&rgb)
        self.init(
            .sRGB,
            red: Double((rgb >> 16) & 0xFF) / 255,
            green: Double((rgb >> 8) & 0xFF) / 255,
            blue: Double(rgb & 0xFF) / 255,
            opacity: 1
        )
    }
}

private let violet = Color(hex: "7C3AED")
private let cyan = Color(hex: "06B6D4")
private let magenta = Color(hex: "EC4899")
private let amber = Color(hex: "F59E0B")
private let emerald = Color(hex: "10B981")
private let crimson = Color(hex: "EF4444")
private let bg = Color(hex: "0B0B14")
private let card = Color(hex: "17172A")
private let textColor = Color(hex: "E5E7EB")
private let darkBtn = Color(hex: "2A2A3A")
private let muted = Color(hex: "9CA3AF")

private func gradientButton(_ colors: [Color], _ label: String, action: @escaping () -> Void) -> some View {
    Button(action: action) {
        Text(label)
            .fontWeight(.bold)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing))
            .cornerRadius(10)
    }
}

struct ContentView: View {
    @StateObject private var manager = CastManager()
    @StateObject private var receiver = PhoneReceiver.shared
    @State private var link = ""

    var body: some View {
        ContentBodyView(manager: manager, atvRemote: manager.atvRemote, receiver: receiver, link: $link)
    }
}

private struct ContentBodyView: View {
    @ObservedObject var manager: CastManager
    @ObservedObject var atvRemote: AndroidTVRemoteManager
    @ObservedObject var receiver: PhoneReceiver
    @Binding var link: String
    @State private var pairingPIN = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("CAST REMOTE")
                    .font(.system(size: 26, weight: .bold))
                    .tracking(0.15 * 26 / 10)
                    .foregroundStyle(LinearGradient(colors: [violet, cyan, magenta, amber], startPoint: .leading, endPoint: .trailing))

                gradientButton([violet, cyan, magenta, amber], "Mirror what's playing") {
                    Task { await manager.mirror(target: nil) }
                }
                .disabled(manager.isBusy)

                Text(manager.status)
                    .font(.system(size: 14))
                    .foregroundColor(muted)

                TextField("or paste a YouTube link", text: $link)
                    .padding(12)
                    .background(card)
                    .foregroundColor(textColor)
                    .cornerRadius(8)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                HStack(spacing: 12) {
                    gradientButton([violet, cyan], "Refresh") {
                        manager.refresh()
                    }
                    gradientButton([magenta, amber], "Stop all") {
                        Task { await manager.stopAll() }
                    }
                    .disabled(manager.isBusy)
                }

                Button(action: {
                    if receiver.isReceiving {
                        receiver.stopReceiving()
                    } else {
                        receiver.startReceiving()
                    }
                }) {
                    Text(receiver.isReceiving ? "Stop receiving" : "Receive on this phone")
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(darkBtn)
                        .cornerRadius(10)
                }
                if receiver.isReceiving {
                    Text("Receiving as \(receiver.receiveName) - keep this app open.")
                        .font(.system(size: 13))
                        .foregroundColor(muted)
                }
                if let incoming = receiver.lastIncomingVideoId {
                    Text("Incoming cast: \(incoming)")
                        .font(.system(size: 13))
                        .foregroundColor(cyan)
                }

                ForEach(manager.devices) { device in
                    deviceCard(device)
                }
            }
            .padding(16)
        }
        .background(bg)
        .sheet(isPresented: Binding(
            get: { atvRemote.pairingTarget != nil },
            set: { if !$0 { atvRemote.cancelPairing(); pairingPIN = "" } }
        )) {
            pairingSheet
        }
    }

    private func deviceCard(_ device: CastDevice) -> some View {
        let isPaired = atvRemote.isPaired(device.id)
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 8) {
                if !device.isPhone, let powerOn = device.isPowerOn {
                    Circle()
                        .fill(powerOn ? emerald : muted)
                        .frame(width: 8, height: 8)
                }

                Text("\(device.name) - \(device.state)" + (device.title.isEmpty ? "" : " - \(device.title)"))
                    .font(.system(size: 15, weight: device.isPinned ? .semibold : .regular))
                    .foregroundColor(device.state == "PLAYING" ? cyan : device.isPhone ? magenta : textColor)
                    .lineLimit(2)

                Spacer(minLength: 4)

                Button(action: {
                    manager.togglePin(device)
                }) {
                    Image(systemName: device.isPinned ? "pin.fill" : "pin")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(device.isPinned ? amber : muted)
                        .padding(6)
                        .background(darkBtn)
                        .cornerRadius(8)
                }
                .accessibilityLabel(device.isPinned ? "Unpin \(device.name)" : "Pin \(device.name) to top")
            }

            HStack(spacing: 8) {
                gradientButton([cyan, violet], "Play here") {
                    playHere(device)
                }
                .disabled(manager.isBusy)

                gradientButton([magenta, violet], "Mirror here") {
                    Task { await manager.mirror(target: device) }
                }
                .disabled(manager.isBusy)

                if !device.isPhone {
                    Button(action: {
                        Task { await manager.stop(device) }
                    }) {
                        Text("Stop")
                            .fontWeight(.semibold)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(darkBtn)
                            .cornerRadius(10)
                    }
                    .disabled(manager.isBusy)
                }
            }

            if !device.isPhone {
                HStack(spacing: 8) {
                    Button(action: {
                        Task { await manager.powerOn(device) }
                    }) {
                        Label("Turn On", systemImage: "power")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(emerald.opacity(0.85))
                            .cornerRadius(8)
                    }
                    .disabled(manager.isBusy)

                    Button(action: {
                        Task { await manager.powerOff(device) }
                    }) {
                        Label("Turn Off", systemImage: "power")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(crimson.opacity(0.85))
                            .cornerRadius(8)
                    }
                    .disabled(manager.isBusy)

                    Button(action: {
                        Task { await manager.pairTV(device) }
                    }) {
                        Label(isPaired ? "Paired" : "Pair TV", systemImage: isPaired ? "checkmark.shield.fill" : "link")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(isPaired ? emerald : textColor)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 10)
                            .background(darkBtn)
                            .cornerRadius(8)
                    }
                    .disabled(manager.isBusy)
                }
            }
        }
        .padding(12)
        .background(card)
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(device.isPinned ? amber.opacity(0.6) : Color.clear, lineWidth: 1.5)
        )
        .cornerRadius(10)
    }

    private var pairingSheet: some View {
        VStack(spacing: 16) {
            Text("Pair with \(atvRemote.pairingTarget?.name ?? "TV")")
                .font(.title3.bold())
                .foregroundColor(textColor)

            Text("Enter the 6-character code shown on your TV screen to enable real Power On & Power Off.")
                .font(.subheadline)
                .foregroundColor(muted)
                .multilineTextAlignment(.center)

            TextField("e.g. A1B2C3", text: $pairingPIN)
                .font(.system(size: 22, weight: .bold, design: .monospaced))
                .multilineTextAlignment(.center)
                .padding(14)
                .background(card)
                .foregroundColor(textColor)
                .cornerRadius(10)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()

            if let err = atvRemote.pairingError {
                Text(err)
                    .font(.footnote)
                    .foregroundColor(crimson)
                    .multilineTextAlignment(.center)
            }

            HStack(spacing: 12) {
                Button("Cancel") {
                    atvRemote.cancelPairing()
                    pairingPIN = ""
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(darkBtn)
                .cornerRadius(10)

                gradientButton([violet, cyan], "Confirm Pair") {
                    let pin = pairingPIN
                    Task {
                        await manager.submitPairingPIN(pin)
                        if atvRemote.pairingTarget == nil {
                            pairingPIN = ""
                        }
                    }
                }
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(bg)
        .presentationDetents([.medium])
    }

    private func playHere(_ device: CastDevice) {
        let parsed = YouTubeLink.parse(link)
        guard let videoId = parsed.videoId else {
            manager.status = "No video id in link."
            return
        }
        Task { await manager.playOn(device, videoId: videoId, start: parsed.start) }
    }
}
