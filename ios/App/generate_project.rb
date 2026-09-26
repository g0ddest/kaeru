#!/usr/bin/env ruby
require 'xcodeproj'
require 'fileutils'

root = File.expand_path('..', __dir__)
project_path = File.join(__dir__, 'Kaeru.xcodeproj')
# Rebuild the in-memory project; save only generated files, preserve user data.
project = Xcodeproj::Project.new(project_path)
app = project.new_target(:application, 'Kaeru', :ios, '17.0')
tests = project.new_target(:unit_test_bundle, 'KaeruTests', :ios, '17.0')
ui_tests = project.new_target(:ui_test_bundle, 'KaeruUITests', :ios, '17.0')
tests.add_dependency(app)
ui_tests.add_dependency(app)
# The Mac application: a native macOS target over the same sources, not Catalyst — for Catalyst
# Kotlin/Native would hand over its simulator build of KaeruShared. Product and module are `Kaeru`,
# as on iOS, so `Kaeru.app` and `@testable import Kaeru` read the same on both.
mac = project.new_target(:application, 'KaeruMac', :osx, '15.0', nil, nil, 'Kaeru')
group = project.new_group('Kaeru', '..')
# Only public OAuth configuration belongs in the application. Never copy the secret.
properties_path = ARGV.first || File.join(root, '..', 'local.properties')
properties = File.exist?(properties_path) ? File.readlines(properties_path).filter_map { |line| line.strip.split('=', 2) if line.include?('=') && !line.start_with?('#') }.to_h : {}
configuration = %w[SHIKIMORI_CLIENT_ID AUTH_PROXY_URL TOGETHER_RELAY_URL].to_h { |key| [key, ENV[key] || properties[key] || ''] }
# Loudly, because the alternative already happened: a project generated without `local.properties`
# in reach writes an empty plist, the build succeeds, and the app reaches a phone where signing in
# and watching together both answer «не настроен» — with nothing anywhere saying why.
missing = configuration.select { |_, value| value.to_s.strip.empty? }.keys
unless missing.empty?
  abort("generate_project.rb: нет значений для #{missing.join(', ')}.\n" \
        "Ожидался #{properties_path} или переменные окружения. " \
        "Сборка с пустым Configuration.plist не умеет ни вход в Shikimori, ни совместный просмотр.")
end
# The version the app compares GitHub's releases against, taken from the Android client so the two
# never drift: one repository publishes both, and a release is named once.
gradle = File.join(root, '..', 'android', 'build.gradle.kts')
version_name = (File.exist?(gradle) && File.read(gradle)[/versionName\s*=\s*"([^"]+)"/, 1]) || '0.0.0'
# Universal links need the Associated Domains capability. The team that signs Kaeru has it since
# 2026-09-24, so the entitlement is on by default; a free personal team does not have it — asking
# for it there fails to sign at all, «Personal development teams … do not support the Associated
# Domains capability» — and such a team opts out with `IOS_ASSOCIATED_DOMAINS=0`, getting a build
# that signs, where an invitation opens through `kaeru://watch` instead.
associated_domains = !%w[0 false no].include?((ENV['IOS_ASSOCIATED_DOMAINS'] || properties['IOS_ASSOCIATED_DOMAINS'] || '1').to_s.downcase)
# The Mac app asks for the same domain only with `MAC_ASSOCIATED_DOMAINS=1`. Off by default: on a Mac
# the entitlement is honoured only through a provisioning profile that carries it — a Developer ID
# one for the release — and until that profile exists an invitation opens through `kaeru://watch`.
mac_associated_domains = %w[1 true yes].include?((ENV['MAC_ASSOCIATED_DOMAINS'] || properties['MAC_ASSOCIATED_DOMAINS'] || '0').to_s.downcase)
team = ENV['DEVELOPMENT_TEAM'] || properties['DEVELOPMENT_TEAM'] || 'TXY49DW96F'
Xcodeproj::Plist.write_to_path(configuration, File.join(__dir__, 'Configuration.plist'))
# The Mac app's entitlements, written like Configuration.plist because one key follows the flag above.
# The sandbox is not a formality: the code was written for iOS, where Application Support and Caches
# belong to one app. Unsandboxed on a Mac they are the user's shared folders, and
# `LocalStore.discardingCache()` would delete `~/Library/Application Support/default.store` — the
# file every unsandboxed SwiftData app opens by default. Sandboxed, all of it is Kaeru's container.
mac_entitlements = {
  'com.apple.security.app-sandbox' => true,
  # Shikimori, Kodik, the relay, GitHub releases, Firebase, the television being paired.
  'com.apple.security.network.client' => true,
  # The host's side of watching together on the local network: TogetherLANTransport's listener.
  'com.apple.security.network.server' => true,
  # Voice messages in watching together; the hardened runtime asks for it as much as the sandbox.
  'com.apple.security.device.audio-input' => true
}
mac_entitlements['com.apple.developer.associated-domains'] = ['applinks:kaeru.vitaliy.velikodniy.name'] if mac_associated_domains
FileUtils.mkdir_p(File.join(__dir__, 'Mac'))
Xcodeproj::Plist.write_to_path(mac_entitlements, File.join(__dir__, 'Mac', 'Kaeru.entitlements'))
resources = %w[App/Configuration.plist App/Assets.xcassets].map { |path| group.new_file(path) }
[app, mac].each { |target| resources.each { |reference| target.resources_build_phase.add_file_reference(reference) } }
# Google Cast and the fetcher that came with its SDK are iOS-only; the Mac app links neither.
cast_framework = group.new_file('Dependencies/GoogleCastSDK-ios-4.8.6_static_xcframework/GoogleCast.xcframework')
cast_framework.last_known_file_type = 'wrapper.xcframework'
app.frameworks_build_phase.add_file_reference(cast_framework)
gtm_package = project.new(Xcodeproj::Project::Object::XCRemoteSwiftPackageReference)
gtm_package.repositoryURL = 'https://github.com/google/gtm-session-fetcher.git'
gtm_package.requirement = {
  'kind' => 'upToNextMajorVersion',
  'minimumVersion' => '3.5.0'
}
project.root_object.package_references << gtm_package
gtm_product = project.new(Xcodeproj::Project::Object::XCSwiftPackageProductDependency)
gtm_product.package = gtm_package
gtm_product.product_name = 'GTMSessionFetcherCore'
app.package_product_dependencies << gtm_product
# Swift that only one of the two applications compiles, whole files: the Cast SDK, the camera's QR
# scanner, UIKit's delegate and BGTaskScheduler exist only on iOS, and anything in a `Mac` folder
# only on the Mac. The rest is shared, with `#if os(…)` where a few lines differ.
ios_only = %w[App/KaeruAppDelegate.swift Cast/GoogleCastTransport.swift Features/QRScannerView.swift]
Dir[File.join(root, '**', '*.swift')].sort.each do |file|
  next if file.match?(%r{/(build[^/]*|Dependencies|Scripts)/})
  path = file.delete_prefix(root + '/')
  reference = group.new_file(path)
  if file.include?('/UITests/') then ui_tests.add_file_references([reference])
  elsif file.include?('/Tests/') then tests.add_file_references([reference])
  else
    app.add_file_references([reference]) unless path.split('/').include?('Mac')
    mac.add_file_references([reference]) unless ios_only.include?(path)
  end
end
# Firebase — Analytics and Crashlytics — only where the project's config is present. The plist is
# per-developer and outside git, like local.properties; without it the app builds the same, with
# `KAERU_FIREBASE` unset and every report a no-op (see Core/Reporting.swift). The Mac app is an app
# of its own in the Firebase project — its own bundle id, so its own plist, in Mac/ — and its crashes
# and symbols never mix with the iPad's.
firebase = File.exist?(File.join(__dir__, 'GoogleService-Info.plist'))
mac_firebase = File.exist?(File.join(__dir__, 'Mac', 'GoogleService-Info.plist'))
if firebase || mac_firebase
  firebase_package = project.new(Xcodeproj::Project::Object::XCRemoteSwiftPackageReference)
  firebase_package.repositoryURL = 'https://github.com/firebase/firebase-ios-sdk.git'
  firebase_package.requirement = { 'kind' => 'upToNextMajorVersion', 'minimumVersion' => '12.19.0' }
  project.root_object.package_references << firebase_package
end
{ app => firebase && 'App/GoogleService-Info.plist', mac => mac_firebase && 'App/Mac/GoogleService-Info.plist' }.each do |target, plist|
  next unless plist
  target.resources_build_phase.add_file_reference(group.new_file(plist))
  %w[FirebaseAnalytics FirebaseCrashlytics].each do |name|
    product = project.new(Xcodeproj::Project::Object::XCSwiftPackageProductDependency)
    product.package = firebase_package
    product.product_name = name
    target.package_product_dependencies << product
  end
  # Symbols for the crash reports. Crashlytics' own script, from the checkout SPM made; it needs the
  # dSYM, which is why the debug information format below is the one that writes one.
  upload = target.new_shell_script_build_phase('Upload symbols to Crashlytics')
  upload.shell_script = '"${BUILD_DIR%/Build/*}/SourcePackages/checkouts/firebase-ios-sdk/Crashlytics/run"'
  upload.input_paths = [
    '${DWARF_DSYM_FOLDER_PATH}/${DWARF_DSYM_FILE_NAME}',
    '${DWARF_DSYM_FOLDER_PATH}/${DWARF_DSYM_FILE_NAME}/Contents/Resources/DWARF/${PRODUCT_NAME}',
    '${DWARF_DSYM_FOLDER_PATH}/${DWARF_DSYM_FILE_NAME}/Contents/Info.plist',
    '$(TARGET_BUILD_DIR)/$(UNLOCALIZED_RESOURCES_FOLDER_PATH)/GoogleService-Info.plist',
    '$(TARGET_BUILD_DIR)/$(EXECUTABLE_PATH)'
  ]
end

# The same script builds KaeruShared for either platform: Kotlin picks the target from the SDK and
# the architectures Xcode is building for, and the frameworks land in sibling folders.
[app, mac].each do |target|
  phase = target.new_shell_script_build_phase('Build shared framework')
  phase.shell_script = '/bin/sh "$SRCROOT/../Scripts/BuildSharedFramework.sh"'
  phase.always_out_of_date = '1'
  target.build_phases.delete(phase)
  target.build_phases.unshift(phase)
end

shared_settings = {
  'SWIFT_VERSION' => '5.0',
  # Apple silicon only, on the Mac as well: Kotlin 2.3 no longer builds for Intel Macs, and with
  # both architectures asked for, the Intel half of KaeruShared would be missing at link time.
  'ARCHS' => 'arm64',
  'GENERATE_INFOPLIST_FILE' => 'YES',
  'ENABLE_USER_SCRIPT_SANDBOXING' => 'NO',
  'FRAMEWORK_SEARCH_PATHS' => ['$(inherited)', '$(SRCROOT)/../../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)'],
  'OTHER_LDFLAGS' => ['$(inherited)', '-framework', 'KaeruShared'],
  'SWIFT_EMIT_LOC_STRINGS' => 'YES',
  # Swift 6's full data-race checking, on Swift 5 language mode: the discipline was already
  # being kept by hand — every model is `@MainActor`, every transport hands its callbacks back
  # to it — and this is what stops the next file from quietly not keeping it.
  'SWIFT_STRICT_CONCURRENCY' => 'complete',
  'MARKETING_VERSION' => version_name,
  'CURRENT_PROJECT_VERSION' => '1'
}
[app, tests, ui_tests].each do |target|
  target.build_configurations.each do |config|
    config.build_settings.merge!(shared_settings.transform_values(&:dup)).merge!({
      'IPHONEOS_DEPLOYMENT_TARGET' => '17.0',
      'TARGETED_DEVICE_FAMILY' => '1,2',
      'PRODUCT_BUNDLE_IDENTIFIER' => target == app ? 'app.kaeru.ios' : "app.kaeru.ios.#{target.name.downcase}",
      'LD_RUNPATH_SEARCH_PATHS' => ['$(inherited)', '@executable_path/Frameworks']
    })
    if target == app
      config.build_settings['FRAMEWORK_SEARCH_PATHS'] = ['$(inherited)', '$(SRCROOT)/../../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)', '$(SRCROOT)/../Dependencies/GoogleCastSDK-ios-4.8.6_static_xcframework/GoogleCast.xcframework']
      config.build_settings['OTHER_LDFLAGS'] = ['$(inherited)', '-ObjC', '-lc++', '-framework', 'KaeruShared', '-framework', 'GoogleCast']
      config.build_settings.merge!({
        'DEVELOPMENT_TEAM' => team,
        'INFOPLIST_FILE' => 'Info.plist',
        'SWIFT_ACTIVE_COMPILATION_CONDITIONS' => firebase ? ['$(inherited)', 'KAERU_FIREBASE'] : ['$(inherited)'],
        'DEBUG_INFORMATION_FORMAT' => 'dwarf-with-dsym',
        'CODE_SIGN_ENTITLEMENTS' => associated_domains ? 'Kaeru.entitlements' : nil,
        'ASSETCATALOG_COMPILER_APPICON_NAME' => 'AppIcon',
        # Kaeru's amber, so the system chrome agrees with the palette instead of staying blue.
        'ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME' => 'AccentColor',
        'INFOPLIST_KEY_CFBundleDisplayName' => 'Kaeru',
        'INFOPLIST_KEY_UILaunchScreen_Generation' => 'YES',
        'INFOPLIST_KEY_UIApplicationSceneManifest_Generation' => 'YES',
        'INFOPLIST_KEY_UISupportedInterfaceOrientations' => 'UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight',
        'INFOPLIST_KEY_UISupportedInterfaceOrientations_iPad' => 'UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight'
      })
    elsif target == tests
      config.build_settings['TEST_HOST'] = '$(BUILT_PRODUCTS_DIR)/Kaeru.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/Kaeru'
      config.build_settings['BUNDLE_LOADER'] = '$(TEST_HOST)'
    else
      config.build_settings['TEST_TARGET_NAME'] = 'Kaeru'
    end
  end
end
mac.build_configurations.each do |config|
  config.build_settings.merge!(shared_settings.transform_values(&:dup)).merge!({
    'MACOSX_DEPLOYMENT_TARGET' => '15.0',
    'PRODUCT_NAME' => 'Kaeru',
    'PRODUCT_BUNDLE_IDENTIFIER' => 'app.kaeru.mac',
    'LD_RUNPATH_SEARCH_PATHS' => ['$(inherited)', '@executable_path/../Frameworks'],
    'DEVELOPMENT_TEAM' => team,
    'CODE_SIGN_STYLE' => 'Automatic',
    # Notarization requires it; the entitlements above are the exceptions it lets through.
    'ENABLE_HARDENED_RUNTIME' => 'YES',
    'CODE_SIGN_ENTITLEMENTS' => 'Mac/Kaeru.entitlements',
    'INFOPLIST_FILE' => 'Mac/Info.plist',
    'SWIFT_ACTIVE_COMPILATION_CONDITIONS' => mac_firebase ? ['$(inherited)', 'KAERU_FIREBASE'] : ['$(inherited)'],
    'DEBUG_INFORMATION_FORMAT' => 'dwarf-with-dsym',
    'ASSETCATALOG_COMPILER_APPICON_NAME' => 'AppIcon',
    'ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME' => 'AccentColor',
    'INFOPLIST_KEY_CFBundleDisplayName' => 'Kaeru'
  })
end
project.save
scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(app)
scheme.add_test_target(tests)
scheme.set_launch_target(app)
scheme.save_as(project_path, 'Kaeru', true)
scheme.test_action.should_use_launch_scheme_args_env = false
scheme.test_action.environment_variables = Xcodeproj::XCScheme::EnvironmentVariables.new([{ key: 'KAERU_LIVE_TESTS', value: '1', enabled: true }])
scheme.save_as(project_path, 'Kaeru-Live', true)
ui_scheme = Xcodeproj::XCScheme.new
ui_scheme.add_build_target(app)
ui_scheme.add_test_target(ui_tests)
ui_scheme.set_launch_target(app)
ui_scheme.save_as(project_path, 'Kaeru-UI', true)
mac_scheme = Xcodeproj::XCScheme.new
mac_scheme.add_build_target(mac)
mac_scheme.set_launch_target(mac)
mac_scheme.save_as(project_path, 'KaeruMac', true)
puts "Generated #{project_path}"
