package app.kaeru.shared

import platform.UIKit.UIDevice

actual object Platform {
    actual fun name(): String = UIDevice.currentDevice.systemName
}
