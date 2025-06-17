import os.log

public class Logger {
    private var osLogger = nil as OSLog?

    // Constructor - init with logtag
    public init(logTag: String) {
        osLogger = OSLog(subsystem: Bundle.main.bundleIdentifier ?? "com.capgo.native-audio", category: logTag)
    }

    /**************************
     * Log methods
     ***************************/
    func error(_ message: String, _ args: CVarArg...) {
        osLog(message, level: .error, args)
    }
    func warning(_ message: String, _ args: CVarArg...) {
        osLog(message, level: .fault, args)
    }
    func info(_ message: String, _ args: CVarArg...) {
        osLog(message, level: .info, args)
    }
    func debug(_ message: String, _ args: CVarArg...) {
        osLog(message, level: .debug, args)
    }
    func verbose(_ message: String, _ args: CVarArg...) {
        osLog(message, level: .default, args)
    }

    /**************************
     * Internal methods
     ***************************/

    func osLog(_ message: String, level: OSLogType = .default, _ args: CVarArg...) {
        if !NativeAudio.debugModeEnabled {
            return
        }
        let formatted = String(format: message, arguments: args)
        guard let osLogger = osLogger else {
            print("OSLog is not initialized")
            return
        }
        os_log("%{public}@", log: osLogger, type: level, formatted)
    }
}
