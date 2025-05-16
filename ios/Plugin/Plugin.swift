import AVFoundation
import Capacitor
import CoreAudio
import Foundation
import os.log

enum MyError: Error {
    case runtimeError(String)
}

/// Please read the Capacitor iOS Plugin Development Guide
/// here: https://capacitor.ionicframework.com/docs/plugins/ios
@objc(NativeAudio)
public class NativeAudio: CAPPlugin, AVAudioPlayerDelegate, CAPBridgedPlugin {
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
        CAPPluginMethod(name: "clearCache", returnType: CAPPluginReturnPromise)
    ]
    private var logger = nil as OSLog?
    internal let audioQueue = DispatchQueue(label: "ee.forgr.audio.queue", qos: .userInitiated, attributes: .concurrent)
    private var audioList: [String: Any] = [:] {
        didSet {
            // Ensure audioList modifications happen on audioQueue
            assert(DispatchQueue.getSpecific(key: queueKey) != nil)
        }
    }
    private let queueKey = DispatchSpecificKey<Bool>()
    var session = AVAudioSession.sharedInstance()

    // Add observer for audio session interruptions
    private var interruptionObserver: Any?

    private var pendingPlayTasks: [String: DispatchWorkItem] = [:]
    private var pendingFadeOutTasks: [String: DispatchWorkItem] = [:]

    override public init() {
        self.logger = OSLog(subsystem: Bundle.main.bundleIdentifier ?? "NativeAudio", category: self.identifier)
        super.init()
    }

    func log(_ message: String, level: OSLogType = .default, _ args: CVarArg...) {
        guard let logger = self.logger else { return }
        let formatted = String(format: message, arguments: args)
        os_log("%{public}@", log: logger, type: level, formatted)
    }

    @objc override public func load() {
        super.load()
        audioQueue.setSpecific(key: queueKey, value: true)

        setupAudioSession()
        setupInterruptionHandling()

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

                if !hasPlayingAssets {
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
        do {
            // Only set the category without immediately activating/deactivating
            try self.session.setCategory(AVAudioSession.Category.playback, options: .mixWithOthers)
            // Don't activate/deactivate in setup - we'll do this explicitly when needed
        } catch {
            log("Failed to setup audio session: %@", level: .error, error.localizedDescription)
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

    @objc func configure(_ call: CAPPluginCall) {
        let focus = call.getBool(Constant.FocusAudio) ?? false
        let background = call.getBool(Constant.Background) ?? false
        let ignoreSilent = call.getBool(Constant.IgnoreSilent) ?? true

        log("Configuring audio session with focus: %@, background: %@, ignoreSilent: %@", level: .info, "\(focus)", "\(background)", "\(ignoreSilent)")
        // Use a single audio session configuration block for better atomicity
        do {
            // Set category first
            if focus {
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
            log("Error configuring audio session: %@", level: .error, error.localizedDescription)
        }

        call.resolve()
    }

    @objc func isPreloaded(_ call: CAPPluginCall) {
        guard let assetId = call.getString(Constant.AssetId) else {
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
            log("Failed to set session active: %@", level: .error, error.localizedDescription)
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

            // Only deactivate if no assets are playing
            if !hasPlayingAssets {
                try self.session.setActive(false, options: .notifyOthersOnDeactivation)
            }
        } catch {
            log("Failed to deactivate audio session: %@", level: .error, error.localizedDescription)
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
        let audioId = call.getString(Constant.AssetId) ?? ""
        let time = max(call.getDouble(Constant.Time) ?? 0, 0) // Ensure non-negative time
        let delay = max(call.getDouble("delay") ?? 0, 0) // Ensure non-negative delay
        let volume = call.getFloat(Constant.Volume) ?? nil
        let fadeIn = call.getBool(Constant.FadeIn) ?? false
        let fadeInDuration = call.getDouble(Constant.FadeInDuration) ?? Double(Constant.DefaultFadeDuration)
        let fadeOut = call.getBool(Constant.FadeOut) ?? false
        let fadeOutDuration = call.getDouble(Constant.FadeOutDuration) ?? Double(Constant.DefaultFadeDuration)
        let fadeOutStartTime = call.getDouble(Constant.FadeOutStartTime) ?? 0.0

        log("Playing audio with id: %@, time: %f, delay: %f, volume: %f, fadeIn: %@, fadeInDuration: %f, fadeOut: %@, fadeOutDuration: %f, fadeOutStartTime: %f", level: .info, audioId, time, delay, volume ?? 0.0, "\(fadeIn)", fadeInDuration, "\(fadeOut)", fadeOutDuration, fadeOutStartTime)

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

            if let asset = asset as? AudioAsset {
                // Cancel any pending play or fade out for this asset
                cancelPendingPlay(for: audioId)
                cancelPendingFadeOut(for: audioId)

                self.activateSession()

                let playBlock = { [weak self] in
                    guard let self = self else { return }
                    self.executeOnAudioQueue {
                        if fadeIn {
                            asset.playWithFade(time: time, volume: volume, fadeInDuration: fadeInDuration)
                        } else {
                            asset.play(time: time, volume: volume)
                        }
                        self.pendingPlayTasks[audioId] = nil

                        if fadeOut {
                            self.handleFadeOut(for: asset, audioId: audioId, fadeOutDuration: fadeOutDuration, fadeOutStartTime: fadeOutStartTime)
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
        var asset: AudioAsset?
        audioQueue.sync { // Read operations should use sync
            asset = self.audioList[call.getString(Constant.AssetId) ?? ""] as? AudioAsset
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

            cancelPendingPlay(for: audioAsset.assetId)
            cancelPendingFadeOut(for: audioAsset.assetId)
            let time = max(call.getDouble("time") ?? 0, 0) // Ensure non-negative time
            log("'Setting current time for audio asset: %@, time: %f", level: .info, audioAsset.assetId, time)
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
            log("Resuming audio asset: %@", level: .info, audioAsset.assetId)
            self.activateSession()
            audioAsset.resume()
            call.resolve()
        }
    }

    @objc func pause(_ call: CAPPluginCall) {
        audioQueue.sync {
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }
            log("Pausing audio asset: %@", level: .info, audioAsset.assetId)
            cancelPendingPlay(for: audioAsset.assetId)
            cancelPendingFadeOut(for: audioAsset.assetId)
            audioAsset.pause()
            self.endSession()
            call.resolve()
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        let audioId = call.getString(Constant.AssetId) ?? ""
        let fadeOut = call.getBool(Constant.FadeOut) ?? false
        let fadeOutDuration = call.getDouble(Constant.FadeOutDuration) ?? Double(Constant.DefaultFadeDuration)

        audioQueue.sync {
            guard !self.audioList.isEmpty else {
                call.reject("Audio list is empty")
                return
            }

            do {
                log("Stopping audio asset with id: %@, fadeOut: %@, fadeOutDuration: %f", level: .info, audioId, "\(fadeOut)", fadeOutDuration)
                try self.stopAudio(audioId: audioId, fadeOut: fadeOut, fadeOutDuration: fadeOutDuration)
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

            log("Looping audio asset: %@", level: .info, audioAsset.assetId)
            cancelPendingPlay(for: audioAsset.assetId)
            cancelPendingFadeOut(for: audioAsset.assetId)
            audioAsset.loop()
            call.resolve()
        }
    }

    @objc func unload(_ call: CAPPluginCall) {
        let audioId = call.getString(Constant.AssetId) ?? ""

        audioQueue.sync(flags: .barrier) { // Use barrier for writing operations
            guard !self.audioList.isEmpty else {
                call.reject("Audio list is empty")
                return
            }

            cancelPendingPlay(for: audioId)
            cancelPendingFadeOut(for: audioId)

            log("Unloading audio asset with id: %@", level: .info, audioId)
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
            let durationSecs = call.getDouble(Constant.FadeDuration) ?? 0.0

            audioAsset.setVolume(volume: volume as NSNumber, fadeDuration: durationSecs)
            call.resolve()
        }
    }

    @objc func setRate(_ call: CAPPluginCall) {
        audioQueue.sync {
            guard let audioAsset: AudioAsset = self.getAudioAsset(call) else {
                call.reject("Failed to get audio asset")
                return
            }

            log("Setting rate for audio asset: %@", level: .info, audioAsset.assetId)
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

    @objc private func preloadAsset(_ call: CAPPluginCall, isComplex complex: Bool) {
        // Common default values to ensure consistency
        let audioId = call.getString(Constant.AssetId) ?? ""
        let channels: Int?
        let volume: Float?
        var isLocalUrl: Bool = call.getBool("isUrl") ?? false

        if audioId == "" {
            call.reject(Constant.ErrorAssetId)
            return
        }
        var assetPath: String = call.getString(Constant.AssetPath) ?? ""

        if assetPath == "" {
            call.reject(Constant.ErrorAssetPath)
            return
        }

        if complex {
            volume = min(max(call.getFloat("volume") ?? Constant.DefaultVolume, Constant.MinVolume), Constant.MaxVolume)
            channels = max(call.getInt("channels") ?? Constant.DefaultChannels, 1)
        } else {
            channels = Constant.DefaultChannels
            volume = Constant.DefaultVolume
            isLocalUrl = false
        }

        log("Preloading audio asset with id: %@, path: %@, channels: %d, volume: %f", level: .info, audioId, assetPath, channels ?? Constant.DefaultChannels, volume ?? Constant.DefaultVolume)

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
                    let remoteAudioAsset = RemoteAudioAsset(owner: self, withAssetId: audioId, withPath: assetPath, withChannels: channels, withVolume: volume)
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

        if fadeOut {
            audioAsset.stopWithFade(fadeOutDuration: fadeOutDuration)
        } else {
            audioAsset.stop()
        }
    }

    private func cancelPendingFadeOut(for audioId: String) {
        if let task = pendingFadeOutTasks[audioId] {
            task.cancel()
            pendingFadeOutTasks[audioId] = nil
        }
    }

    private func cancelPendingPlay(for audioId: String) {
        if let task = pendingPlayTasks[audioId] {
            task.cancel()
            pendingPlayTasks[audioId] = nil
        }
    }

    private func handleFadeOut(for asset: AudioAsset, audioId: String, fadeOutDuration: TimeInterval, fadeOutStartTime: TimeInterval) {
        cancelPendingFadeOut(for: audioId)

        let duration = asset.getDuration()
        if duration <= 0 || !duration.isFinite {
            log("Audio asset has no duration or is not finite, skipping fade out for asset: %@", level: .info, audioId)
            return
        }

        var startTime = max(duration - fadeOutDuration, 0)
        if fadeOutStartTime > 0 {
            startTime = fadeOutStartTime
        }

        log("Scheduling fade out for audio asset: %@, startTime: %fs, fadeOutDuration: %fs", level: .info, audioId, startTime, fadeOutDuration)

        let workItem = DispatchWorkItem { [weak self] in
            asset.stopWithFade(fadeOutDuration: fadeOutDuration)
            self?.pendingFadeOutTasks[audioId] = nil
        }
        pendingFadeOutTasks[audioId] = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + startTime, execute: workItem)
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
}
