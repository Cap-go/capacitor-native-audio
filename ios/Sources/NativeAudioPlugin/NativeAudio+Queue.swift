import Capacitor
import Foundation
import MediaPlayer

extension NativeAudio {
    @objc func setQueue(_ call: CAPPluginCall) {
        guard let rawItems = call.getArray("items", JSObject.self), !rawItems.isEmpty else {
            call.reject("Queue must include at least one item")
            return
        }

        let startIndex = max(0, call.getInt("startIndex") ?? 0)
        let autoPlay = call.getBool("autoPlay") ?? false

        executeOnAudioQueue {
            self.playbackQueueIds = []
            self.playbackQueueItemById = [:]
            self.playbackQueueIndex = -1

            for item in rawItems {
                guard let assetId = item["assetId"] as? String,
                      let assetPath = item["assetPath"] as? String,
                      !assetId.isEmpty, !assetPath.isEmpty else {
                    continue
                }
                self.playbackQueueIds.append(assetId)
                self.playbackQueueItemById[assetId] = item
            }

            guard !self.playbackQueueIds.isEmpty else {
                call.reject("No valid queue items")
                return
            }

            let cappedStart = min(startIndex, self.playbackQueueIds.count - 1)
            self.preloadQueueItemIfNeeded(assetId: self.playbackQueueIds[cappedStart]) { [weak self] success in
                guard let self else { return }
                guard success else {
                    call.reject("Failed to prepare queue item")
                    return
                }
                self.playbackQueueIndex = cappedStart
                let assetId = self.playbackQueueIds[cappedStart]
                self.refreshQueueRemoteCommands()
                if autoPlay {
                    self.playQueueAsset(assetId: assetId, reason: "setQueue")
                } else {
                    self.notifyQueueChange(assetId: assetId, index: cappedStart, reason: "setQueue")
                }
                call.resolve(self.buildQueueStateDictionary())
            }
        }
    }

    @objc func clearQueue(_ call: CAPPluginCall) {
        executeOnAudioQueue {
            self.playbackQueueIds = []
            self.playbackQueueItemById = [:]
            self.playbackQueueIndex = -1
            self.refreshQueueRemoteCommands()
            call.resolve()
        }
    }

    @objc func getQueueState(_ call: CAPPluginCall) {
        call.resolve(readOnAudioQueue { self.buildQueueStateDictionary() })
    }

    @objc func queueNext(_ call: CAPPluginCall) {
        advanceQueue(by: 1, call: call, reason: "next")
    }

    @objc func queuePrevious(_ call: CAPPluginCall) {
        advanceQueue(by: -1, call: call, reason: "previous")
    }

    @objc func queueSelect(_ call: CAPPluginCall) {
        guard let assetId = call.getString(Constant.AssetIdKey) else {
            call.reject(Constant.ErrorAssetId)
            return
        }

        executeOnAudioQueue {
            guard let index = self.playbackQueueIds.firstIndex(of: assetId) else {
                call.reject("Asset is not in the queue")
                return
            }
            self.selectQueueIndex(index, call: call, reason: "select")
        }
    }

    @objc func getPlaybackState(_ call: CAPPluginCall) {
        let requestedId = call.getString(Constant.AssetIdKey)

        audioQueue.sync {
            let assetId = requestedId
                ?? self.currentlyPlayingAssetId
                ?? (self.playbackQueueIndex >= 0 ? self.playbackQueueIds[self.playbackQueueIndex] : nil)

            guard let assetId, let asset = self.audioList[assetId] as? AudioAsset else {
                call.resolve([
                    "assetId": "",
                    "state": PlaybackStateValue.stopped.rawValue,
                    "reason": "snapshot",
                    "isPlaying": false,
                    "currentTime": 0,
                    "duration": 0
                ] as [String: Any])
                return
            }

            let state = self.resolvePlaybackState(assetId: assetId, audioAsset: asset)
            let isPlaying = state == .playing
            call.resolve([
                "assetId": assetId,
                "state": state.rawValue,
                "reason": "snapshot",
                "isPlaying": isPlaying,
                "currentTime": asset.getCurrentTime(),
                "duration": {
                    let raw = asset.getDuration()
                    return raw.isFinite ? raw : 0
                }()
            ] as [String: Any])
        }
    }

    @objc func getRate(_ call: CAPPluginCall) {
        guard let assetId = call.getString(Constant.AssetIdKey) else {
            call.reject(Constant.ErrorAssetId)
            return
        }

        audioQueue.sync {
            guard let asset = self.audioList[assetId] as? AudioAsset else {
                call.reject(Constant.ErrorAssetNotFound)
                return
            }
            call.resolve(["rate": asset.getPlaybackRate()])
        }
    }

    @objc func setSpeaker(_ call: CAPPluginCall) {
        setAudioRouteOverride(useSpeaker: true, call: call)
    }

    @objc func setEarpiece(_ call: CAPPluginCall) {
        setAudioRouteOverride(useSpeaker: false, call: call)
    }

    @objc func getAudioRoute(_ call: CAPPluginCall) {
        call.resolve(["route": currentAudioRouteLabel()])
    }

    internal func setupQueueRemoteCommandsIfNeeded() {
        let commandCenter = MPRemoteCommandCenter.shared()

        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            self.advanceQueueFromRemote(delta: 1)
            return .success
        }

        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            self.advanceQueueFromRemote(delta: -1)
            return .success
        }

        refreshQueueRemoteCommands()
    }

    internal func refreshQueueRemoteCommands() {
        let hasQueue = playbackQueueIds.count > 1 && showNotification
        let commandCenter = MPRemoteCommandCenter.shared()
        commandCenter.nextTrackCommand.isEnabled = hasQueue
        commandCenter.previousTrackCommand.isEnabled = hasQueue
    }

    private func advanceQueueFromRemote(delta: Int) {
        executeOnAudioQueue {
            guard !self.playbackQueueIds.isEmpty else { return }
            let nextIndex = self.playbackQueueIndex + delta
            guard nextIndex >= 0, nextIndex < self.playbackQueueIds.count else { return }
            self.selectQueueIndex(nextIndex, call: nil, reason: delta > 0 ? "remoteNext" : "remotePrevious")
        }
    }

    private func advanceQueue(by delta: Int, call: CAPPluginCall, reason: String) {
        executeOnAudioQueue {
            guard !self.playbackQueueIds.isEmpty else {
                call.reject("Queue is empty")
                return
            }
            let nextIndex = self.playbackQueueIndex + delta
            guard nextIndex >= 0, nextIndex < self.playbackQueueIds.count else {
                call.reject("Queue boundary reached")
                return
            }
            self.selectQueueIndex(nextIndex, call: call, reason: reason)
        }
    }

    private func selectQueueIndex(_ index: Int, call: CAPPluginCall?, reason: String) {
        let assetId = playbackQueueIds[index]
        preloadQueueItemIfNeeded(assetId: assetId) { [weak self] success in
            guard let self else { return }
            guard success else {
                call?.reject("Failed to prepare queue item")
                return
            }
            self.playbackQueueIndex = index
            self.playQueueAsset(assetId: assetId, reason: reason)
            call?.resolve(self.buildQueueStateDictionary())
        }
    }

    private func playQueueAsset(assetId: String, reason: String) {
        if let previousId = currentlyPlayingAssetId,
           previousId != assetId,
           let previous = audioList[previousId] as? AudioAsset {
            previous.stop()
        }
        guard let asset = audioList[assetId] as? AudioAsset else { return }
        currentlyPlayingAssetId = assetId
        asset.play(time: 0)
        notifyPlaybackState(assetId: assetId, reason: reason, state: .playing, audioAsset: asset)
        notifyQueueChange(assetId: assetId, index: playbackQueueIndex, reason: reason)
        if showNotification {
            updateNowPlayingInfo(audioId: assetId, audioAsset: asset)
        }
    }

    private func notifyQueueChange(assetId: String, index: Int, reason: String) {
        notifyListeners("queueChange", data: [
            "assetId": assetId,
            "index": index,
            "reason": reason
        ])
    }

    internal func buildQueueStateDictionary() -> [String: Any] {
        var result: [String: Any] = [
            "count": playbackQueueIds.count,
            "index": playbackQueueIndex,
            "assetIds": playbackQueueIds
        ]
        if playbackQueueIndex >= 0, playbackQueueIndex < playbackQueueIds.count {
            result["assetId"] = playbackQueueIds[playbackQueueIndex]
        } else {
            result["assetId"] = ""
        }
        return result
    }

    private func preloadQueueItemIfNeeded(assetId: String, completion: @escaping (Bool) -> Void) {
        if audioList[assetId] != nil {
            completion(true)
            return
        }
        guard let item = playbackQueueItemById[assetId] else {
            completion(false)
            return
        }

        let assetPath = item["assetPath"] as? String ?? ""
        let isUrl = item["isUrl"] as? Bool ?? false
        let volume = min(
            max(item["volume"] as? Float ?? Constant.DefaultVolume, Constant.MinVolume),
            Constant.MaxVolume
        )
        let rawChannels = item["audioChannelNum"] as? Int ?? Constant.DefaultChannels
        let audioChannelNum = min(max(rawChannels, 1), Constant.MaxChannels)

        preloadQueueItem(
            assetId: assetId,
            assetPath: assetPath,
            isUrl: isUrl,
            volume: volume,
            audioChannelNum: audioChannelNum,
            item: item,
            completion: completion
        )
    }

    internal func preloadQueueItem(
        assetId: String,
        assetPath: String,
        isUrl: Bool,
        volume: Float,
        audioChannelNum: Int,
        item: [String: Any],
        completion: @escaping (Bool) -> Void
    ) {
        if let metadata = item["notificationMetadata"] as? [String: Any] {
            var metadataDict: [String: String] = [:]
            for key in ["title", "artist", "album", "artworkUrl"] {
                if let value = metadata[key] as? String {
                    metadataDict[key] = value
                }
            }
            if !metadataDict.isEmpty {
                notificationMetadataMap[assetId] = metadataDict
            }
        }

        if audioList[assetId] != nil {
            completion(true)
            return
        }

        var headers: [String: String]?
        if let headersObj = item["headers"] as? [String: Any] {
            headers = [:]
            for (key, value) in headersObj {
                if let stringValue = value as? String {
                    headers?[key] = stringValue
                }
            }
        }

        if let url = URL(string: assetPath), url.scheme != nil {
            if url.isFileURL {
                let audioAsset = AudioAsset(
                    owner: self,
                    withAssetId: assetId,
                    withPath: url.path,
                    withChannels: audioChannelNum,
                    withVolume: volume
                )
                audioList[assetId] = audioAsset
                completion(true)
                return
            }

            let remoteAudioAsset = RemoteAudioAsset(
                owner: self,
                withAssetId: assetId,
                withPath: assetPath,
                withChannels: audioChannelNum,
                withVolume: volume,
                withHeaders: headers
            )
            audioList[assetId] = remoteAudioAsset
            completion(true)
            return
        }

        if isUrl {
            let filePath = URL(fileURLWithPath: assetPath).path
            if FileManager.default.fileExists(atPath: filePath) {
                let audioAsset = AudioAsset(
                    owner: self,
                    withAssetId: assetId,
                    withPath: filePath,
                    withChannels: audioChannelNum,
                    withVolume: volume
                )
                audioList[assetId] = audioAsset
                completion(true)
                return
            }
            completion(false)
            return
        }

        let publicPath = assetPath.starts(with: "public/") ? assetPath : "public/" + assetPath
        let assetPathSplit = publicPath.components(separatedBy: ".")
        guard assetPathSplit.count >= 2,
              let basePath = Bundle.main.path(forResource: assetPathSplit[0], ofType: assetPathSplit[1]) else {
            completion(false)
            return
        }

        let audioAsset = AudioAsset(
            owner: self,
            withAssetId: assetId,
            withPath: basePath,
            withChannels: audioChannelNum,
            withVolume: volume
        )
        audioList[assetId] = audioAsset
        completion(true)
    }

    internal func restoreRouteControlSessionIfNeeded() throws {
        guard let savedCategory = routeControlSavedCategory else {
            return
        }
        try session.overrideOutputAudioPort(.none)
        let mode = routeControlSavedMode ?? .default
        if let savedOptions = routeControlSavedOptions {
            try session.setCategory(savedCategory, mode: mode, options: savedOptions)
        } else {
            try session.setCategory(savedCategory, mode: mode)
        }
        routeControlSavedCategory = nil
        routeControlSavedOptions = nil
        routeControlSavedMode = nil
    }

    private func setAudioRouteOverride(useSpeaker: Bool, call: CAPPluginCall) {
        do {
            if routeControlSavedCategory == nil {
                routeControlSavedCategory = session.category
                routeControlSavedOptions = session.categoryOptions
                routeControlSavedMode = session.mode
            }
            var options: AVAudioSession.CategoryOptions = [.allowBluetooth]
            if useSpeaker {
                options.insert(.defaultToSpeaker)
            }
            try session.setCategory(.playAndRecord, mode: useSpeaker ? .default : .voiceChat, options: options)
            try session.setActive(true)
            try session.overrideOutputAudioPort(useSpeaker ? .speaker : .none)
            let route = currentAudioRouteLabel()
            notifyListeners("audioRouteChange", data: ["route": route])
            call.resolve()
        } catch {
            call.reject("Failed to change audio route: \(error.localizedDescription)")
        }
    }

    private func currentAudioRouteLabel() -> String {
        let outputs = session.currentRoute.outputs
        if outputs.contains(where: { $0.portType == .headphones || $0.portType == .bluetoothA2DP || $0.portType == .bluetoothLE || $0.portType == .bluetoothHFP }) {
            return "external"
        }
        if outputs.contains(where: { $0.portType == .builtInReceiver }) {
            return "earpiece"
        }
        return "speaker"
    }
}
