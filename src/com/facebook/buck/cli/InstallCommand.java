/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.facebook.buck.command.Build;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.InstallableBuildRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.AndroidManifestReader;
import com.facebook.buck.util.DefaultAndroidManifestReader;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Command so a user can build and install an APK.
 */
public class InstallCommand extends UninstallSupportCommandRunner<InstallCommandOptions> {

  protected InstallCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  InstallCommandOptions createOptions(BuckConfig buckConfig) {
    return new InstallCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptions(InstallCommandOptions options) throws IOException {
    // Make sure that only one build target is specified.
    if (options.getArguments().size() != 1) {
      getStdErr().println("Must specify exactly one android_binary() or apk_genrule() rule.");
      return 1;
    }

    // Build the specified target.
    BuildCommand buildCommand = new BuildCommand(getCommandRunnerParams());
    int exitCode = buildCommand.runCommandWithOptions(options);
    if (exitCode != 0) {
      return exitCode;
    }

    // Get the build rule that was built. Verify that it is an android_binary() rule.
    Build build = buildCommand.getBuild();
    DependencyGraph graph = build.getDependencyGraph();
    BuildRule buildRule = graph.findBuildRuleByTarget(buildCommand.getBuildTargets().get(0));
    if (!(buildRule instanceof InstallableBuildRule)) {
      console.printBuildFailure(String.format(
          "Specified rule %s must be of type android_binary() or apk_genrule() but was %s().\n",
          buildRule.getFullyQualifiedName(),
          buildRule.getType().getName()));
      return 1;
    }
    InstallableBuildRule installableBuildRule = (InstallableBuildRule)buildRule;

    // Uninstall the app first, if requested.
    if (options.shouldUninstallFirst()) {
      String packageName = tryToExtractPackageNameFromManifest(installableBuildRule);
      uninstallApk(packageName,
          options.adbOptions(),
          options.targetDeviceOptions(),
          options.uninstallOptions(),
          build.getExecutionContext());
      // Perhaps the app wasn't installed to begin with, shouldn't stop us.
    }

    if (!installApk(installableBuildRule, options, build.getExecutionContext())) {
      return 1;
    }

    // We've installed the application successfully.
    // Is either of --activity or --run present?
    if (options.shouldStartActivity()) {
      exitCode = startActivity(installableBuildRule, options.getActivityToStart(), options,
          build.getExecutionContext());
      if (exitCode != 0) {
        return exitCode;
      }
    }

    return exitCode;
  }

  private int startActivity(InstallableBuildRule androidBinaryRule, String activity,
      InstallCommandOptions options, ExecutionContext context) throws IOException {

    // Might need the package name and activities from the AndroidManifest.
    AndroidManifestReader reader = DefaultAndroidManifestReader.forPath(
        androidBinaryRule.getManifest());

    if (activity == null) {
      // Get list of activities that show up in the launcher.
      List<String> launcherActivities = reader.getLauncherActivities();

      // Sanity check.
      if (launcherActivities.isEmpty()) {
        console.printBuildFailure("No launchable activities found.");
        return 1;
      } else if (launcherActivities.size() > 1) {
        console.printBuildFailure("Default activity is ambiguous.");
        return 1;
      }

      // Construct a component for the '-n' argument of 'adb shell am start'.
      activity = reader.getPackage() + "/" + launcherActivities.get(0);
    } else if (!activity.contains("/")) {
      // If no package name was provided, assume the one in the manifest.
      activity = reader.getPackage() + "/" + activity;
    }

    final String activityToRun = activity;

    PrintStream stdOut = console.getStdOut();
    stdOut.println(String.format("Starting activity %s...", activityToRun));

    getBuckEventBus().post(StartActivityEvent.started(androidBinaryRule.getBuildTarget(),
        activityToRun));
    boolean success = adbCall(options.adbOptions(),
        options.targetDeviceOptions(),
        context,
        new AdbCallable() {
          @Override
          public boolean call(IDevice device) throws Exception {
            String err = deviceStartActivity(device, activityToRun);
            if (err != null) {
              console.printBuildFailure(err);
              return false;
            } else {
              return true;
            }
          }

          @Override
          public String toString() {
            return "start activity";
          }
        });
    getBuckEventBus().post(StartActivityEvent.finished(androidBinaryRule.getBuildTarget(),
        activityToRun,
        success));

    return success ? 0 : 1;

  }

  @VisibleForTesting
  String deviceStartActivity(IDevice device, String activityToRun) {
    try {
        ErrorParsingReceiver receiver = new ErrorParsingReceiver() {
          @Override
          protected String matchForError(String line) {
            // Parses output from shell am to determine if activity was started correctly.
            return (Pattern.matches("^([\\w_$.])*(Exception|Error|error).*$", line) ||
                line.contains("am: not found")) ? line : null;
          }
        };
        device.executeShellCommand(
            String.format("am start -n %s", activityToRun),
            receiver,
            INSTALL_TIMEOUT);
        return receiver.getErrorMessage();
    } catch (Exception e) {
      return e.toString();
    }
  }


  @Override
  String getUsageIntro() {
    return "Specify an android_binary() rule whose APK should be installed";
  }

  /**
   * Install apk on all matching devices. This functions performs device
   * filtering based on three possible arguments:
   *
   *  -e (emulator-only) - only emulators are passing the filter
   *  -d (device-only) - only real devices are passing the filter
   *  -s (serial) - only device/emulator with specific serial number are passing the filter
   *
   *  If more than one device matches the filter this function will fail unless multi-install
   *  mode is enabled (-x). This flag is used as a marker that user understands that multiple
   *  devices will be used to install the apk if needed.
   */
  @VisibleForTesting
  boolean installApk(InstallableBuildRule buildRule,
      InstallCommandOptions options,
      ExecutionContext context) {
    getBuckEventBus().post(InstallEvent.started(buildRule.getBuildTarget()));

    final File apk = new File(buildRule.getApkPath());
    boolean success = adbCall(options.adbOptions(),
        options.targetDeviceOptions(),
        context,
        new AdbCallable() {
          @Override
          public boolean call(IDevice device) throws Exception {
            return installApkOnDevice(device, apk);
          }

          @Override
          public String toString() {
            return "install apk";
          }
        });
    getBuckEventBus().post(InstallEvent.finished(buildRule.getBuildTarget(), success));

    return success;
  }


  /**
   * Installs apk on specific device. Reports success or failure to console.
   */
  @VisibleForTesting
  @SuppressWarnings("PMD.PrematureDeclaration")
  boolean installApkOnDevice(IDevice device, File apk) {
    String name;
    if (device.isEmulator()) {
      name = device.getSerialNumber() + " (" + device.getAvdName() + ")";
    } else {
      name = device.getSerialNumber();
      String model = device.getProperty("ro.product.model");
      if (model != null) {
        name += " (" + model + ")";
      }
    }

    PrintStream stdOut = console.getStdOut();
    stdOut.printf("Installing apk on %s.\n", name);
    try {
      long start = System.currentTimeMillis();
      String reason = device.installPackage(apk.getAbsolutePath(), true);
      long end = System.currentTimeMillis();

      if (reason != null) {
        console.printBuildFailure(String.format("Failed to install apk on %s: %s.", name, reason));
        return false;
      }

      long delta = end - start;
      stdOut.printf("Installed apk on %s in %d.%03ds.\n", name, delta / 1000, delta % 1000);
      return true;

    } catch (InstallException ex) {
      console.printBuildFailure(String.format("Failed to install apk on %s.", name));
      ex.printStackTrace(console.getStdErr());
      return false;
    }
  }



}
