import AVFoundation
import Capacitor
import CoreAudio
import Foundation
import MediaPlayer

enum MyError: Error {
    case runtimeError(String)
}

/// Please read the Capacitor iOS Plugin Development Guide
/// here: https://capacitor.ionicframework.com/docs/plugins/ios
// swiftlint:disable type_body_length file_length
@objc(NativeAudio)
public class NativeAudio: CAPPlugin, AVAudioPlayerDelegate, CAPBridgedPlugin {
    private let pluginVersion: String = "7.9.7"
    public let identifier = "NativeAudio"
    public let jsName = "NativeAudio"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "configure", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "preload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isPreloaded", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "play", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pause", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "loop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "unload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setVolume", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setRate", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isPlaying", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCurrentTime", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDuration", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resume", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setCurrentTime", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearCache", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPluginVersion", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deinitPlugin", returnType: CAPPluginReturnPromise)
    ]
    internal let audioQueue = DispatchQueue(label: "ee.forgr.audio.queue", qos: .userInitiated, attributes: .concurrent)
    private var audioList: [String: Any] = [:] {
        didSet {
            // Ensure audioList modifications happen on audioQueue
            assert(DispatchQueue.getSpecific(key: queueKey) != nil)
        }
    }
    private let queueKey = DispatchSpecificKey<Bool>()
    var fadeMusic = false
    var session = AVAudioSession.sharedInstance()

    // Track if audio session has been initialized
    private var audioSessionInitialized = false
    // Store the original audio category to restore on deinit
    private var originalAudioCategory: AVAudioSession.Category?
    private var originalAudioOptions: AVAudioSession.CategoryOptions?

    // Add observer for audio session interruptions
    private var interruptionObserver: Any?

    // Notification center support
    private var showNotification = false
    private var notificationMetadataMap: [String: [String: String]] = [:]
    private var currentlyPlayingAssetId: String?

    @objc override public func load() {
        super.load()
        audioQueue.setSpecific(key: queueKey, value: true)

        self.fadeMusic = false

        // Don't setup audio session on load - defer until first use
        // setupAudioSession()
        setupInterruptionHandling()
        setupRemoteCommandCenter()

        NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            guard let strongSelf = self else { return }

            // When entering background, automatically deactivate audio session if not playing any audio
            strongSelf.audioQueue.sync {
                // Check if there are any playing assets
                let hasPlayingAssets = strongSelf.audioList.values.contains { asset in
                    if let audioAsset = asset as? AudioAsset {
                        return audioAsset.isPlaying()
                    }
                    return false
                }

                // Only deactivate if we have no playing assets AND no other audio is active
                // This prevents interfering with VoIP calls or other audio sessions
                if !hasPlayingAssets && !strongSelf.session.isOtherAudioPlaying && strongSelf.session.secondaryAudioShouldBeSilencedHint == false {
                    strongSelf.endSession()
                }
            }
        }
    }

    // Clean up on deinit
    deinit {
        if let observer = interruptionObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private func setupAudioSession() {
        // Save the original audio session category before making changes
        if !audioSessionInitialized {
            originalAudioCategory = session.category
            originalAudioOptions = session.categoryOptions
            audioSessionInitialized = true
        }

        do {
            // Only set the category without immediately activating/deactivating
            try self.session.setCategory(AVAudioSession.Category.playback, options: .mixWithOthers)
            // Don't activate/deactivate in setup - we'll do this explicitly when needed
        } catch {
            print("Failed to setup audio session: \(error)")
        }
    }

    private func setupInterruptionHandling() {
        // Handle audio session interruptions
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: nil) { [weak self] notification in
            guard let strongSelf = self else { return }

            guard let userInfo = notification.userInfo,
                  let typeInt = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeInt) else {
                return
            }

            switch type {
            case .began:
                // Audio was interrupted - we could pause all playing audio here
                strongSelf.notifyListeners("interrupt", data: ["interrupted": true])
            case .ended:
                // Interruption ended - we could resume audio here if appropriate
                if let optionsInt = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt,
                   AVAudioSession.InterruptionOptions(rawValue: optionsInt).contains(.shouldResume) {
                    // Resume playback if appropriate (user wants to resume)
                    strongSelf.notifyListeners("interrupt", data: ["interrupted": false, "shouldResume": true])
                } else {
                    strongSelf.notifyListeners("interrupt", data: ["interrupted": false, "shouldResume": false])
                }
            @unknown default:
                break
            }
        }
    }

    // swiftlint:disable function_body_length
    private func setupRemoteCommandCenter() {
        let commandCenter = MPRemoteCommandCenter.shared()

        // Play command
        commandCenter.playCommand.addTarget { [weak self] _ in
            guard let self = self, let assetId = self.currentlyPlayingAssetId else {
                return .noSuchContent
            }

            self.audioQueue.sync {
                guard let asset = self.audioList[assetId] as? AudioAsset else {
                    return
                }

                if !asset.isPlaying() {
                    asset.resume()
                    self.updatePlaybackState(isPlaying: true)
                }
            }
            return .success
        }

        // Pause command
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            guard let self = self, let assetId = self.currentlyPlayingAssetId else {
                return .noSuchContent
            }

            self.audioQueue.sync {
                guard let asset = self.audioList[assetId] as? AudioAsset else {
                    return
                }

                asset.pause()
                self.updatePlaybackState(isPlaying: false)
            }
            return .success
        }

        // Stop command
        commandCenter.stopCommand.addTarget { [weak self] _ in
            guard let self = self, let assetId = self.currentlyPlayingAssetId else {
                return .noSuchContent
            }

            self.audioQueue.sync {
                guard let asset = self.audioList[assetId] as? AudioAsset else {
                    return
                }

                asset.stop()
                self.clearNowPlayingInfo()
                self.currentlyPlayingAssetId = nil
                self.updatePlaybackState(isPlaying: false)
            }
            return .success
        }

        // Toggle play/pause command
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self = self, let assetId = self.currentlyPlayingAssetId else {
                return .noSuchContent
            }

            self.audioQueue.sync {
                guard let asset = self.audioList[assetId] as? AudioAsset else {
                    return
                }

                if asset.isPlaying() {
                    asset.pause()
                    self.updatePlaybackState(isPlaying: false)
                } else {
                    asset.resume()
                    self.updatePlaybackState(isPlaying: true)
                }
            }
            return .success
        }
    }
    // swiftlint:enable function_body_length

    @objc func configure(_ call: CAPPluginCall) {
        // Save original category on first configure call
        if !audioSessionInitialized {
            originalAudioCategory = session.category
            originalAudioOptions = session.categoryOptions
            audioSessionInitialized = true
        }

        if let fade = call.getBool(Constant.FadeKey) {
            self.fadeMusic = fade
        }

        let focus = call.getBool(Constant.FocusAudio) ?? false
        let background = call.getBool(Constant.Background) ?? false
        let ignoreSilent = call.getBool(Constant.IgnoreSilent) ?? true
        self.showNotification = call.getBool(Constant.ShowNotification) ?? false

        // Use a single audio session configuration block for better atomicity
        do {
            // Set category first
            // Fix for issue #202: When showNotification is enabled, use .playback without
            // .mixWithOthers or .duckOthers to allow Now Playing info to display in
            // Control Center and lock screen.
            //
            // IMPORTANT: This is a behavior trade-off:
            // - With .playback + .default mode: Now Playing info shows, but interrupts other audio
            // - With .mixWithOthers or .duckOthers: Audio mixes, but no Now Playing info
            //
            // This is required because iOS only shows Now Playing controls for audio sessions
            // that use the .playback category without mixing options. This means the app becomes
            // the primary audio source and will interrupt background music from other apps.
            if self.showNotification {
                // Use playback category with default mode for notification support
                try self.session.setCategory(AVAudioSession.Category.playback, mode: .default)
            } else if focus {
                try self.session.setCategory(AVAudioSession.Category.playback, options: .duckOthers)
            } else if !ignoreSilent {
                try self.session.setCategory(AVAudioSession.Category.ambient, options: focus ? .duckOthers : .mixWithOthers)
            } else {
                try self.session.setCategory(AVAudioSession.Category.playback, options: .mixWithOthers)
            }

            // Only activate if needed (background mode)
            if background {
                try self.session.setActive(true)
            }

        } catch {
            print("Failed to configure audio session: \(error)")
        }

        call.resolve()
    }

    @objc func isPreloaded(_ call: CAPPluginCall) {
        guard let assetId = call.getString(Constant.AssetIdKey) else {
            call.reject("Missing assetId")
            return
        }

        audioQueue.sync {
            call.resolve([
                "found": self.audioList[assetId] != nil
            ])
        }
    }

    @objc func preload(_ call: CAPPluginCall) {
        preloadAsset(call, isComplex: true)
    }

    func activateSession() {
        do {
            // Only activate if not already active
            if !session.isOtherAudioPlaying {
                try self.session.setActive(true)
            }
        } catch {
            print("Failed to set session active: \(error)")
        }
    }

    func endSession() {
        do {
            // Check if any audio assets are still playing before deactivating
            let hasPlayingAssets = audioQueue.sync {
                return self.audioList.values.contains { asset in
                    if let audioAsset = asset as? AudioAsset {
                        return audioAsset.isPlaying()
                    }
                    return false
                }
            }

            // Only deactivate if no assets are playing AND no other audio is active
            // This prevents interfering with VoIP calls or other audio sessions
            if !hasPlayingAssets && !session.isOtherAudioPlaying && session.secondaryAudioShouldBeSilencedHint == false {
                try self.session.setActive(false, options: .notifyOthersOnDeactivation)
            }
        } catch {
            print("Failed to deactivate audio session: \(error)")
        }
    }

    public func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        // Don't immediately end the session here, as other players might still be active
        // Instead, check if all players are done
        audioQueue.async { [weak self] in
            guard let self = self else { return }

            // Avoid recursive calls by checking if the asset is still in the list
            let hasPlayingAssets = self.audioList.values.contains { asset in
                if let audioAsset = asset as? AudioAsset {
                    // Check if the asset has any playing channels other than the one that just finished
                    return audioAsset.channels.contains { $0 != player && $0.isPlaying }
                }
                return false
            }

            // Only end the session if no more assets are playing
            if !hasPlayingAssets {
                self.endSession()
            }
        }
    }

    @objc func play(_ call: CAPPluginCall) {
        let audioId = call.getString(Constant.AssetIdKey) ?? ""
        let time = max(call.getDouble("time") ?? 0, 0) // Ensure non-negative time
        let delay = max(call.getDouble("delay") ?? 0, 0) // Ensure non-negative delay

        // Ensure audio session is initialized before first play
        if !audioSessionInitialized {
            setupAudioSession()
        }

        // Use sync for operations that need to be blocking
        audioQueue.sync {
            guard !audioList.isEmpty else {
                call.reject("Audio list is empty")
                return
            }

            guard let asset = audioList[audioId] else {
                call.reject(Constant.ErrorAssetNotFound)
                return
            }

            if let audioAsset = asset as? AudioAsset {
                self.activateSession()
                if self.fadeMusic {
                    audioAsset.playWithFade(time: time)
                } else {
                    audioAsset.play(time: time, delay: delay)
                }

                // Update notification center if enabled
                if self.showNotification {
                    self.currentlyPlayingAssetId = audioId
                    self.updateNowPlayingInfo(audioId: audioId, audioAsset: audioAsset)
                    self.updatePlaybackState(isPlaying: true)
                }

                call.resolve()
            } else if let audioNumber = asset as? NSNumber {
                self.activateSession()
                AudioServicesPlaySystemSound(SystemSoundID(audioNumber.intValue))
                call.resolve()
            } else {
                call.reject(Constant.ErrorAssetNotFound)
            }
        }
    }

    @objc private func getAudioAsset(_ call: CAPPluginCall) -> AudioAsset? {
        var asset: AudioAsset?
        audioQueue.sync { // Read operations should use sync
            asset = self.audioList[call.getString(Constant.AssetIdKey) ?? ""] as? AudioAsset
        }
        return asset
    }

    @objc func setCurrentTime(_ call: CAPPluginCall) {
        // Consistent use of audioQueue.sync for all operations
        audioQueue.sync {
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }

            let time = max(call.getDouble("time") ?? 0, 0) // Ensure non-negative time
            audioAsset.setCurrentTime(time: time)
            call.resolve()
        }
    }

    @objc func getDuration(_ call: CAPPluginCall) {
        audioQueue.sync {
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }

            call.resolve([
                "duration": audioAsset.getDuration()
            ])
        }
    }

    @objc func getCurrentTime(_ call: CAPPluginCall) {
        audioQueue.sync {
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }

            call.resolve([
                "currentTime": audioAsset.getCurrentTime()
            ])
        }
    }

    @objc func resume(_ call: CAPPluginCall) {
        audioQueue.sync {
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }
            self.activateSession()
            audioAsset.resume()

            // Update notification when resumed
            if self.showNotification {
                self.updatePlaybackState(isPlaying: true)
            }

            call.resolve()
        }
    }

    @objc func pause(_ call: CAPPluginCall) {
        audioQueue.sync {
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }

            audioAsset.pause()

            // Update notification when paused
            if self.showNotification {
                self.updatePlaybackState(isPlaying: false)
            }

            self.endSession()
            call.resolve()
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        let audioId = call.getString(Constant.AssetIdKey) ?? ""

        audioQueue.sync {
            guard !self.audioList.isEmpty else {
                call.reject("Audio list is empty")
                return
            }

            do {
                try self.stopAudio(audioId: audioId)

                // Clear notification when stopped
                if self.showNotification {
                    self.clearNowPlayingInfo()
                    self.currentlyPlayingAssetId = nil
                }

                self.endSession()
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func loop(_ call: CAPPluginCall) {
        audioQueue.sync {
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }

            audioAsset.loop()
            call.resolve()
        }
    }

    @objc func unload(_ call: CAPPluginCall) {
        let audioId = call.getString(Constant.AssetIdKey) ?? ""

        audioQueue.sync(flags: .barrier) { // Use barrier for writing operations
            guard !self.audioList.isEmpty else {
                call.reject("Audio list is empty")
                return
            }

            if let asset = self.audioList[audioId] as? AudioAsset {
                asset.unload()
                self.audioList[audioId] = nil
                call.resolve()
            } else if let audioNumber = self.audioList[audioId] as? NSNumber {
                // Also handle unloading system sounds
                AudioServicesDisposeSystemSoundID(SystemSoundID(audioNumber.intValue))
                self.audioList[audioId] = nil
                call.resolve()
            } else {
                call.reject("Cannot cast to AudioAsset")
            }
        }
    }

    @objc func setVolume(_ call: CAPPluginCall) {
        audioQueue.sync {
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }

            let volume = min(max(call.getFloat(Constant.Volume) ?? Constant.DefaultVolume, Constant.MinVolume), Constant.MaxVolume)
            audioAsset.setVolume(volume: volume as NSNumber)
            call.resolve()
        }
    }

    @objc func setRate(_ call: CAPPluginCall) {
        audioQueue.sync {
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }

            let rate = min(max(call.getFloat(Constant.Rate) ?? Constant.DefaultRate, Constant.MinRate), Constant.MaxRate)
            audioAsset.setRate(rate: rate as NSNumber)
            call.resolve()
        }
    }

    @objc func isPlaying(_ call: CAPPluginCall) {
        audioQueue.sync {
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }

            call.resolve([
                "isPlaying": audioAsset.isPlaying()
            ])
        }
    }

    @objc func clearCache(_ call: CAPPluginCall) {
        DispatchQueue.global(qos: .background).async {
            RemoteAudioAsset.clearCache()
            call.resolve()
        }
    }

    // swiftlint:disable cyclomatic_complexity function_body_length
    @objc private func preloadAsset(_ call: CAPPluginCall, isComplex complex: Bool) {
        // Common default values to ensure consistency
        let audioId = call.getString(Constant.AssetIdKey) ?? ""
        let channels: Int?
        let volume: Float?
        let delay: Float?
        var isLocalUrl: Bool = call.getBool("isUrl") ?? false

        if audioId == "" {
            call.reject(Constant.ErrorAssetId)
            return
        }
        var assetPath: String = call.getString(Constant.AssetPathKey) ?? ""

        if assetPath == "" {
            call.reject(Constant.ErrorAssetPath)
            return
        }

        // Store notification metadata if provided
        if let metadata = call.getObject(Constant.NotificationMetadata) {
            var metadataDict: [String: String] = [:]
            if let title = metadata["title"] as? String {
                metadataDict["title"] = title
            }
            if let artist = metadata["artist"] as? String {
                metadataDict["artist"] = artist
            }
            if let album = metadata["album"] as? String {
                metadataDict["album"] = album
            }
            if let artworkUrl = metadata["artworkUrl"] as? String {
                metadataDict["artworkUrl"] = artworkUrl
            }
            if !metadataDict.isEmpty {
                notificationMetadataMap[audioId] = metadataDict
            }
        }

        if complex {
            volume = min(max(call.getFloat("volume") ?? Constant.DefaultVolume, Constant.MinVolume), Constant.MaxVolume)
            channels = max(call.getInt("channels") ?? Constant.DefaultChannels, 1)
            delay = max(call.getFloat("delay") ?? Constant.DefaultFadeDelay, 0.0)
        } else {
            channels = Constant.DefaultChannels
            volume = Constant.DefaultVolume
            delay = Constant.DefaultFadeDelay
            isLocalUrl = false
        }

        audioQueue.sync(flags: .barrier) { [self] in
            if audioList.isEmpty {
                audioList = [:]
            }

            if audioList[audioId] != nil {
                call.reject(Constant.ErrorAssetAlreadyLoaded + " - " + audioId)
                return
            }

            var basePath: String?
            if let url = URL(string: assetPath), url.scheme != nil {
                // Check if it's a local file URL or a remote URL
                if url.isFileURL {
                    // Handle local file URL
                    let fileURL = url
                    basePath = fileURL.path

                    if let basePath = basePath, FileManager.default.fileExists(atPath: basePath) {
                        let audioAsset = AudioAsset(
                            owner: self,
                            withAssetId: audioId, withPath: basePath, withChannels: channels,
                            withVolume: volume, withFadeDelay: delay)
                        self.audioList[audioId] = audioAsset
                        call.resolve()
                        return
                    }
                } else {
                    // Handle remote URL
                    let remoteAudioAsset = RemoteAudioAsset(owner: self, withAssetId: audioId, withPath: assetPath, withChannels: channels, withVolume: volume, withFadeDelay: delay)
                    self.audioList[audioId] = remoteAudioAsset
                    call.resolve()
                    return
                }
            } else if isLocalUrl == false {
                // Handle public folder
                assetPath = assetPath.starts(with: "public/") ? assetPath : "public/" + assetPath
                let assetPathSplit = assetPath.components(separatedBy: ".")
                if assetPathSplit.count >= 2 {
                    basePath = Bundle.main.path(forResource: assetPathSplit[0], ofType: assetPathSplit[1])
                } else {
                    call.reject("Invalid asset path format: \(assetPath)")
                    return
                }
            } else {
                // Handle local file URL
                let fileURL = URL(fileURLWithPath: assetPath)
                basePath = fileURL.path
            }

            if let basePath = basePath, FileManager.default.fileExists(atPath: basePath) {
                if !complex {
                    let soundFileUrl = URL(fileURLWithPath: basePath)
                    var soundId = SystemSoundID()
                    let result = AudioServicesCreateSystemSoundID(soundFileUrl as CFURL, &soundId)
                    if result == kAudioServicesNoError {
                        self.audioList[audioId] = NSNumber(value: Int32(soundId))
                    } else {
                        call.reject("Failed to create system sound: \(result)")
                        return
                    }
                } else {
                    let audioAsset = AudioAsset(
                        owner: self,
                        withAssetId: audioId, withPath: basePath, withChannels: channels,
                        withVolume: volume, withFadeDelay: delay)
                    self.audioList[audioId] = audioAsset
                }
            } else {
                if !FileManager.default.fileExists(atPath: assetPath) {
                    call.reject(Constant.ErrorAssetPath + " - " + assetPath)
                    return
                }
                // Use the original assetPath
                if !complex {
                    let soundFileUrl = URL(fileURLWithPath: assetPath)
                    var soundId = SystemSoundID()
                    let result = AudioServicesCreateSystemSoundID(soundFileUrl as CFURL, &soundId)
                    if result == kAudioServicesNoError {
                        self.audioList[audioId] = NSNumber(value: Int32(soundId))
                    } else {
                        call.reject("Failed to create system sound: \(result)")
                        return
                    }
                } else {
                    let audioAsset = AudioAsset(
                        owner: self,
                        withAssetId: audioId, withPath: assetPath, withChannels: channels,
                        withVolume: volume, withFadeDelay: delay)
                    self.audioList[audioId] = audioAsset
                }
            }
            call.resolve()
        }
    }
    // swiftlint:enable cyclomatic_complexity function_body_length

    private func stopAudio(audioId: String) throws {
        var asset: AudioAsset?

        audioQueue.sync {
            asset = self.audioList[audioId] as? AudioAsset
        }

        guard let audioAsset = asset else {
            throw MyError.runtimeError(Constant.ErrorAssetNotFound)
        }

        if self.fadeMusic {
            audioAsset.stopWithFade()
        } else {
            audioAsset.stop()
        }
    }

    internal func executeOnAudioQueue(_ block: @escaping () -> Void) {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            block()  // Already on queue
        } else {
            audioQueue.sync(flags: .barrier) {
                block()
            }
        }
    }

    @objc func notifyCurrentTime(_ asset: AudioAsset) {
        audioQueue.sync {
            let rawTime = asset.getCurrentTime()
            // Round to nearest 100ms (0.1 seconds)
            let currentTime = round(rawTime * 10) / 10
            notifyListeners("currentTime", data: [
                "currentTime": currentTime,
                "assetId": asset.assetId
            ])
        }
    }

    @objc func getPluginVersion(_ call: CAPPluginCall) {
        call.resolve(["version": self.pluginVersion])
    }

    @objc func deinitPlugin(_ call: CAPPluginCall) {
        // Stop all playing audio
        audioQueue.sync(flags: .barrier) {
            for (_, asset) in self.audioList {
                if let audioAsset = asset as? AudioAsset {
                    audioAsset.stop()
                }
            }
        }

        // Clear notification center
        clearNowPlayingInfo()

        // Restore original audio session settings if we changed them
        if audioSessionInitialized, let originalCategory = originalAudioCategory {
            do {
                // Deactivate our audio session
                try self.session.setActive(false, options: .notifyOthersOnDeactivation)

                // Restore original category and options
                if let originalOptions = originalAudioOptions {
                    try self.session.setCategory(originalCategory, options: originalOptions)
                } else {
                    try self.session.setCategory(originalCategory)
                }

                audioSessionInitialized = false
            } catch {
                print("Failed to restore audio session: \(error)")
            }
        }

        call.resolve()
    }

    // MARK: - Now Playing Info Methods

    private func updateNowPlayingInfo(audioId: String, audioAsset: AudioAsset) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            var nowPlayingInfo = [String: Any]()

            // Get metadata from the map
            if let metadata = self.notificationMetadataMap[audioId] {
                if let title = metadata["title"] {
                    nowPlayingInfo[MPMediaItemPropertyTitle] = title
                }
                if let artist = metadata["artist"] {
                    nowPlayingInfo[MPMediaItemPropertyArtist] = artist
                }
                if let album = metadata["album"] {
                    nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = album
                }

                // Load artwork if provided
                if let artworkUrl = metadata["artworkUrl"] {
                    self.loadArtwork(from: artworkUrl) { image in
                        if let image = image {
                            nowPlayingInfo[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in
                                return image
                            }
                            MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
                        }
                    }
                }
            }

            // Add playback info
            nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = audioAsset.getDuration()
            nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = audioAsset.getCurrentTime()
            nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = 1.0

            MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
        }
    }

    private func clearNowPlayingInfo() {
        DispatchQueue.main.async {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        }
    }

    private func updatePlaybackState(isPlaying: Bool) {
        DispatchQueue.main.async {
            var nowPlayingInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [String: Any]()
            nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
        }
    }

    private func loadArtwork(from urlString: String, completion: @escaping (UIImage?) -> Void) {
        // Check if it's a local file path or URL
        if let url = URL(string: urlString) {
            if url.scheme == nil || url.isFileURL {
                // Local file
                let path = url.path
                if FileManager.default.fileExists(atPath: path) {
                    if let image = UIImage(contentsOfFile: path) {
                        completion(image)
                        return
                    }
                }
            } else {
                // Remote URL
                URLSession.shared.dataTask(with: url) { data, _, _ in
                    if let data = data, let image = UIImage(data: data) {
                        completion(image)
                    } else {
                        completion(nil)
                    }
                }.resume()
                return
            }
        }
        completion(nil)
    }

}
