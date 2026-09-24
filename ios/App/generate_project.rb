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
Xcodeproj::Plist.write_to_path(configuration, File.join(__dir__, 'Configuration.plist'))
app.resources_build_phase.add_file_reference(group.new_file('App/Configuration.plist'))
app.resources_build_phase.add_file_reference(group.new_file('App/Assets.xcassets'))
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
Dir[File.join(root, '**', '*.swift')].sort.each do |file|
  next if file.match?(%r{/(build[^/]*|Dependencies)/})
  reference = group.new_file(file.delete_prefix(root + '/'))
  target = file.include?('/UITests/') ? ui_tests : (file.include?('/Tests/') ? tests : app)
  target.add_file_references([reference])
end
phase = app.new_shell_script_build_phase('Build shared framework')
phase.shell_script = '/bin/sh "$SRCROOT/../Scripts/BuildSharedFramework.sh"'
phase.always_out_of_date = '1'
app.build_phases.delete(phase)
app.build_phases.unshift(phase)

[app, tests, ui_tests].each do |target|
  target.build_configurations.each do |config|
    config.build_settings.merge!({
      'SWIFT_VERSION' => '5.0',
      'ARCHS' => 'arm64',
      'IPHONEOS_DEPLOYMENT_TARGET' => '17.0',
      'TARGETED_DEVICE_FAMILY' => '1,2',
      'GENERATE_INFOPLIST_FILE' => 'YES',
      'PRODUCT_BUNDLE_IDENTIFIER' => target == app ? 'app.kaeru.ios' : "app.kaeru.ios.#{target.name.downcase}",
      'ENABLE_USER_SCRIPT_SANDBOXING' => 'NO',
      'FRAMEWORK_SEARCH_PATHS' => ['$(inherited)', '$(SRCROOT)/../../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)'],
      'OTHER_LDFLAGS' => ['$(inherited)', '-framework', 'KaeruShared'],
      'LD_RUNPATH_SEARCH_PATHS' => ['$(inherited)', '@executable_path/Frameworks'],
      'SWIFT_EMIT_LOC_STRINGS' => 'YES',
      # Swift 6's full data-race checking, on Swift 5 language mode: the discipline was already
      # being kept by hand — every model is `@MainActor`, every transport hands its callbacks back
      # to it — and this is what stops the next file from quietly not keeping it.
      'SWIFT_STRICT_CONCURRENCY' => 'complete',
      'MARKETING_VERSION' => version_name,
      'CURRENT_PROJECT_VERSION' => '1'
    })
    if target == app
      config.build_settings['FRAMEWORK_SEARCH_PATHS'] = ['$(inherited)', '$(SRCROOT)/../../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)', '$(SRCROOT)/../Dependencies/GoogleCastSDK-ios-4.8.6_static_xcframework/GoogleCast.xcframework']
      config.build_settings['OTHER_LDFLAGS'] = ['$(inherited)', '-ObjC', '-lc++', '-framework', 'KaeruShared', '-framework', 'GoogleCast']
      config.build_settings.merge!({
        'DEVELOPMENT_TEAM' => ENV['DEVELOPMENT_TEAM'] || properties['DEVELOPMENT_TEAM'] || 'TXY49DW96F',
        'INFOPLIST_FILE' => 'Info.plist',
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
puts "Generated #{project_path}"
