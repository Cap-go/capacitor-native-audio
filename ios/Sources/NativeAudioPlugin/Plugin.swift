import AVFoundation
import Capacitor
import CoreAudio
import Foundation
import MediaPlayer

enum MyError: Error {
    case runtimeError(String)
}

// swiftlint:disable file_length
@objc(NativeAudio)
// swiftlint:disable:next type_body_length
public class NativeAudio: CAPPlugin, AVAudioPlayerDelegate, CAPBridgedPlugin {
    private let pluginVersion: String = "8.3.0"
    public let identifier = "NativeAudio"
    public let jsName = "NativeAudio"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "setDebugMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "configure", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "preload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "playOnce", returnType: CAPPluginReturnPromise),
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
    private var logger = Logger(logTag: "NativeAudio")
    internal let audioQueue = DispatchQueue(label: "ee.forgr.audio.queue", qos: .userInitiated, attributes: .concurrent)
    /// A dictionary that stores audio asset objects by their asset IDs.
    ///
    /// - Important: Must only be accessed within `audioQueue.sync` blocks.
    internal var audioList: [String: Any] = [:] {
        didSet {
            // Ensure audioList modifications happen on audioQueue
            assert(DispatchQueue.getSpecific(key: queueKey) != nil)
        }
    }
    private let queueKey = DispatchSpecificKey<Bool>()
    /// Set while executing a block on the audio queue so getAudioAsset/endSession can avoid reentrant sync (deadlock).
    private let audioQueueContextKey = DispatchSpecificKey<Bool?>()
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
    /// A mapping from asset IDs to their associated notification metadata for media playback.
    ///
    /// - Important: Must only be accessed within `audioQueue.sync` blocks.
    internal var notificationMetadataMap: [String: [String: String]] = [:]
    private var currentlyPlayingAssetId: String?

    /// Stores the asset IDs for playOnce operations to enable automatic cleanup after playback.
    ///
    /// - Important: Must only be accessed within `audioQueue.sync` blocks.
    internal var playOnceAssets: Set<String> = []

    private var pendingPlayTasks: [String: DispatchWorkItem] = [:]
    private var audioAssetData: [String: [String: Any]] = [:]
    var isRunningTests = false

    /// Initialize plugin state and audio-related handlers, and register background behavior for session management.
    ///
    /// Performs initial plugin setup after the plugin is loaded.
    ///
    /// Registers the plugin's audio queue, initializes default flags, defers full audio session activation until first use, and configures interruption handling and remote command controls. Also adds a background observer that will deactivate the audio session when the app enters background if no plugin-managed audio is playing and the system reports no other active audio.
    @objc override public func load() {
        super.load()
        audioQueue.setSpecific(key: queueKey, value: true)

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
                    self.updateNowPlayingInfo(audioId: assetId, audioAsset: asset)
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
                self.currentlyPlayingAssetId = nil
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
                    self.updateNowPlayingInfo(audioId: assetId, audioAsset: asset)
                }
            }
            return .success
        }
    }
    // swiftlint:enable function_body_length

    @objc func setDebugMode(_ call: CAPPluginCall) {
        let debug = call.getBool("enabled") ?? false
        Logger.debugModeEnabled = debug
        if debug {
            logger.info("Debug mode enabled")
        }
        call.resolve()
    }

    @objc func configure(_ call: CAPPluginCall) {
        // Save original category on first configure call
        if !audioSessionInitialized {
            originalAudioCategory = session.category
            originalAudioOptions = session.categoryOptions
            audioSessionInitialized = true
        }

        let focus = call.getBool(Constant.FocusAudio) ?? false
        let background = call.getBool(Constant.Background) ?? false
        let ignoreSilent = call.getBool(Constant.IgnoreSilent) ?? true
        // Only update showNotification when explicitly provided so repeated configure() calls
        // (e.g. when switching assets) don't reset it to false and break Now Playing for the next play
        if let showNotification = call.getBool(Constant.ShowNotification) {
            self.showNotification = showNotification
        }

        logger.info("Configuring audio session with focus=%@ background=%@ ignoreSilent=%@", "\(focus)", "\(background)", "\(ignoreSilent)")

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
            logger.error("Failed to configure audio session: %@", error.localizedDescription)
        }

        call.resolve()
    }

    /// Checks whether an audio asset with the given assetId is currently loaded.
    /// - Parameter call: A CAPPluginCall that must include the `"assetId"` string identifying the audio asset to check. The call is rejected with `"Missing assetId"` if the parameter is absent.
    /// - Returns: A dictionary with key `found` set to `true` if the asset is loaded, `false` otherwise.
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

    /// Preloads an audio asset into the plugin's audio cache for full-featured playback.
    ///
    /// The call should include the asset configuration (for example `assetId`, `assetPath`) and may include optional playback and metadata options such as `channels`, `volume`, `delay`, `isUrl`, `headers`, and notification metadata. The plugin will load the asset so it is ready for subsequent play, loop, stop and other playback operations.
    /// - Parameters:
    /// Preloads an audio asset with advanced playback options for later use.
    ///
    /// Prepares the asset specified in the plugin call (local file, bundled resource, or remote URL) using options such as `assetId`, `assetPath`, `isUrl`, `volume`, `channels`, `delay`, headers, and notification metadata so it is ready for playback.
    /// - Parameter call: The CAPPluginCall containing preload options and identifiers.
    @objc func preload(_ call: CAPPluginCall) {
        preloadAsset(call, isComplex: true)
    }

    // swiftlint:disable:next cyclomatic_complexity function_body_length
    @objc func playOnce(_ call: CAPPluginCall) {
        // Generate unique temporary asset ID
        let assetId = "playOnce_\(Int(Date().timeIntervalSince1970 * 1000))_\(UUID().uuidString.prefix(8))"

        // Extract options
        let assetPath = call.getString(Constant.AssetPathKey) ?? ""
        let autoPlay = call.getBool("autoPlay") ?? true
        let deleteAfterPlay = call.getBool("deleteAfterPlay") ?? false
        let volume = min(max(call.getFloat("volume") ?? Constant.DefaultVolume, Constant.MinVolume), Constant.MaxVolume)
        let isLocalUrl = call.getBool("isUrl") ?? false

        if assetPath == "" {
            call.reject(Constant.ErrorAssetPath)
            return
        }

        // Parse notification metadata if provided (on main thread)
        var metadataDict: [String: String]?
        if let metadata = call.getObject(Constant.NotificationMetadata) {
            var tempDict: [String: String] = [:]
            if let title = metadata["title"] as? String {
                tempDict["title"] = title
            }
            if let artist = metadata["artist"] as? String {
                tempDict["artist"] = artist
            }
            if let album = metadata["album"] as? String {
                tempDict["album"] = album
            }
            if let artworkUrl = metadata["artworkUrl"] as? String {
                tempDict["artworkUrl"] = artworkUrl
            }
            if !tempDict.isEmpty {
                metadataDict = tempDict
            }
        }

        // Ensure audio session is initialized
        if !audioSessionInitialized {
            setupAudioSession()
        }

        // Track this as a playOnce asset and store metadata (thread-safe)
        audioQueue.sync(flags: .barrier) {
            self.playOnceAssets.insert(assetId)
            if let metadata = metadataDict {
                self.notificationMetadataMap[assetId] = metadata
            }
        }

        // Create a completion handler for cleanup
        let cleanupHandler: () -> Void = { [weak self] in
            guard let self = self else { return }

            self.audioQueue.async(flags: .barrier) {
                guard let asset = self.audioList[assetId] as? AudioAsset else { return }

                // Get the file path before unloading if we need to delete
                // Only delete if it's a local file:// URL, not remote streaming URLs
                var filePathToDelete: String?
                if deleteAfterPlay {
                    if let url = asset.channels.first?.url, url.isFileURL {
                        filePathToDelete = url.path
                    }
                }

                // Unload the asset
                asset.unload()
                self.audioList[assetId] = nil
                self.playOnceAssets.remove(assetId)
                self.notificationMetadataMap.removeValue(forKey: assetId)

                // Reset current track if this was the currently playing asset (next play will overwrite Now Playing)
                if self.currentlyPlayingAssetId == assetId {
                    self.currentlyPlayingAssetId = nil
                }

                // Delete file if requested and it's a local file
                if let filePath = filePathToDelete {
                    let fileManager = FileManager.default
                    let resolvedPath: String
                    if filePath.hasPrefix("file://") {
                        resolvedPath = URL(string: filePath)?.path ?? filePath
                    } else {
                        resolvedPath = filePath
                    }

                    do {
                        if fileManager.fileExists(atPath: resolvedPath) {
                            try fileManager.removeItem(atPath: resolvedPath)
                            print("Deleted file after playOnce: \(resolvedPath)")
                        }
                    } catch {
                        print("Error deleting file after playOnce: \(error.localizedDescription)")
                    }
                }
            }
        }

        /// Cleans up tracking data when playOnce fails to prevent memory leaks.
        ///
        /// Removes the asset ID from both playOnceAssets set and notificationMetadataMap
        /// to ensure proper cleanup when an error occurs during playOnce execution.
        ///
        /// Removes transient tracking for a one-off playback asset and its associated notification metadata.
        /// Remove tracking and Now Playing metadata for a play-once asset after a failed load or playback.
        /// - Parameter assetId: The asset identifier to remove from play-once tracking and notification metadata.
        func cleanupOnFailure(assetId: String) {
            self.playOnceAssets.remove(assetId)
            self.notificationMetadataMap.removeValue(forKey: assetId)
        }

        // Inline preload logic directly (avoid creating mock PluginCall)
        audioQueue.async(flags: .barrier) { [weak self] in
            guard let self = self else { return }

            // Check if asset already exists
            if self.audioList[assetId] != nil {
                cleanupOnFailure(assetId: assetId)
                call.reject(Constant.ErrorAssetAlreadyLoaded + " - " + assetId)
                return
            }

            var basePath: String?

            if let url = URL(string: assetPath), url.scheme != nil {
                // Check if it's a local file URL or a remote URL
                if url.isFileURL {
                    // Handle local file URL
                    basePath = url.path

                    if let basePath = basePath, FileManager.default.fileExists(atPath: basePath) {
                        let audioAsset = AudioAsset(
                            owner: self,
                            withAssetId: assetId,
                            withPath: basePath,
                            withChannels: 1,
                            withVolume: volume
                        )
                        self.audioList[assetId] = audioAsset
                    } else {
                        cleanupOnFailure(assetId: assetId)
                        call.reject(Constant.ErrorAssetPath + " - " + assetPath)
                        return
                    }
                } else {
                    // Handle remote URL
                    var headers: [String: String]?
                    if let headersObj = call.getObject("headers") {
                        headers = [:]
                        for (key, value) in headersObj {
                            if let stringValue = value as? String {
                                headers?[key] = stringValue
                            }
                        }
                    }
                    let remoteAudioAsset = RemoteAudioAsset(
                        owner: self,
                        withAssetId: assetId,
                        withPath: assetPath,
                        withChannels: 1,
                        withVolume: volume,
                        withHeaders: headers
                    )
                    self.audioList[assetId] = remoteAudioAsset
                }
            } else if !isLocalUrl {
                // Handle public folder
                let publicAssetPath = assetPath.starts(with: "public/") ? assetPath : "public/" + assetPath
                let assetPathSplit = publicAssetPath.components(separatedBy: ".")
                if assetPathSplit.count >= 2 {
                    basePath = Bundle.main.path(forResource: assetPathSplit[0], ofType: assetPathSplit[1])
                } else {
                    cleanupOnFailure(assetId: assetId)
                    call.reject("Invalid asset path format: \(assetPath)")
                    return
                }

                if let basePath = basePath, FileManager.default.fileExists(atPath: basePath) {
                    let audioAsset = AudioAsset(
                        owner: self,
                        withAssetId: assetId,
                        withPath: basePath,
                        withChannels: 1,
                        withVolume: volume
                    )
                    self.audioList[assetId] = audioAsset
                } else {
                    cleanupOnFailure(assetId: assetId)
                    call.reject(Constant.ErrorAssetPath + " - " + assetPath)
                    return
                }
            } else {
                // Handle local file path
                let fileURL = URL(fileURLWithPath: assetPath)
                basePath = fileURL.path

                if let basePath = basePath, FileManager.default.fileExists(atPath: basePath) {
                    let audioAsset = AudioAsset(
                        owner: self,
                        withAssetId: assetId,
                        withPath: basePath,
                        withChannels: 1,
                        withVolume: volume
                    )
                    self.audioList[assetId] = audioAsset
                } else {
                    cleanupOnFailure(assetId: assetId)
                    call.reject(Constant.ErrorAssetPath + " - " + assetPath)
                    return
                }
            }

            // Get the loaded asset
            guard let asset = self.audioList[assetId] as? AudioAsset else {
                // Cleanup on failure
                cleanupOnFailure(assetId: assetId)
                call.reject("Failed to load asset for playOnce")
                return
            }

            // Set up completion handler
            asset.onComplete = { [weak self] in
                cleanupHandler()
            }

            // Auto-play if requested
            if autoPlay {
                self.activateSession()
                asset.play(time: 0, volume: nil)

                // Update notification center if enabled
                if self.showNotification {
                    self.currentlyPlayingAssetId = assetId
                    self.updateNowPlayingInfo(audioId: assetId, audioAsset: asset)
                    self.updatePlaybackState(isPlaying: true)
                }
            }

            // Return the generated assetId
            call.resolve(["assetId": assetId])
        }
    }

    /// Activates the app's audio session when no other audio is playing.
    /// Activate the shared AVAudioSession when no other audio is playing.
    ///
    /// If the system reports other audio is playing, the session is left inactive. On failure to activate, the error is printed to the console.
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
            // Avoid reentrant sync when already on audio queue (e.g. from pause()) to prevent deadlock
            let hasPlayingAssets: Bool
            if DispatchQueue.getSpecific(key: audioQueueContextKey) == true {
                hasPlayingAssets = self.audioList.values.contains { asset in
                    if let audioAsset = asset as? AudioAsset {
                        return audioAsset.isPlaying()
                    }
                    return false
                }
            } else {
                hasPlayingAssets = audioQueue.sync {
                    return self.audioList.values.contains { asset in
                        if let audioAsset = asset as? AudioAsset {
                            return audioAsset.isPlaying()
                        }
                        return false
                    }
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
        // Instead, check if all players are done and clear Now Playing if this asset was current
        audioQueue.async { [weak self] in
            guard let self = self else { return }

            // Find which asset this player belongs to; if it was the currently playing one, clear notification
            for (audioId, asset) in self.audioList {
                if let audioAsset = asset as? AudioAsset, audioAsset.channels.contains(player) {
                    if self.currentlyPlayingAssetId == audioId {
                        self.currentlyPlayingAssetId = nil
                        self.clearNowPlayingInfo()
                    }
                    break
                }
            }

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
                // If we didn't find the asset above (e.g. playOnce already removed it), clear notification when nothing is playing
                if self.currentlyPlayingAssetId != nil {
                    self.currentlyPlayingAssetId = nil
                    self.clearNowPlayingInfo()
                }
                self.endSession()
            }
        }
    }

    // swiftlint:disable:next function_body_length
    @objc func play(_ call: CAPPluginCall) {
        let audioId = call.getString(Constant.AssetIdKey) ?? ""
        let time = max(call.getDouble(Constant.Time) ?? 0, 0)
        let delay = max(call.getDouble(Constant.Delay) ?? 0, 0)
        let volume = call.getFloat(Constant.Volume)
        let fadeIn = call.getBool(Constant.FadeIn) ?? false
        let fadeOut = call.getBool(Constant.FadeOut) ?? false
        let fadeInDuration = call.getDouble(Constant.FadeInDuration) ?? Double(Constant.DefaultFadeDuration)
        let fadeOutDuration = call.getDouble(Constant.FadeOutDuration) ?? Double(Constant.DefaultFadeDuration)
        let fadeOutStartTime = call.getDouble(Constant.FadeOutStartTime) ?? 0.0

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
                cancelPendingPlay(for: audioId)
                clearAudioAssetData(for: audioId)

                let playBlock = { [weak self] in
                    guard let self else { return }
                    self.executeOnAudioQueue {
                        if fadeIn {
                            audioAsset.playWithFade(time: time, volume: volume, fadeInDuration: fadeInDuration)
                        } else {
                            audioAsset.play(time: time, volume: volume)
                        }
                        self.pendingPlayTasks[audioId] = nil

                        if fadeOut {
                            self.handleFadeOut(
                                for: audioAsset,
                                audioId: audioId,
                                fadeOutDuration: fadeOutDuration,
                                fadeOutStartTime: fadeOutStartTime
                            )
                        }

                        if self.showNotification {
                            self.currentlyPlayingAssetId = audioId
                            self.updateNowPlayingInfo(audioId: audioId, audioAsset: audioAsset)
                            self.updatePlaybackState(isPlaying: true)
                        }
                        call.resolve()
                    }
                }

                if delay > 0 {
                    let workItem = DispatchWorkItem(block: playBlock)
                    pendingPlayTasks[audioId] = workItem
                    DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: workItem)
                } else {
                    playBlock()
                }
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
        // Avoid reentrant sync when already on audio queue (e.g. from pause()) to prevent deadlock
        if DispatchQueue.getSpecific(key: audioQueueContextKey) == true {
            return self.audioList[call.getString(Constant.AssetIdKey) ?? ""] as? AudioAsset
        }
        var asset: AudioAsset?
        audioQueue.sync {
            asset = self.audioList[call.getString(Constant.AssetIdKey) ?? ""] as? AudioAsset
        }
        return asset
    }

    @objc func setCurrentTime(_ call: CAPPluginCall) {
        audioQueue.sync {
            self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: true)
            defer { self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: nil) }
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }

            cancelPendingPlay(for: audioAsset.assetId)
            clearAudioAssetData(for: audioAsset.assetId)
            let time = max(call.getDouble(Constant.Time) ?? 0, 0)
            audioAsset.setCurrentTime(time: time)
            call.resolve()
        }
    }

    @objc func getDuration(_ call: CAPPluginCall) {
        audioQueue.sync {
            self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: true)
            defer { self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: nil) }
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
            self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: true)
            defer { self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: nil) }
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
        let audioId = call.getString(Constant.AssetIdKey) ?? ""
        audioQueue.sync {
            self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: true)
            defer { self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: nil) }
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }
            self.activateSession()
            let fadeIn = call.getBool(Constant.FadeIn) ?? false
            let fadeInDuration = call.getDouble(Constant.FadeInDuration) ?? Double(Constant.DefaultFadeDuration)
            var restoredVolume: Float?
            if let data = audioAssetData[audioAsset.assetId], let volume = data["volumeBeforePause"] as? Float {
                restoredVolume = volume
            }
            if fadeIn {
                let targetVolume = restoredVolume ?? (audioAsset.channels.first?.volume ?? audioAsset.initialVolume)
                audioAsset.setVolume(volume: 0, fadeDuration: 0)
                audioAsset.resume()
                audioAsset.setVolume(volume: NSNumber(value: targetVolume), fadeDuration: fadeInDuration)
            } else {
                if let volume = restoredVolume {
                    audioAsset.setVolume(volume: NSNumber(value: volume), fadeDuration: 0)
                }
                audioAsset.resume()
            }
            if var data = audioAssetData[audioAsset.assetId] {
                data.removeValue(forKey: "volumeBeforePause")
                audioAssetData[audioAsset.assetId] = data
            }

            // Update notification when resumed
            if self.showNotification {
                self.updateNowPlayingInfo(audioId: audioId, audioAsset: audioAsset)
            }

            call.resolve()
        }
    }

    @objc func pause(_ call: CAPPluginCall) {
        audioQueue.sync {
            self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: true)
            defer { self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: nil) }
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }
            cancelPendingPlay(for: audioAsset.assetId)
            let fadeOut = call.getBool(Constant.FadeOut) ?? false
            let fadeOutDuration = call.getDouble(Constant.FadeOutDuration) ?? Double(Constant.DefaultFadeDuration)
            let currentVolume = audioAsset.channels.first?.volume ?? audioAsset.initialVolume
            var data = audioAssetData[audioAsset.assetId] ?? [:]
            data["volumeBeforePause"] = currentVolume
            audioAssetData[audioAsset.assetId] = data

            if fadeOut {
                audioAsset.stopWithFade(fadeOutDuration: fadeOutDuration, toPause: true)
            } else {
                audioAsset.pause()
            }

            // Update notification when paused
            if self.showNotification {
                self.updatePlaybackState(isPlaying: false)
            }

            self.endSession()
            call.resolve()
        }
    }

    /// Stops playback of the audio asset identified by `assetId` from the plugin call and performs related cleanup.
    ///
    /// The `assetId` is read from the call using `Constant.AssetIdKey`. If the asset is currently playing it will be stopped; if `showNotification` is enabled the Now Playing info is cleared and `currentlyPlayingAssetId` is reset. If the asset was created by `playOnce`, it is removed from `playOnceAssets` and its notification metadata is removed. The audio session is ended if appropriate. The call is resolved on success or rejected with an error message on failure.
    @objc func stop(_ call: CAPPluginCall) {
        let audioId = call.getString(Constant.AssetIdKey) ?? ""
        let fadeOut = call.getBool(Constant.FadeOut) ?? false
        let fadeOutDuration = call.getDouble(Constant.FadeOutDuration) ?? Double(Constant.DefaultFadeDuration)

        audioQueue.sync {
            self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: true)
            defer { self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: nil) }
            guard !self.audioList.isEmpty else {
                call.reject("Audio list is empty")
                return
            }

            do {
                try self.stopAudio(audioId: audioId, fadeOut: fadeOut, fadeOutDuration: fadeOutDuration)

                // Reset current track when stopping the asset that was playing (internal state, not just for notifications)
                if self.currentlyPlayingAssetId == audioId {
                    self.currentlyPlayingAssetId = nil
                }

                // Clean up playOnce tracking if this was a playOnce asset
                if self.playOnceAssets.contains(audioId) {
                    self.playOnceAssets.remove(audioId)
                    self.notificationMetadataMap.removeValue(forKey: audioId)
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
            self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: true)
            defer { self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: nil) }
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }

            audioAsset.loop()
            call.resolve()
        }
    }

    /// Unloads a previously loaded audio asset identified by `assetId` and removes any associated one-shot tracking or metadata.
    /// - Parameters:
    ///   - call: The plugin call that must include the `assetId` string under the key used by the plugin; on success the call is resolved, on failure the call is rejected (for example if the audio list is empty or the asset cannot be cast/unloaded).
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

                // Reset current track if this was the currently playing asset (internal state tracking)
                if self.currentlyPlayingAssetId == audioId {
                    self.currentlyPlayingAssetId = nil
                }

                // Clean up playOnce tracking if this was a playOnce asset
                if self.playOnceAssets.contains(audioId) {
                    self.playOnceAssets.remove(audioId)
                    self.notificationMetadataMap.removeValue(forKey: audioId)
                }

                call.resolve()
            } else if let audioNumber = self.audioList[audioId] as? NSNumber {
                // Also handle unloading system sounds
                AudioServicesDisposeSystemSoundID(SystemSoundID(audioNumber.intValue))
                self.audioList[audioId] = nil

                // Clean up playOnce tracking if this was a playOnce asset
                if self.playOnceAssets.contains(audioId) {
                    self.playOnceAssets.remove(audioId)
                    self.notificationMetadataMap.removeValue(forKey: audioId)
                }

                call.resolve()
            } else {
                call.reject("Cannot cast to AudioAsset")
            }
        }
    }

    @objc func setVolume(_ call: CAPPluginCall) {
        audioQueue.sync {
            self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: true)
            defer { self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: nil) }
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }

            let volume = min(max(call.getFloat(Constant.Volume) ?? Constant.DefaultVolume, Constant.MinVolume), Constant.MaxVolume)
            let durationSecs = call.getDouble(Constant.FadeDuration) ?? 0.0
            audioAsset.setVolume(volume: volume as NSNumber, fadeDuration: durationSecs)
            call.resolve()
        }
    }

    @objc func setRate(_ call: CAPPluginCall) {
        audioQueue.sync {
            self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: true)
            defer { self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: nil) }
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
            self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: true)
            defer { self.audioQueue.setSpecific(key: self.audioQueueContextKey, value: nil) }
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

    // swiftlint:disable:next cyclomatic_complexity function_body_length
    @objc private func preloadAsset(_ call: CAPPluginCall, isComplex complex: Bool) {
        // Common default values to ensure consistency
        let audioId = call.getString(Constant.AssetIdKey) ?? ""
        let channels: Int?
        let volume: Float?
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
                // Store metadata on audioQueue for thread safety
                audioQueue.sync(flags: .barrier) {
                    notificationMetadataMap[audioId] = metadataDict
                }
            }
        }

        if complex {
            volume = min(max(call.getFloat("volume") ?? Constant.DefaultVolume, Constant.MinVolume), Constant.MaxVolume)
            channels = max(call.getInt("channels") ?? Constant.DefaultChannels, 1)
        } else {
            channels = Constant.DefaultChannels
            volume = Constant.DefaultVolume
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
                            withVolume: volume)
                        self.audioList[audioId] = audioAsset
                        call.resolve()
                        return
                    }
                } else {
                    // Handle remote URL
                    // Extract headers if provided
                    var headers: [String: String]?
                    if let headersObj = call.getObject("headers") {
                        headers = [:]
                        for (key, value) in headersObj {
                            if let stringValue = value as? String {
                                headers?[key] = stringValue
                            }
                        }
                    }
                    let remoteAudioAsset = RemoteAudioAsset(
                        owner: self,
                        withAssetId: audioId,
                        withPath: assetPath,
                        withChannels: channels,
                        withVolume: volume,
                        withHeaders: headers
                    )
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
                        withVolume: volume)
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
                        withVolume: volume)
                    self.audioList[audioId] = audioAsset
                }
            }
            call.resolve()
        }
    }
    private func stopAudio(audioId: String, fadeOut: Bool, fadeOutDuration: Double) throws {
        var asset: AudioAsset?

        audioQueue.sync {
            asset = self.audioList[audioId] as? AudioAsset
        }

        guard let audioAsset = asset else {
            throw MyError.runtimeError(Constant.ErrorAssetNotFound)
        }

        clearAudioAssetData(for: audioId)

        if fadeOut {
            audioAsset.stopWithFade(fadeOutDuration: fadeOutDuration)
        } else {
            audioAsset.stop()
        }
    }

    private func clearAudioAssetData(for audioId: String) {
        audioAssetData[audioId] = nil
    }

    private func cancelPendingPlay(for audioId: String) {
        if let task = pendingPlayTasks[audioId] {
            task.cancel()
            pendingPlayTasks[audioId] = nil
        }
    }

    private func handleFadeOut(for asset: AudioAsset, audioId: String, fadeOutDuration: TimeInterval, fadeOutStartTime: TimeInterval) {
        let duration = asset.getDuration()
        if duration <= 0 || !duration.isFinite {
            logger.warning("Audio asset has no finite duration, skipping fadeOut for %@", audioId)
            return
        }

        var startTime = max(duration - fadeOutDuration, 0)
        if fadeOutStartTime > 0 {
            startTime = fadeOutStartTime
        }

        audioAssetData[audioId] = [
            "fadeOut": true,
            "fadeOutStartTime": startTime,
            "fadeOutDuration": fadeOutDuration
        ]
    }

    internal func executeOnAudioQueue(_ block: @escaping () -> Void) {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            block()  // Already on queue
        } else {
            if isRunningTests {
                audioQueue.async {
                    block()
                }
            } else {
                audioQueue.sync(flags: .barrier) {
                    block()
                }
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

            if let fadeData = audioAssetData[asset.assetId],
               let fadeOut = fadeData["fadeOut"] as? Bool, fadeOut,
               let fadeOutStartTime = fadeData["fadeOutStartTime"] as? Double,
               let fadeOutDuration = fadeData["fadeOutDuration"] as? Double,
               currentTime >= fadeOutStartTime {
                asset.stopWithFade(fadeOutDuration: fadeOutDuration)
                audioAssetData[asset.assetId] = nil
            }
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

    /// Updates the system Now Playing information for the specified audio asset.
    ///
    /// Looks up stored metadata for `audioId` and publishes title, artist, album, artwork (if provided),
    /// playback duration, elapsed time, and playback rate to MPNowPlayingInfoCenter. Artwork, when present,
    /// is loaded asynchronously and applied when available.
    /// - Parameters:
    ///   - audioId: The asset identifier used to retrieve Now Playing metadata.
    ///   - audioAsset: The audio asset used to obtain current playback time and duration.

    private func updateNowPlayingInfo(audioId: String, audioAsset: AudioAsset) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            var nowPlayingInfo = [String: Any]()

            // Get metadata from the map (read on audioQueue for thread safety)
            let metadata = self.audioQueue.sync { self.notificationMetadataMap[audioId] }
            if let metadata = metadata {
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

    /// Clears the Now Playing info. Only used when tearing down (deinit); stop/unload do not clear
    /// so that the next play can overwrite the notification without a race.
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

    /// Loads an image from a local file path or a remote URL and delivers it to the completion handler.
    /// - Parameters:
    ///   - urlString: A string representing either a local file path (plain path or `file://` URL) or a remote URL (e.g., `http://` or `https://`).
    ///   - completion: Called with the loaded `UIImage` on success, or `nil` if the image could not be loaded.
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
