import Foundation
import MediaPlayer

/// Makes CarANC look like a playing media source on CarPlay (main audio / Now Playing).
/// Pause is ignored — same as Android AncMediaSession. Stop quits ANC.
enum AncNowPlaying {
    private static var commandsInstalled = false

    static func installRemoteCommandsIfNeeded() {
        guard !commandsInstalled else { return }
        commandsInstalled = true
        let cc = MPRemoteCommandCenter.shared()
        cc.playCommand.isEnabled = true
        cc.pauseCommand.isEnabled = true
        cc.stopCommand.isEnabled = true
        cc.playCommand.addTarget { _ in .success }
        cc.pauseCommand.addTarget { _ in
            // CarPlay / lock-screen Pause must not kill ANC.
            .success
        }
        cc.stopCommand.addTarget { _ in
            Task { @MainActor in
                AppController.shared.stopAnc()
            }
            return .success
        }
    }

    static func setPlaying(_ playing: Bool) {
        installRemoteCommandsIfNeeded()
        let center = MPNowPlayingInfoCenter.default()
        if playing {
            var info = [String: Any]()
            info[MPMediaItemPropertyTitle] = "CarANC"
            info[MPMediaItemPropertyArtist] = "Active noise cancellation"
            info[MPMediaItemPropertyAlbumTitle] = "Cabin"
            info[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
            info[MPNowPlayingInfoPropertyIsLiveStream] = true
            center.nowPlayingInfo = info
            if #available(iOS 13.0, *) {
                center.playbackState = .playing
            }
        } else {
            if #available(iOS 13.0, *) {
                center.playbackState = .stopped
            }
            center.nowPlayingInfo = nil
        }
    }
}
