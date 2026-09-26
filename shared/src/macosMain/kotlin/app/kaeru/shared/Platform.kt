package app.kaeru.shared

import platform.Foundation.NSProcessInfo

actual object Platform {
    actual fun name(): String = "macOS " + NSProcessInfo.processInfo.operatingSystemVersionString
}
