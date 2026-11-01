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
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("CAST REMOTE")
                    .font(.system(size: 26, weight: .bold))
                    .tracking(0.15 * 26 / 10)
                    .foregroundStyle(LinearGradient(colors: [violet, cyan, magenta, amber], startPoint: .leading, endPoint: .trailing))

                gradientButton([violet, cyan, magenta, amber], "Mirror what's playing") {
                    Task { await manager.mirror(target: nil) }
                }

                Text(manager.status)
                    .font(.system(size: 14))
                    .foregroundColor(muted)

                TextField("or paste a YouTube link", text: $link)
                    .padding(12)
                    .background(card)
                    .foregroundColor(textColor)
                    .cornerRadius(8)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)

                HStack(spacing: 12) {
                    gradientButton([violet, cyan], "Refresh") {
                        manager.refresh()
                    }
                    gradientButton([magenta, amber], "Stop all") {
                        Task { await manager.stopAll() }
                    }
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
                    VStack(alignment: .leading, spacing: 10) {
                        Text("\(device.name) - \(device.state)" + (device.title.isEmpty ? "" : " - \(device.title)"))
                            .font(.system(size: 15))
                            .foregroundColor(device.state == "PLAYING" ? cyan : device.isPhone ? magenta : textColor)
                        HStack(spacing: 8) {
                            gradientButton([cyan, violet], "Play here") {
                                playHere(device)
                            }
                            gradientButton([magenta, violet], "Mirror here") {
                                Task { await manager.mirror(target: device) }
                            }
                            if !device.isPhone {
                                Button(action: {
                                    Task { await manager.stop(device) }
                                }) {
                                    Text("Stop")
                                        .foregroundColor(.white)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 12)
                                        .background(darkBtn)
                                        .cornerRadius(10)
                                }
                            }
                        }
                    }
                    .padding(12)
                    .background(card)
                    .cornerRadius(10)
                }
            }
            .padding(16)
        }
        .background(bg)
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
