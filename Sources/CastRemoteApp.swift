import SwiftUI
import GoogleCast

@main
struct CastRemoteApp: App {
    init() {
        // Discover and launch the YouTube receiver app on Chromecasts.
        let criteria = GCKDiscoveryCriteria(applicationID: CastManager.youTubeAppId)
        GCKCastContext.setSharedInstanceWith(GCKCastOptions(discoveryCriteria: criteria))
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
