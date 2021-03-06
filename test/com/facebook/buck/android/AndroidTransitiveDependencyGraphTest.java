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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.cpp.PrebuiltNativeLibraryBuildRule;
import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.java.KeystoreRule;
import com.facebook.buck.java.PrebuiltJarRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.testutil.RuleMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

public class AndroidTransitiveDependencyGraphTest {

  /**
   * This is a regression test to ensure that an additional 1 second startup cost is not
   * re-introduced to fb4a.
   */
  @Test
  public void testFindTransitiveDependencies() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // Create an AndroidBinaryRule that transitively depends on two prebuilt JARs. One of the two
    // prebuilt JARs will be listed in the AndroidBinaryRule's no_dx list.
    PrebuiltJarRule guavaRule = ruleResolver.buildAndAddToIndex(
        PrebuiltJarRule.newPrebuiltJarRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/guava:guava"))
        .setBinaryJar("third_party/guava/guava-10.0.1.jar")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    PrebuiltJarRule jsr305Rule = ruleResolver.buildAndAddToIndex(
        PrebuiltJarRule.newPrebuiltJarRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/jsr-305:jsr-305"))
        .setBinaryJar("third_party/jsr-305/jsr305.jar")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));


    NdkLibraryRule ndkLibraryRule = ruleResolver.buildAndAddToIndex(
        NdkLibraryRule.newNdkLibraryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(BuildTargetFactory.newInstance("//java/com/facebook/native_library:library"))
            .addSrc("Android.mk")
            .setIsAsset(false)
            .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    PrebuiltNativeLibraryBuildRule prebuiltNativeLibraryBuildRule = ruleResolver.buildAndAddToIndex(
        PrebuiltNativeLibraryBuildRule.newPrebuiltNativeLibrary(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/facebook/prebuilt_native_library:library"))
        .setNativeLibsDirectory("/java/com/facebook/prebuilt_native_library/libs")
        .setIsAsset(true)
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    DefaultJavaLibraryRule libraryRule = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/facebook:example"))
            .addDep(guavaRule.getBuildTarget())
            .addDep(jsr305Rule.getBuildTarget())
            .addDep(prebuiltNativeLibraryBuildRule.getBuildTarget())
            .addDep(ndkLibraryRule.getBuildTarget()));

    AndroidResourceRule manifestRule = ruleResolver.buildAndAddToIndex(
        AndroidResourceRule.newAndroidResourceRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/facebook:res"))
        .setManifestFile("java/src/com/facebook/module/AndroidManifest.xml")
        .setAssetsDirectory("assets/"));

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    ruleResolver.buildAndAddToIndex(
        KeystoreRule.newKeystoreBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(keystoreTarget)
        .setStore("keystore/debug.keystore")
        .setProperties("keystore/debug.keystore.properties"));

    AndroidBinaryRule binaryRule = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/facebook:app"))
        .addDep(libraryRule.getBuildTarget())
        .addDep(manifestRule.getBuildTarget())
        .addBuildRuleToExcludeFromDex(BuildTargetFactory.newInstance("//third_party/guava:guava"))
        .setManifest("java/src/com/facebook/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(keystoreTarget));

    // Verify that the correct transitive dependencies are found.
    DependencyGraph graph = RuleMap.createGraphFromBuildRules(ruleResolver);
    AndroidTransitiveDependencies transitiveDeps = binaryRule.findTransitiveDependencies(graph);
    AndroidDexTransitiveDependencies dexTransitiveDeps =
    		binaryRule.findDexTransitiveDependencies(graph);
    assertEquals(
        "Because guava was passed to no_dx, it should not be in the classpathEntriesToDex list",
        ImmutableSet.of("third_party/jsr-305/jsr305.jar"),
        dexTransitiveDeps.classpathEntriesToDex);
    assertEquals(
        "Because guava was passed to no_dx, it should not be treated as a third-party JAR whose " +
            "resources need to be extracted and repacked in the APK. If this is done, then code in " +
            "the guava-10.0.1.dex.1.jar in the APK's assets/ folder may try to load the resource " +
            "from the APK as a ZipFileEntry rather than as a resource within guava-10.0.1.dex.1.jar. " +
            "Loading a resource in this way could take substantially longer. Specifically, this was " +
            "observed to take over one second longer to load the resource in fb4a. Because the " +
            "resource was loaded on startup, this introduced a substantial regression in the startup " +
            "time for the fb4a app.",
        ImmutableSet.of("third_party/jsr-305/jsr305.jar"),
        dexTransitiveDeps.pathsToThirdPartyJars);
    assertEquals(
        "Because assets directory was passed an AndroidResourceRule it should be added to the " +
            "transitive dependencies",
        ImmutableSet.of("assets/"),
        transitiveDeps.assetsDirectories);
    assertEquals(
        "Because manifest file was passed an AndroidResourceRule it should be added to the " +
            "transitive dependencies",
        ImmutableSet.of("java/src/com/facebook/module/AndroidManifest.xml"),
        transitiveDeps.manifestFiles);
    assertEquals(
        "Because a native library was declared as a dependency, it should be added to the " +
            "transitive dependencies.",
        ImmutableSet.of(ndkLibraryRule.getLibraryPath()),
        transitiveDeps.nativeLibsDirectories);
    assertEquals(
        "Because a prebuilt native library  was declared as a dependency (and asset), it should " +
            "be added to the transitive dependecies.",
        ImmutableSet.of(prebuiltNativeLibraryBuildRule.getLibraryPath()),
        transitiveDeps.nativeLibAssetsDirectories);
  }
}
