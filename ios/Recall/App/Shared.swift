import Foundation
import RecallCore

/// Where Recall keeps its data. The App Group lets the widget read the same database;
/// if the group isn't available (some sideloading setups), the app's own folder is used.
enum Shared {
    static let group = "group.app.recall"

    static var container: URL {
        if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) { return url }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    static var defaults: UserDefaults { UserDefaults(suiteName: group) ?? .standard }

    static var databasePath: String {
        let dir = container.appendingPathComponent("Recall", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("recall.db").path
    }

    static func openStore() -> RecallStore {
        do { return try RecallStore(path: databasePath) }
        catch { fatalError("Recall couldn't open its database: \(error)") }
    }
}
