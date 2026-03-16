import Foundation
import os.log

public class Logger {
    private var osLogger: OSLog?

    public static var debugModeEnabled = false

    public init(logTag: String) {
        osLogger = OSLog(subsystem: Bundle.main.bundleIdentifier ?? "com.capgo.native-audio", category: logTag)
    }

    func error(_ message: String, _ args: CVarArg...) {
        osLog(message, args, level: .error)
    }

    func warning(_ message: String, _ args: CVarArg...) {
        osLog(message, args, level: .fault)
    }

    func info(_ message: String, _ args: CVarArg...) {
        osLog(message, args, level: .info)
    }

    func debug(_ message: String, _ args: CVarArg...) {
        osLog(message, args, level: .debug)
    }

    func verbose(_ message: String, _ args: CVarArg...) {
        osLog(message, args, level: .default)
    }

    private func osLog(_ message: String, _ args: [CVarArg], level: OSLogType = .default) {
        if !Logger.debugModeEnabled {
            return
        }
        let formatted = String(format: message, arguments: args)
        guard let osLogger else {
            return
        }
        os_log("%{public}@", log: osLogger, type: level, formatted)
    }
}
