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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.easymock.EasyMock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Iterator;
import java.util.Map;

import javax.annotation.Nullable;

public class BuckConfigTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testSortOrder() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "one =   //foo:one",
        "two =   //foo:two",
        "three = //foo:three",
        "four  = //foo:four"));
    Map<String, Map<String, String>> sectionsToEntries = BuckConfig.createFromReaders(
        ImmutableList.of(reader));
    Map<String, String> aliases = sectionsToEntries.get("alias");

    // Verify that entries are sorted in the order that they appear in the file, rather than in
    // alphabetical order, or some sort of hashed-key order.
    Iterator<Map.Entry<String, String>> entries = aliases.entrySet().iterator();

    Map.Entry<String, String> first = entries.next();
    assertEquals("one", first.getKey());

    Map.Entry<String, String> second = entries.next();
    assertEquals("two", second.getKey());

    Map.Entry<String, String> third = entries.next();
    assertEquals("three", third.getKey());

    Map.Entry<String, String> fourth = entries.next();
    assertEquals("four", fourth.getKey());

    assertFalse(entries.hasNext());
  }

  /**
   * Ensure that whichever alias is listed first in the file is the one used in the reverse map if
   * the value appears multiple times.
   */
  @Test
  public void testGetBasePathToAliasMap() throws IOException, NoSuchBuildTargetException {
    BuildTargetParser parser = EasyMock.createMock(BuildTargetParser.class);
    EasyMock.expect(parser.parse("//java/com/example:fbandroid", ParseContext.fullyQualified()))
        .andReturn(BuildTargetFactory.newInstance("//java/com/example:fbandroid"))
        .anyTimes();
    EasyMock.replay(parser);

    Reader reader1 = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "fb4a   =   //java/com/example:fbandroid",
        "katana =   //java/com/example:fbandroid"));
    BuckConfig config1 = createWithDefaultFilesystem(reader1, parser);
    assertEquals(ImmutableMap.of("java/com/example", "fb4a"), config1.getBasePathToAliasMap());
    assertEquals(
        ImmutableMap.of(
            "fb4a", "//java/com/example:fbandroid",
            "katana", "//java/com/example:fbandroid"),
        config1.getEntriesForSection("alias"));

    Reader reader2 = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "katana =   //java/com/example:fbandroid",
        "fb4a   =   //java/com/example:fbandroid"));
    BuckConfig config2 = createWithDefaultFilesystem(reader2, parser);
    assertEquals(ImmutableMap.of("java/com/example", "katana"), config2.getBasePathToAliasMap());
    assertEquals(
        ImmutableMap.of(
            "fb4a", "//java/com/example:fbandroid",
            "katana", "//java/com/example:fbandroid"),
        config2.getEntriesForSection("alias"));

    Reader noAliasesReader = new StringReader("");
    BuckConfig noAliasesConfig = createWithDefaultFilesystem(noAliasesReader, parser);
    assertEquals(ImmutableMap.of(), noAliasesConfig.getBasePathToAliasMap());
    assertEquals(ImmutableMap.of(), noAliasesConfig.getEntriesForSection("alias"));

    EasyMock.verify(parser);
  }

  @Test
  public void testConstructorThrowsForMalformedBuildTarget() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "fb4a   = :fb4a"));
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.replay(projectFilesystem);

    try {
      BuildTargetParser parser = new BuildTargetParser(projectFilesystem);
      createWithDefaultFilesystem(reader, parser);
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals(":fb4a must start with //", e.getHumanReadableErrorMessage());
    }

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void testConstructorThrowsNonExistentBasePath() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "katana = //java/com/example:fb4a"));
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem.exists("java/com/example")).andReturn(false);
    EasyMock.replay(projectFilesystem);

    try {
      BuildTargetParser parser = new BuildTargetParser(projectFilesystem);
      createWithDefaultFilesystem(reader, parser);
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals(
          "No directory java/com/example when resolving target //java/com/example:fb4a " +
          "in context FULLY_QUALIFIED",
          e.getHumanReadableErrorMessage());
    }

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void testGetBuildTargetForAlias() throws IOException, NoSuchBuildTargetException {
    BuildTargetParser parser = EasyMock.createMock(BuildTargetParser.class);
    EasyMock.expect(parser.parse("//java/com/example:foo", ParseContext.fullyQualified()))
        .andReturn(BuildTargetFactory.newInstance("//java/com/example:foo"));
    EasyMock.expect(parser.parse("//java/com/example:bar", ParseContext.fullyQualified()))
    .andReturn(BuildTargetFactory.newInstance("//java/com/example:bar"));
    EasyMock.replay(parser);

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "foo = //java/com/example:foo",
        "bar = //java/com/example:bar"));
    BuckConfig config = createWithDefaultFilesystem(reader, parser);
    assertEquals("//java/com/example:foo", config.getBuildTargetForAlias("foo"));
    assertEquals("//java/com/example:bar", config.getBuildTargetForAlias("bar"));
    assertNull(
        "Invalid alias names, such as build targets, should be tolerated by this method.",
        config.getBuildTargetForAlias("//java/com/example:foo"));
    assertNull(config.getBuildTargetForAlias("baz"));

    Reader noAliasesReader = new StringReader("");
    BuckConfig noAliasesConfig = createWithDefaultFilesystem(noAliasesReader, parser);
    assertNull(noAliasesConfig.getBuildTargetForAlias("foo"));
    assertNull(noAliasesConfig.getBuildTargetForAlias("bar"));
    assertNull(noAliasesConfig.getBuildTargetForAlias("baz"));

    EasyMock.verify(parser);
  }

  /**
   * Ensures that all public methods of BuckConfig return reasonable values for an empty config.
   */
  @Test
  public void testEmptyConfig() {
    BuckConfig emptyConfig = new FakeBuckConfig();
    assertEquals(ImmutableMap.of(), emptyConfig.getEntriesForSection("alias"));
    assertNull(emptyConfig.getBuildTargetForAlias("fb4a"));
    assertEquals(ImmutableMap.of(), emptyConfig.getBasePathToAliasMap());
    assertEquals(0, Iterables.size(emptyConfig.getDefaultIncludes()));
  }

  @Test
  public void testValidateAliasName() {
    BuckConfig.validateAliasName("f");
    BuckConfig.validateAliasName("_");
    BuckConfig.validateAliasName("F");
    BuckConfig.validateAliasName("fb4a");
    BuckConfig.validateAliasName("FB4A");
    BuckConfig.validateAliasName("FB4_");

    try {
      BuckConfig.validateAliasName(null);
      fail("Should have thrown HumanReadableException");
    } catch (HumanReadableException e) {
      assertEquals("Alias cannot be null.", e.getHumanReadableErrorMessage());
    }

    try {
      BuckConfig.validateAliasName("");
      fail("Should have thrown HumanReadableException");
    } catch (HumanReadableException e) {
      assertEquals("Alias cannot be the empty string.", e.getHumanReadableErrorMessage());
    }

    try {
      BuckConfig.validateAliasName("42meaningOfLife");
      fail("Should have thrown HumanReadableException");
    } catch (HumanReadableException e) {
      assertEquals("Not a valid alias: 42meaningOfLife.", e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testReferentialAliases() throws IOException, NoSuchBuildTargetException {
    BuildTargetParser parser = EasyMock.createMock(BuildTargetParser.class);
    EasyMock.expect(parser.parse("//java/com/example:foo", ParseContext.fullyQualified()))
        .andReturn(BuildTargetFactory.newInstance("//java/com/example:foo"));
    EasyMock.expect(parser.parse("//java/com/example:bar", ParseContext.fullyQualified()))
        .andReturn(BuildTargetFactory.newInstance("//java/com/example:bar"));
    EasyMock.replay(parser);

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "foo            = //java/com/example:foo",
        "bar            = //java/com/example:bar",
        "foo_codename   = foo",
        "",
        "# Do not delete these: automation builds require these aliases to exist!",
        "automation_foo = foo_codename",
        "automation_bar = bar"));
    BuckConfig config = createWithDefaultFilesystem(reader, parser);
    assertEquals("//java/com/example:foo", config.getBuildTargetForAlias("foo"));
    assertEquals("//java/com/example:bar", config.getBuildTargetForAlias("bar"));
    assertEquals("//java/com/example:foo", config.getBuildTargetForAlias("foo_codename"));
    assertEquals("//java/com/example:foo", config.getBuildTargetForAlias("automation_foo"));
    assertEquals("//java/com/example:bar", config.getBuildTargetForAlias("automation_bar"));
    assertNull(config.getBuildTargetForAlias("baz"));

    EasyMock.verify(parser);
  }

  @Test
  public void testUnresolvedAliasThrows() throws IOException, NoSuchBuildTargetException {
    BuildTargetParser parser = EasyMock.createMock(BuildTargetParser.class);
    EasyMock.expect(parser.parse("//java/com/example:foo", ParseContext.fullyQualified()))
        .andReturn(BuildTargetFactory.newInstance("//java/com/example:foo"));
    EasyMock.replay(parser);

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "foo = //java/com/example:foo",
        "bar = food"));
    try {
      createWithDefaultFilesystem(reader, parser);
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals("No alias for: food.", e.getHumanReadableErrorMessage());
    }

    EasyMock.verify(parser);
  }

  @Test
  public void testDuplicateAliasDefinitionThrows() throws IOException, NoSuchBuildTargetException {
    BuildTargetParser parser = EasyMock.createMock(BuildTargetParser.class);
    EasyMock.replay(parser);

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[alias]",
        "foo = //java/com/example:foo",
        "foo = //java/com/example:foo"));
    try {
      createWithDefaultFilesystem(reader, parser);
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals(
          "Throw an exception if there are duplicate definitions for an alias, " +
              "even if the values are the same.",
          "Duplicate definition for foo in [alias].",
          e.getHumanReadableErrorMessage());
    }

    EasyMock.verify(parser);
  }

  @Test
  public void testExcludedLabels() throws IOException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[test]",
        "excluded_labels = windows, linux"));
    BuckConfig config = createWithDefaultFilesystem(reader, null);

    assertEquals(ImmutableSet.of("windows", "linux"), config.getDefaultExcludedLabels());
  }

  @Test
  public void testIgnorePaths() throws IOException {
    ProjectFilesystem filesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(filesystem.getPathRelativizer()).andReturn(Functions.<String>identity())
        .times(2);
    BuildTargetParser parser = EasyMock.createMock(BuildTargetParser.class);
    EasyMock.replay(filesystem, parser);

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[project]",
        "ignore = .git, foo, bar/, baz//, a/b/c"));
    BuckConfig config = BuckConfig.createFromReader(reader, filesystem, parser);

    ImmutableSet<String> ignorePaths = config.getIgnorePaths();
    assertEquals("Should ignore paths, sans trailing slashes", ignorePaths, ImmutableSet.of(
        BuckConstant.BUCK_OUTPUT_DIRECTORY,
        ".idea",
        System.getProperty(BuckConfig.BUCK_BUCKD_DIR_KEY, ".buckd"),
        config.getCacheDir(),
        ".git",
        "foo",
        "bar",
        "baz",
        "a/b/c"
    ));

    EasyMock.verify(filesystem, parser);
  }

  @Test
  public void testIgnorePathsWithRelativeCacheDir() throws IOException {
    ProjectFilesystem filesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(filesystem.getPathRelativizer()).andReturn(Functions.<String>identity());
    BuildTargetParser parser = EasyMock.createMock(BuildTargetParser.class);
    EasyMock.replay(filesystem, parser);

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[cache]",
        "dir = cache_dir"));
    BuckConfig config = BuckConfig.createFromReader(reader, filesystem, parser);

    ImmutableSet<String> ignorePaths = config.getIgnorePaths();
    assertTrue("Relative cache directory should be in set of ignored paths",
        ignorePaths.contains("cache_dir"));

    EasyMock.verify(filesystem, parser);
  }

  @Test
  public void testIgnorePathsWithAbsoluteCacheDir() throws IOException {

    ProjectFilesystem filesystem = EasyMock.createMock(ProjectFilesystem.class);
    BuildTargetParser parser = EasyMock.createMock(BuildTargetParser.class);
    EasyMock.replay(filesystem, parser);
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[cache]",
        "dir = /cache_dir"));
    BuckConfig config = BuckConfig.createFromReader(reader, filesystem, parser);

    ImmutableSet<String> ignorePaths = config.getIgnorePaths();
    assertFalse("Absolute cache directory should not be in set of ignored paths",
        ignorePaths.contains("/cache_dir"));

    EasyMock.verify(filesystem, parser);
  }

  @Test
  public void testBuckPyIgnorePaths() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "buck_py_ignore_paths", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "--all");
    assertEquals("buck test --all should exit cleanly", 0, result.getExitCode());
  }

  @Test
  public void testGetDefaultTestTimeoutMillis() throws IOException {
    assertEquals(0L, new FakeBuckConfig().getDefaultTestTimeoutMillis());

    Reader reader = new StringReader(Joiner.on('\n').join(
        "[test]",
        "timeout = 54321"));
    BuckConfig config = createWithDefaultFilesystem(reader, null);
    assertEquals(54321L, config.getDefaultTestTimeoutMillis());
  }

  @Test
  public void testOverride() throws IOException {
    Reader readerA = new StringReader(Joiner.on('\n').join(
        "[cache]",
        "    mode = dir,cassandra"));
    Reader readerB = new StringReader(Joiner.on('\n').join(
        "[cache]",
        "    mode ="));
    // Verify that no exception is thrown when a definition is overridden.
    BuckConfig.createFromReaders(ImmutableList.of(readerA, readerB));
  }

  private BuckConfig createWithDefaultFilesystem(Reader reader, @Nullable BuildTargetParser parser)
      throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(new File("."));
    if (parser == null) {
      parser = new BuildTargetParser(projectFilesystem);
    }
    return BuckConfig.createFromReader(reader, projectFilesystem, parser);
  }
}
