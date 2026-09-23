import Foundation
import UIKit

/// Keeps the model from cooking the phone or draining the battery. Same rules as Android:
/// pause when warm, heavy work only while charging, never on Low Power Mode.
enum Safety {
    enum Load { case interactive, light, heavy }

    struct Verdict { let ok: Bool; let reason: String? }

    @MainActor
    static func check(_ load: Load) -> Verdict {
        let thermal = ProcessInfo.processInfo.thermalState
        if thermal == .critical || thermal == .serious { return Verdict(ok: false, reason: "Your iPhone is hot. Recall will wait until it cools down.") }
        if thermal == .fair && load != .interactive { return Verdict(ok: false, reason: "Your iPhone is warm. Background work is paused.") }
        if ProcessInfo.processInfo.isLowPowerModeEnabled && load != .interactive {
            return Verdict(ok: false, reason: "Low Power Mode is on.")
        }
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        let charging = device.batteryState == .charging || device.batteryState == .full
        if load == .heavy && !charging { return Verdict(ok: false, reason: "Waits until your iPhone is charging.") }
        if load == .light && !charging && device.batteryLevel >= 0 && device.batteryLevel < 0.3 {
            return Verdict(ok: false, reason: "Battery is below 30%.")
        }
        return Verdict(ok: true, reason: nil)
    }
}
